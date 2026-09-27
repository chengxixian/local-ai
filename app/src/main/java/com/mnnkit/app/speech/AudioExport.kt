package com.mnnkit.app.speech

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 PCM 音频编码成 mp3 / m4a。
 *
 * ## ⚠️ 先说清一个现实限制
 *
 * **Android 平台没有内置 mp3 编码器。**
 * `MediaCodec` 的 `audio/mpeg` 只在部分设备上作为**解码器**存在；
 * 编码侧官方从不保证 —— AOSP 只强制要求 AAC 编码器（`audio/mp4a-latm`）。
 *
 * 所以本文件的策略是：
 *
 *  1. [encodeToM4a] —— **可靠路径**。用系统 AAC 编码器 + [MediaMuxer] 出 `.m4a`。
 *     任何 Android 设备都能跑。
 *  2. [tryEncodeToMp3] —— **尽力而为**。先探测设备有没有 `audio/mpeg` 编码器；
 *     有就用（少数机型/ROM 提供），没有就返回 null，由调用方回退到 m4a。
 *
 * 不对用户撒谎说「一定给你 mp3」：拿不到 mp3 时导出的就是 m4a，
 * 并在提示里说明原因。硬凑一个改扩展名的「假 mp3」只会让文件在别的播放器里放不出来。
 *
 * ## 输入格式
 *
 * 统一按 **16-bit 单声道 PCM** 处理。Sherpa-MNN 的 TTS 输出就是
 * 16kHz float32 单声道，转成 16-bit 后正好。
 */
object AudioExport {

    /** 编码结果。`actualFormat` 会告诉用户实际拿到了什么。 */
    data class Result(
        val file: File,
        val actualFormat: String,
        val note: String?,
    )

    /**
     * 编码成 m4a（AAC）。**这是可靠路径**。
     *
     * @param pcm 16-bit 小端单声道 PCM
     * @param sampleRate 采样率（Sherpa TTS 一般是 16000）
     */
    fun encodeToM4a(pcm: ShortArray, sampleRate: Int, target: File): Result {
        val pcmBytes = shortsToBytes(pcm)
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            var offset = 0
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val chunk = minOf(inBuf.capacity(), pcmBytes.size - offset)
                        if (chunk <= 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            inBuf.put(pcmBytes, offset, chunk)
                            // 时间戳按已写入的字节数算，单位微秒
                            val ptsUs = offset.toLong() * 1_000_000L /
                                (sampleRate.toLong() * 2L)   // 2 字节/样本
                            codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                            offset += chunk
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            // 输入结束且没有更多输出，收工
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                        // 编码器配置帧（codec config）不能写进 muxer，由 addTrack 处理
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        return Result(target, "m4a", null)
    }

    /**
     * 尽力编码成 mp3。
     *
     * 成功返回 [Result]；设备没有 mp3 编码器时返回 **null**（调用方应回退到 m4a）。
     */
    fun tryEncodeToMp3(pcm: ShortArray, sampleRate: Int, target: File): Result? {
        val hasEncoder = runCatching {
            val codec = MediaCodec.createEncoderByType("audio/mpeg")
            codec.release()
            true
        }.getOrDefault(false)

        if (!hasEncoder) return null

        // 设备确实有 mp3 编码器，才走这条路径。
        // MediaMuxer 不支持 mp3 容器，所以这里手写 ADTS 帧头 —— 但
        // MediaCodec 的 audio/mpeg 输出通常**已经是** ADTS 流，可直接落盘。
        return runCatching {
            val pcmBytes = shortsToBytes(pcm)
            val format = MediaFormat.createAudioFormat("audio/mpeg", sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            }
            val codec = MediaCodec.createEncoderByType("audio/mpeg")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            target.outputStream().use { out ->
                var offset = 0
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            inBuf.clear()
                            val chunk = minOf(inBuf.capacity(), pcmBytes.size - offset)
                            if (chunk <= 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                inBuf.put(pcmBytes, offset, chunk)
                                codec.queueInputBuffer(inIndex, 0, chunk, 0, 0)
                                offset += chunk
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        val isConfig =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(data)
                            out.write(data)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            codec.stop()
            codec.release()
            Result(target, "mp3", null)
        }.getOrNull()
    }

    /**
     * 把 float32 的 PCM（[-1, 1]）转成 16-bit。
     *
     * Sherpa-MNN 的 TTS 产出 float 数组，[encodeToM4a] 要 16-bit。
     */
    fun floatsToShorts(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i ->
            val v = samples[i].coerceIn(-1f, 1f)
            (v * 32767f).toInt().toShort()
        }

    private fun shortsToBytes(pcm: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { buf.putShort(it) }
        return buf.array()
    }
}
