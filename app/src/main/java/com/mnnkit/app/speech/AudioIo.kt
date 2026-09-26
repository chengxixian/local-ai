package com.mnnkit.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 录音 / 放音工具。
 *
 * - **录音**：[Recorder] 用 `AudioRecord` 采 16kHz 单声道 PCM，优先 `ENCODING_PCM_FLOAT`，
 *   设备/驱动不支持时自动回退 `ENCODING_PCM_16BIT` 并转成 float 后送出，统一封装成
 *   `Flow<FloatArray>`；[Recorder.stop] 让流正常结束。
 * - **放音**：[AudioPlayer] 用 `AudioTrack` 播 float PCM，同样自动回退 16bit。
 * - **权限**：`RECORD_AUDIO` 是运行时权限（manifest 已声明）。调用前用
 *   [hasRecordPermission] / [requireRecordPermission] 检查，缺失时给出可读错误
 *   （不会崩溃，也不会静默返回空音频）。
 *
 * 说明：本文件依赖 Android API，无法在 JVM 单元测试里运行；音频相关的纯逻辑
 * （重采样、定点转换）都放在 [SpeechAudio] 里单独测试。
 */
object AudioIo {

    private const val TAG = "MnnKitSpeech"

    /** sherpa 声学前端要求的采样率。 */
    const val DEFAULT_SAMPLE_RATE = SpeechAudio.SHERPA_SAMPLE_RATE

    /** 每次回调的样本数，100ms @16kHz —— 与 sherpa 流式识别的分块粒度匹配。 */
    const val DEFAULT_CHUNK_SAMPLES = 1_600

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    fun hasRecordPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 没有录音权限时抛出可读的 [SpeechException]。 */
    fun requireRecordPermission(context: Context) {
        if (!hasRecordPermission(context)) {
            throw SpeechException(
                "没有麦克风权限（RECORD_AUDIO）。请在系统设置 → 应用 → 权限里允许录音后重试。",
            )
        }
    }

    // ------------------------------------------------------------------
    // 录音
    // ------------------------------------------------------------------

    /**
     * 一步到位采集：`AudioIo.record(context).collect { pcm -> … }`。
     *
     * 需要"中途停止"时请自行创建 [Recorder] 并调用 [Recorder.stop]（取消收集协程亦可）。
     */
    fun record(
        context: Context,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
    ): Flow<FloatArray> = Recorder(context, sampleRate, chunkSamples).start()

