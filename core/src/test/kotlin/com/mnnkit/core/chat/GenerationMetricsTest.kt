package com.mnnkit.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GenerationMetricsTest {
    @Test fun `native microseconds give measured decode rate`() {
        val metrics = assertNotNull(GenerationMetrics.fromNativeDecode(120, 2_000_000))
        assertEquals(60.0, metrics.tokensPerSecond)
        assertEquals(2.0, metrics.durationSeconds)
        assertEquals(GenerationMetrics.Source.NATIVE_DECODE, metrics.source)
    }

    @Test fun `API uses reported usage and elapsed nanos`() {
        val metrics = assertNotNull(GenerationMetrics.fromApiUsage(35, 2_000_000_000))
        assertEquals(17.5, metrics.tokensPerSecond)
        assertEquals(GenerationMetrics.Source.API_WALL_CLOCK, metrics.source)
        assertEquals(0.0, assertNotNull(GenerationMetrics.fromApiUsage(0, 1)).tokensPerSecond)
    }

    @Test fun `invalid or unmeasured durations never create a rate`() {
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertNull(GenerationMetrics.fromNativeDecode(5, duration))
            assertNull(GenerationMetrics.fromApiUsage(5, duration))
        }
        assertNull(GenerationMetrics.fromNativeDecode(-1, 100))
        assertNull(GenerationMetrics.fromNativeDecode(Long.MAX_VALUE, 100))
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE)) {
            assertNull(GenerationMetrics.create(100, seconds, GenerationMetrics.Source.NATIVE_DECODE))
        }
    }

    @Test fun `constructor and copy cannot bypass validation`() {
        assertFailsWith<IllegalArgumentException> {
            GenerationMetrics(1, Double.NaN, GenerationMetrics.Source.NATIVE_DECODE)
        }
        val valid = assertNotNull(GenerationMetrics.fromNativeDecode(1, 1))
        assertFailsWith<IllegalArgumentException> { valid.copy(durationSeconds = 0.0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(completionTokens = -1) }
    }
}
