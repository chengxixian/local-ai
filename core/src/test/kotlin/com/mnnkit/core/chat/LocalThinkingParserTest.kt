package com.mnnkit.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalThinkingParserTest {
    @Test fun `explicit thought markers are removed from answer`() {
        val partial = LocalThinkingParser.split("<think>分析中</thi")
        assertEquals("分析中", partial.reasoning)
        assertEquals("", partial.text)
        val done = LocalThinkingParser.split("<think>分析中</think>\n最终答案")
        assertEquals("分析中", done.reasoning)
        assertEquals("最终答案", done.text)
    }

    @Test fun `prefilled Qwen thought begins without opening marker`() {
        val partial = LocalThinkingParser.split("先想一想</thi", implicitOpen = true)
        assertEquals("先想一想", partial.reasoning)
        assertEquals("", partial.text)
        val done = LocalThinkingParser.split("先想一想</think>\n答案", implicitOpen = true)
        assertEquals("先想一想", done.reasoning)
        assertEquals("答案", done.text)
    }

    @Test fun `plain local response stays visible`() {
        val plain = LocalThinkingParser.split("正常回复")
        assertEquals("正常回复", plain.text)
        assertNull(plain.reasoning)
        assertEquals("文本", LocalThinkingParser.split("文本<").text)
        assertEquals("文本<abc", LocalThinkingParser.split("文本<abc").text)
    }

    @Test fun `prefilled thought can finish without closing marker`() {
        val partial = LocalThinkingParser.split("尚在思考", implicitOpen = true)
        assertEquals("", partial.text)
        assertEquals("尚在思考", partial.reasoning)
    }
}
