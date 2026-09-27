package com.mnnkit.core.chat

/** Measured token throughput, never inferred from characters or stream chunks. */
data class GenerationMetrics(
    val completionTokens: Long,
    val durationSeconds: Double,
    val source: Source,
) {
    enum class Source {
        /** Native decode_len / decode_time; excludes prompt prefill. */
        NATIVE_DECODE,
        /** API usage.completion_tokens / client request-to-completion wall time. */
        API_WALL_CLOCK,
    }

    init {
        require(isValid(completionTokens, durationSeconds)) { "Invalid generation metrics" }
    }

    val tokensPerSecond: Double get() = completionTokens.toDouble() / durationSeconds

    companion object {
        // The JSON codec uses Double numbers: keep counts exactly representable.
        const val MAX_EXACT_TOKENS: Long = 9_007_199_254_740_991L

        private fun isValid(tokens: Long, seconds: Double): Boolean =
            tokens in 0..MAX_EXACT_TOKENS && seconds.isFinite() && seconds > 0.0 &&
                (tokens.toDouble() / seconds).isFinite()

        fun create(completionTokens: Long, durationSeconds: Double, source: Source): GenerationMetrics? =
            if (isValid(completionTokens, durationSeconds)) {
                GenerationMetrics(completionTokens, durationSeconds, source)
            } else {
                null
            }

        fun fromNativeDecode(decodeTokens: Long, decodeTimeUs: Long): GenerationMetrics? =
            create(decodeTokens, decodeTimeUs / 1_000_000.0, Source.NATIVE_DECODE)

        fun fromApiUsage(completionTokens: Long, elapsedNanos: Long): GenerationMetrics? =
            create(completionTokens, elapsedNanos / 1_000_000_000.0, Source.API_WALL_CLOCK)
    }
}
