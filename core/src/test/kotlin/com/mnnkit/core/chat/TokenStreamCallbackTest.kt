package com.mnnkit.core.chat

import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenStreamCallbackTest {
    @Test
    fun `successful delivery returns false to continue native generation`() {
        val receiver = Channel<String>(Channel.UNLIMITED)
        try {
            assertFalse(TokenStreamCallback.shouldStop("first", false, receiver))
            assertEquals("first", receiver.tryReceive().getOrThrow())
        } finally {
            receiver.cancel()
        }
    }

    @Test
    fun `closed receiver returns true to stop native generation`() {
        val receiver = Channel<String>(Channel.UNLIMITED)
        receiver.close()
        assertTrue(TokenStreamCallback.shouldStop("undeliverable", false, receiver))
        assertTrue(receiver.tryReceive().isClosed)
    }

    @Test
    fun `cancelled receiver returns true to stop native generation`() {
        val receiver = Channel<String>(Channel.UNLIMITED)
        receiver.cancel()
        assertTrue(TokenStreamCallback.shouldStop("undeliverable", false, receiver))
        assertTrue(receiver.tryReceive().isClosed)
    }

    @Test
    fun `cancellation returns true without delivering a token`() {
        val receiver = Channel<String>(Channel.UNLIMITED)
        try {
            assertTrue(TokenStreamCallback.shouldStop("not emitted", true, receiver))
            assertTrue(receiver.tryReceive().isFailure)
        } finally {
            receiver.cancel()
        }
    }

    @Test
    fun `null sentinel returns false and is not delivered`() {
        val receiver = Channel<String>(Channel.UNLIMITED)
        try {
            assertFalse(TokenStreamCallback.shouldStop(null, false, receiver))
            assertTrue(receiver.tryReceive().isFailure)
        } finally {
            receiver.cancel()
        }
    }

    @Test
    fun `cancellation takes precedence over null sentinel`() {
        val receiver = Channel<String>(Channel.UNLIMITED)
        try {
            assertTrue(TokenStreamCallback.shouldStop(null, true, receiver))
            assertTrue(receiver.tryReceive().isFailure)
        } finally {
            receiver.cancel()
        }
    }

    @Test
    fun `native loop delivers more than one chunk before sentinel`() {
        val receiver = Channel<String>(Channel.UNLIMITED)
        val chunks = listOf("Hello", " ", "world", "!")
        var callbacks = 0
        try {
            // Model JNI's loop: a true callback result stops decoding immediately.
            for (token in chunks + listOf<String?>(null)) {
                callbacks++
                if (TokenStreamCallback.shouldStop(token, false, receiver)) break
            }
            assertEquals(chunks.size + 1, callbacks)
            val delivered = buildList {
                while (true) {
                    val result = receiver.tryReceive()
                    if (result.isFailure) break
                    add(result.getOrThrow())
                }
            }
            assertEquals(chunks, delivered)
            assertEquals("Hello world!", delivered.joinToString(""))
        } finally {
            receiver.cancel()
        }
    }
}