    /**
     * 麦克风采集器。**一个实例只能 [start] 一次**（停止后请新建实例）。
     *
     * 采集失败（无权限、设备不支持、读取错误）会以 [SpeechException] 形式
     * 抛给流的收集者，由上层转成 UI 提示。
     */
    class Recorder(
        private val context: Context,
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        private val chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
    ) {

        private val stopped = AtomicBoolean(false)

        @Volatile
        private var opened: OpenRecord? = null

        val isRecording: Boolean
            get() = opened?.record?.recordingState == AudioRecord.RECORDSTATE_RECORDING && !stopped.get()

        /** 请求停止：使 [start] 返回的流正常结束（不再产生新数据）。 */
        fun stop() {
            stopped.set(true)
            // 主动 stop() 能让阻塞中的 read() 立刻返回
            runCatching { opened?.record?.stop() }
                .onFailure { Log.w(TAG, "停止录音失败", it) }
        }

        fun start(): Flow<FloatArray> = callbackFlow {
            requireRecordPermission(context)
            stopped.set(false)

            val open = openRecord()
            opened = open
            Log.i(TAG, "开始录音：${sampleRate}Hz 单声道，encoding=${open.encoding}")

            val job = launch(Dispatchers.IO) {
                val buffer = when (open.encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(chunkSamples)
                    else -> ShortArray(chunkSamples)
                }
                try {
                    open.record.startRecording()
                    if (open.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw SpeechException(
                            "麦克风启动失败（recordingState=${open.record.recordingState}），" +
                                "可能被其它应用占用",
                        )
                    }
                    while (isActive && !stopped.get()) {
                        if (buffer is FloatArray) {
                            val n = open.record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                            if (n > 0) {
                                send(buffer.copyOf(n))
                            } else if (n < 0) {
                                throw SpeechException("录音读取失败：AudioRecord.read 返回 $n")
                            }
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            val shorts = buffer as ShortArray
                            val n = open.record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                            if (n > 0) {
                                send(SpeechAudio.pcm16ToFloat(shorts, n))
                            } else if (n < 0) {
                                throw SpeechException("录音读取失败：AudioRecord.read 返回 $n")
                            }
                        }
                    }
                    close()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    close(if (t is SpeechException) t else SpeechException("录音中断：${SherpaNative.describe(t)}", t))
                }
            }

            awaitClose {
                stopped.set(true)
                job.cancel()
                runCatching { open.record.stop() }
                runCatching { open.record.release() }
                    .onFailure { Log.w(TAG, "释放 AudioRecord 失败", it) }
                opened = null
                Log.i(TAG, "录音已停止")
            }
        }

        private fun openRecord(): OpenRecord {
            var lastError: Throwable? = null
            // 依次尝试：语音识别音源（关掉 AGC/降噪，识别更准）→ 普通麦克风；
            //           float 采集 → 16bit 采集。
            for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                    val minBuffer = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        encoding,
                    )
                    if (minBuffer <= 0) {
                        lastError = SpeechException("设备不支持该录音格式（encoding=$encoding，错误码 $minBuffer）")
                        continue
                    }
                    try {
                        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        val bufferBytes = max(minBuffer * 2, chunkSamples * bytesPerSample * 4)
                        val record = AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(encoding)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build(),
                            )
                            .setBufferSizeInBytes(bufferBytes)
                            .build()
                        if (record.state != AudioRecord.STATE_INITIALIZED) {
                            record.release()
                            lastError = SpeechException("AudioRecord 初始化失败（source=$source, encoding=$encoding）")
                            continue
                        }
                        return OpenRecord(record, encoding)
                    } catch (t: Throwable) {
                        lastError = t
                        Log.w(TAG, "打开录音设备失败（source=$source, encoding=$encoding）", t)
                    }
                }
            }
            throw SpeechException(
                "无法打开麦克风：${lastError?.let { SherpaNative.describe(it) } ?: "设备不支持 ${sampleRate}Hz 单声道录音"}",
                lastError,
            )
        }

        private class OpenRecord(val record: AudioRecord, val encoding: Int)
    }

    // ------------------------------------------------------------------
    // 放音
    // ------------------------------------------------------------------

    /** 一次性播放（内部临时创建 [AudioPlayer]，播完自动释放）。 */
    suspend fun playOnce(pcm: FloatArray, sampleRate: Int) {
        val player = AudioPlayer()
        try {
            player.play(pcm, sampleRate)
        } finally {
            player.release()
        }
    }

    /**
     * PCM 播放器。可反复 [play]；[stop] 立即停止当前播放。
     *
     * 采样率用 TTS 返回的采样率（`SherpaTtsEngine.outputSampleRate`），
     * 不要假设是 16kHz —— bert-vits2 生成的是 44.1kHz 级别。
     */
    class AudioPlayer {

        @Volatile
        private var track: AudioTrack? = null

        private val stopped = AtomicBoolean(false)

        val isPlaying: Boolean
            get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

        /** 播放一段 float PCM（阻塞直到播完或 [stop] / 协程取消）。 */
        suspend fun play(pcm: FloatArray, sampleRate: Int) = withContext(Dispatchers.IO) {
            if (pcm.isEmpty()) return@withContext
            if (sampleRate <= 0) throw SpeechException("播放失败：采样率非法（$sampleRate）")

            stop()
            stopped.set(false)
            val (audioTrack, encoding) = openTrack(sampleRate)
            track = audioTrack
            try {
                audioTrack.play()
                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    writeAll(pcm, audioTrack)
                } else {
                    writeAll(SpeechAudio.floatToPcm16(pcm), audioTrack)
                }
                if (!stopped.get()) {
                    // 等缓冲区排空，避免尾部被截断
                    runCatching { audioTrack.stop() }
                }
            } catch (e: CancellationException) {
                runCatching { audioTrack.pause() }
                runCatching { audioTrack.flush() }
                throw e
            } finally {
                runCatching { audioTrack.release() }
                if (track === audioTrack) track = null
            }
        }

        private fun writeAll(pcm: FloatArray, audioTrack: AudioTrack) {
            var offset = 0
            while (offset < pcm.size && !stopped.get()) {
                val n = audioTrack.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) throw SpeechException("音频写入失败：AudioTrack.write 返回 $n")
                offset += n
            }
        }

        private fun writeAll(pcm: ShortArray, audioTrack: AudioTrack) {
            var offset = 0
            while (offset < pcm.size && !stopped.get()) {
                val n = audioTrack.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) throw SpeechException("音频写入失败：AudioTrack.write 返回 $n")
                offset += n
            }
        }

        /** 立即停止播放。 */
        fun stop() {
            stopped.set(true)
            val current = track ?: return
            runCatching { current.pause() }
            runCatching { current.flush() }
        }

        fun release() {
            stop()
            runCatching { track?.release() }
            track = null
        }

        private fun openTrack(sampleRate: Int): Pair<AudioTrack, Int> {
            var lastError: Throwable? = null
            for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    encoding,
                )
                if (minBuffer <= 0) {
                    lastError = SpeechException("设备不支持该播放格式（encoding=$encoding，错误码 $minBuffer）")
                    continue
                }
                try {
                    val audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(minBuffer * 2)
                        .build()
                    if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                        audioTrack.release()
                        lastError = SpeechException("AudioTrack 初始化失败（encoding=$encoding）")
                        continue
                    }
                    return audioTrack to encoding
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "创建播放器失败（encoding=$encoding）", t)
                }
            }
            throw SpeechException(
                "无法创建音频播放器（采样率 $sampleRate）：" +
                    (lastError?.let { SherpaNative.describe(it) } ?: "设备不支持"),
                lastError,
            )
        }
    }
}
