package com.mnnkit.core.chat

import kotlinx.coroutines.channels.SendChannel

/** Maps token delivery to the native callback contract: true means STOP. */
object TokenStreamCallback {
    /**
     * Cancellation wins even for the null end-of-generation sentinel. Otherwise
     * the sentinel is not emitted and does not itself request a stop.
     *
     * [receiver] must have unlimited capacity, as the engine's token flow does:
     * a failed non-suspending delivery must stop generation rather than drop text.
     */
    fun shouldStop(token: String?, cancelled: Boolean, receiver: SendChannel<String>): Boolean {
        if (cancelled) return true
        if (token == null) return false
        return receiver.trySend(token).isFailure
    }
}
