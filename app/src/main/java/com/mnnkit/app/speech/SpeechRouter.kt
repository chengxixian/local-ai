package com.mnnkit.app.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.mnnkit.app.data.api.ApiProviders
import com.mnnkit.app.util.MediaExport
import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音合成与播放的统一入口。
 *
 * ## 为什么需要这一层
 *
 * 两条 TTS 路径的**产物格式根本不同**，调用方不该关心：
 *
 * | 路径 | 产物 | 播放方式 |
 * |---|---|---|
 * | API（`/audio/speech`） | **mp3 字节流**（压缩） | `MediaPlayer` |
 * | 本地 Sherpa-MNN | **PCM float**（[TtsEngine.synthesize] 返回的原始采样） | `AudioTrack` |
 *
 * `AudioTrack` 播不了 mp3（它只吃 PCM），`MediaPlayer` 播 PCM 又要先封装容器 ——
 * 所以两条路各用各的播放器，在这里收口。
 *
 * ## 关于 mp3
 *
 * 走 API 时**服务端直接产出真 mp3**，不需要本地编码 —— 这正好绕开了
 * [AudioExport] 注释里说的「Android 没有内置 mp3 编码器」那个限制。
 * 本地路径则只能用 AAC(.m4a)，见 [AudioExport]。
 */
