package com.mnnkit.core.chat

/** Split local-model output into a collapsible thought and the visible answer.
 * Parse the accumulated stream, not individual tokens: tags may span callbacks.
 * Qwen3 templates can prefill the opening <think> in the prompt, so the model
 * only emits its contents and a closing </think>.
 */
object LocalThinkingParser {
    data class Parts(val text: String, val reasoning: String?)

    fun split(raw: String, implicitOpen: Boolean = false): Parts {
        val thought = StringBuilder()
        val answer = StringBuilder()
        var thinking = implicitOpen
        var position = 0
        val open = "<think>"
        val close = "</think>"
        while (position < raw.length) {
            val marker = when {
                raw.startsWith(open, position) -> open
                raw.startsWith(close, position) -> close
                else -> null
            }
            if (marker != null) {
                thinking = marker == open
                position += marker.length
                continue
            }
            // Do not show a partially emitted marker in either bubble while streaming.
            if (raw[position] == '<') {
                val remaining = raw.length - position
                if ((remaining < open.length && raw.regionMatches(position, open, 0, remaining)) ||
                    (remaining < close.length && raw.regionMatches(position, close, 0, remaining))) break
            }
            (if (thinking) thought else answer).append(raw[position])
            position++
        }
        return Parts(answer.toString().trimStart(), thought.toString().takeIf { it.isNotBlank() })
    }
}
