package com.mnnkit.app.speech

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 语音相关的**纯逻辑**工具。
 *
 * 刻意不引用任何 Android / native 符号，因此可以直接在 JVM 单元测试里验证
 * （见 `app/src/test/java/com/mnnkit/app/speech/`）。
 */
object SpeechAudio {

    /**
     * sherpa-mnn 的声学前端按 16kHz 训练：`FeatureConfig(sampleRate = 16000)`，
     * 传入音频必须重采样到该采样率（详见 docs/impl-speech.md）。
     */
    const val SHERPA_SAMPLE_RATE = 16_000

    /** 长文本合成时单个分句的目标长度（字符数）。 */
    const val DEFAULT_TTS_CHUNK_CHARS = 60

    // ------------------------------------------------------------------
    // 重采样
    // ------------------------------------------------------------------

    /**
     * 线性插值重采样（单声道 float，取值 [-1, 1]）。
     *
     * [fromRate] == [toRate] 时直接返回原数组（不复制）。
     * 输出长度 = round(in.size * toRate / fromRate)，边界样本做了钳制，不会越界。
     */
    fun resampleLinear(pcm: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "采样率必须为正：$fromRate -> $toRate" }
        if (pcm.isEmpty() || fromRate == toRate) return pcm

        val outLen = ((pcm.size.toLong() * toRate.toLong()) / fromRate.toLong())
            .toInt()
            .coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = fromRate.toDouble() / toRate.toDouble()
        val last = pcm.size - 1

        for (i in 0 until outLen) {
            val src = i * step
            val i0 = src.toInt()
            val frac = (src - i0).toFloat()
            val a = pcm[min(i0, last)]
            val b = pcm[min(i0 + 1, last)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    // ------------------------------------------------------------------
    // 定点/浮点互转
    // ------------------------------------------------------------------

    /** float [-1,1] → 16bit PCM。 */
    fun floatToPcm16(pcm: FloatArray): ShortArray {
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        }
        return out
    }

    /** 16bit PCM → float [-1,1]。 */
    fun pcm16ToFloat(pcm: ShortArray, length: Int = pcm.size): FloatArray {
        val n = length.coerceIn(0, pcm.size)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = pcm[i] / 32768f
        }
        return out
    }

    /** 均方根，用于判断某段音频是否"基本无声"。 */
    fun rootMeanSquare(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (v in pcm) sum += v.toDouble() * v.toDouble()
        return sqrt(sum / pcm.size).toFloat()
    }

    /** 生成一段静音（用于分句拼接间隔）。 */
    fun silence(millis: Int, sampleRate: Int): FloatArray {
        if (millis <= 0 || sampleRate <= 0) return FloatArray(0)
        return FloatArray((millis.toLong() * sampleRate / 1000L).toInt())
    }

    /**
     * 按顺序拼接多段 PCM，段间插入 [gapSamples] 个静音样本。
     * 全部为空时返回空数组。
     */
    fun concat(parts: List<FloatArray>, gapSamples: Int = 0): FloatArray {
        val nonEmpty = parts.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return FloatArray(0)
        val gap = gapSamples.coerceAtLeast(0)
        val total = nonEmpty.sumOf { it.size } + gap * (nonEmpty.size - 1)
        val out = FloatArray(total)
        var pos = 0
        nonEmpty.forEachIndexed { index, part ->
            if (index > 0) pos += gap
            System.arraycopy(part, 0, out, pos, part.size)
            pos += part.size
        }
        return out
    }

    // ------------------------------------------------------------------
    // 分句（长文本 TTS 必须先切句，否则单次推理过长会失败或爆内存）
    // ------------------------------------------------------------------

    /** 硬断句标点：命中即断句（按需求：`。！？.!?\n`）。 */
    private const val HARD_TERMINATORS = "。！？!?…\n"

    /** 软断句标点：仅在"某句太长必须硬切"时作为优先切点。 */
    private const val SOFT_BREAKS = "，,、；;：: "

    fun isHardTerminator(c: Char): Boolean = c in HARD_TERMINATORS

    fun isSoftBreak(c: Char): Boolean = c in SOFT_BREAKS

    /**
     * 只按硬标点切句，不做长度控制、不做合并。
     * 连续标点（如 `什么？！`）会归到同一句；空白/空句被丢弃。
     */
    fun sentenceBoundaries(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c == '\r') continue
            sb.append(c)
            if (isHardTerminator(c)) {
                sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                sb.setLength(0)
            }
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    /**
     * 把长文本切成适合逐句送入 TTS 的块：
     *  1. 先按硬标点断句；
     *  2. 超长句在软标点处硬切（没有软标点则按 [maxChars] 直接切）；
     *  3. 相邻短句合并，直到接近 [maxChars]，以减少 native 调用次数。
     *
     * 保证：任何返回块的长度 <= [maxChars]（除非原文里存在单个超长"标点串"），
     * 且所有块拼接后（去掉空白）与原文一致。
     */
    fun chunkForTts(text: String, maxChars: Int = DEFAULT_TTS_CHUNK_CHARS): List<String> {
        require(maxChars >= 1) { "maxChars 必须 >= 1" }
        val pieces = sentenceBoundaries(text).flatMap { hardSplit(it, maxChars) }
        if (pieces.isEmpty()) return emptyList()

        val merged = ArrayList<String>(pieces.size)
        for (piece in pieces) {
            val last = merged.lastOrNull()
            if (last != null && last.length + piece.length <= maxChars) {
                merged[merged.size - 1] = last + piece
            } else {
                merged.add(piece)
            }
        }
        return merged
    }

    private fun hardSplit(piece: String, maxChars: Int): List<String> {
        if (piece.length <= maxChars) return listOf(piece)
        val out = ArrayList<String>()
        var start = 0
        while (start < piece.length) {
            val hardEnd = min(start + maxChars, piece.length)
            if (hardEnd >= piece.length) {
                out.add(piece.substring(start))
                break
            }
            var cut = -1
            for (i in hardEnd - 1 downTo start + 1) {
                if (isSoftBreak(piece[i])) {
                    cut = i + 1
                    break
                }
            }
            val end = if (cut > start) cut else hardEnd
            out.add(piece.substring(start, end))
            start = end
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