class SpeechRouter(
    private val context: Context,
    private val apiProviders: ApiProviders,
    private val outputDir: File,
) {

    /** 合成结果。 */
    data class Spoken(
        /** 落盘的文件，供「下载」用。 */
        val file: File,
        /** 实际格式：`mp3`（API）或 `m4a`（本地）。 */
        val format: String,
        /** 本地路径才有的 PCM，可用于 AudioTrack 播放；API 路径为 null。 */
        val pcm: FloatArray? = null,
        val sampleRate: Int = 0,
    )

    private var mediaPlayer: MediaPlayer? = null
    private var localPlayer: AudioIo.AudioPlayer? = null
    private var playbackJob: kotlinx.coroutines.Job? = null

    /** 当前是否有音频在播。 */
    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true || localPlayer?.isPlaying == true

    /**
     * 朗读一段文字。
     *
     * 优先走 API（能拿到真 mp3），不可用时退回本地 Sherpa。
     * **两条路都会落盘一个文件**，所以「下载」按钮在任何情况下都有东西可下。
     */
    suspend fun speak(
        text: String,
        localEngine: ((ModelItem?) -> com.mnnkit.core.speech.TtsEngine?)? = null,
        localModel: ModelItem? = null,
        speakerId: Int = 0,
        speed: Float = 1.0f,
    ): Spoken = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "没有可朗读的内容" }

        val api = apiProviders.active()?.takeIf { it.canTts }
        if (api != null) {
            return@withContext runCatching {
                val file = File(outputDir, "tts-${System.currentTimeMillis()}.mp3")
                apiProviders.speak(api, trimmed, file)
                Log.i(TAG, "API 合成完成：${file.name}（${file.length()} B）")
                Spoken(file = file, format = "mp3")
            }.getOrElse { e ->
                Log.w(TAG, "API 合成失败，尝试本地引擎", e)
                localSpeak(trimmed, localEngine, localModel, speakerId, speed)
            }
        }
        localSpeak(trimmed, localEngine, localModel, speakerId, speed)
    }

    private suspend fun localSpeak(
        text: String,
        localEngine: ((ModelItem?) -> com.mnnkit.core.speech.TtsEngine?)?,
        localModel: ModelItem?,
        speakerId: Int,
        speed: Float,
    ): Spoken {
        val engine = localEngine?.invoke(localModel)
            ?: throw SpeechException(
                "没有可用的语音合成：既没有配置带 TTS 的 API 提供商，" +
                    "也没有加载本地的语音合成模型。"
            )

        val pcm = engine.synthesize(text, speakerId, speed)
        if (pcm.isEmpty()) throw SpeechException("本地语音合成没有产出音频")

        // SherpaTtsEngine 在 synthesize 之后才有真实采样率
        val rate = (engine as? SherpaTtsEngine)?.outputSampleRate?.takeIf { it > 0 } ?: 16_000
        val shorts = SpeechAudio.floatToPcm16(pcm)

        val target = File(outputDir, "tts-${System.currentTimeMillis()}.m4a")
        val result = AudioExport.encodeToM4a(shorts, rate, target)
        Log.i(TAG, "本地合成完成：${target.name}（${target.length()} B，$rate Hz）")

        return Spoken(
            file = result.file,
            format = result.actualFormat,
            pcm = pcm,
            sampleRate = rate,
        )
    }

    /**
     * 播放 [Spoken]。
     *
     * **立即返回**，音频在后台播 —— `AudioPlayer.play()` 会挂起到播完为止，
     * 若在这里 await，UI 线程的调用方会被卡住整个朗读时长。
     */
    fun play(spoken: Spoken, scope: kotlinx.coroutines.CoroutineScope) {
        stop()
        playbackJob = scope.launch {
            runCatching {
                val pcm = spoken.pcm
                if (pcm != null && spoken.sampleRate > 0) {
                    // 本地：直接喂 PCM 给 AudioTrack（少一次解码，
                    // 且与语音页的播放行为一致）
                    localPlayer = AudioIo.AudioPlayer().also { it.play(pcm, spoken.sampleRate) }
                } else {
                    // API：文件是压缩的 mp3，AudioTrack 播不了，只能交给 MediaPlayer
                    playFileBlocking(spoken.file)
                }
            }.onFailure { Log.w(TAG, "播放失败", it) }
        }
    }

    /** 用 `MediaPlayer` 播一个音频文件，阻塞到播完（在 IO 线程调用）。 */
    private suspend fun playFileBlocking(file: File) = withContext(Dispatchers.IO) {
        val mp = MediaPlayer()
        val done = java.util.concurrent.CountDownLatch(1)
        mp.setOnCompletionListener { done.countDown() }
        mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
        mp.setDataSource(file.absolutePath)
        mp.prepare()
        mp.start()
        mediaPlayer = mp
        // 等待播完或被 stop() 打断
        done.await()
        runCatching { mp.release() }
        if (mediaPlayer === mp) mediaPlayer = null
    }

    /** 停止播放。可重复调用。 */
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        localPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        localPlayer = null
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
    }

    /**
     * 把合成结果导出到「音乐」目录，供用户长期保留。
     *
     * 走 [MediaExport]（MediaStore），**不需要任何存储权限**。
     */
    suspend fun export(spoken: Spoken, displayName: String): MediaExport.Result =
        withContext(Dispatchers.IO) {
            MediaExport.saveAudio(
                context = context,
                source = spoken.file,
                displayName = displayName,
                asMp3 = spoken.format.equals("mp3", ignoreCase = true),
            )
        }

    companion object {
        private const val TAG = "SpeechRouter"

        /**
         * 写一个标准 16-bit 单声道 WAV 文件。
         *
         * 用于把录音 PCM 交给 API 的 `/audio/transcriptions` —— 那个端点要求
         * **上传音频文件**（multipart），不能直接传 PCM 数组。
         *
         * 选 WAV 是因为它是无压缩容器，任何服务端都能解，不用现找编码器；
         * 转写场景下体积也不是瓶颈（16 kHz 单声道约 32 KB/秒）。
         */
        fun writeWav(target: File, pcm16: ShortArray, sampleRate: Int) {
            val dataBytes = pcm16.size * 2
            target.parentFile?.mkdirs()
            java.io.DataOutputStream(
                java.io.BufferedOutputStream(target.outputStream())
            ).use { out ->
                fun le32(v: Int) {
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                    out.write((v shr 16) and 0xFF)
                    out.write((v shr 24) and 0xFF)
                }
                fun le16(v: Int) {
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }

                out.write("RIFF".toByteArray(Charsets.US_ASCII))
                le32(36 + dataBytes)                     // ChunkSize
                out.write("WAVE".toByteArray(Charsets.US_ASCII))

                out.write("fmt ".toByteArray(Charsets.US_ASCII))
                le32(16)                                 // Subchunk1Size（PCM 固定 16）
                le16(1)                                  // AudioFormat：1 = PCM
                le16(1)                                  // NumChannels：单声道
                le32(sampleRate)
                le32(sampleRate * 2)                     // ByteRate = 采样率 × 声道 × 位深/8
                le16(2)                                  // BlockAlign
                le16(16)                                 // BitsPerSample

                out.write("data".toByteArray(Charsets.US_ASCII))
                le32(dataBytes)
                val buf = java.nio.ByteBuffer.allocate(dataBytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                pcm16.forEach { buf.putShort(it) }
                out.write(buf.array())
            }
        }
    }
}
