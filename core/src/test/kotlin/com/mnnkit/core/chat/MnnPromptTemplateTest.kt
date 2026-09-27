package com.mnnkit.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MnnPromptTemplate] 的测试。
 *
 * 这是「本地模型只吐一个词」的回归测试。真机诊断日志证明：出问题时
 * 渲染后的 prompt 是**裸拼**的（system prompt 紧跟着 user 消息，
 * 没有任何 `<|im_start|>` 标记），模型收到不成形输入 → 第一个 token 就 EOS。
 *
 * 下面这些断言把「渲染结果必须是完整 ChatML」这件事钉住：
 * 只要有人把渲染退回原样返回，测试立刻红。
 */
class MnnPromptTemplateTest {

    private val system = "你是一个助手。"

    @Test
    fun `renders system and user with chatml markers`() {
        val out = MnnPromptTemplate.render(
            listOf("system" to system, "user" to "你好"),
            enableThinking = false,
        )
        assertTrue(out.startsWith("<|im_start|>system\n"), "必须以 system 段开头，实际：$out")
        assertTrue(out.contains("$system<|im_end|>\n"), "system 段必须以 <|im_end|> 收尾")
        assertTrue(out.contains("<|im_start|>user\n你好<|im_end|>\n"), "user 段格式不对")
        assertTrue(out.endsWith("<|im_start|>assistant\n"), "必须以 assistant 段结尾，实际：$out")
    }

    @Test
    fun `never produces bare concatenation`() {
        // 这是本次问题的核心断言：
        // 裸拼的结果里 system 内容会**直接紧贴** user 内容。
        val out = MnnPromptTemplate.render(
            listOf("system" to system, "user" to "你好"),
            enableThinking = false,
        )
        assertFalse(
            out.contains("${system}你"),
            "出现裸拼（system 内容直接紧跟 user 内容），说明模板没渲染：$out",
        )
        assertTrue(out.contains("<|im_start|>"), "完全没有 ChatML 标记")
    }

    @Test
    fun `thinking mode appends open think tag`() {
        val out = MnnPromptTemplate.render(
            listOf("system" to system, "user" to "hi"),
            enableThinking = true,
        )
        assertTrue(out.endsWith("<|im_start|>assistant\n<think>"), "开启思考时应以 <think> 结尾，实际：$out")
    }

    @Test
    fun `non thinking mode must not insert empty think block`() {
        // ⚠️ 这条是防止"退回旧方案"的护栏：
        // 曾经为了关思考而插入空 `<think>\n\n</think>` 块，
        // 结果 Qwen3.5 在空思考块之后直接吐 EOS（真机 decode_len=1）。
        val out = MnnPromptTemplate.render(
            listOf("system" to system, "user" to "hi"),
            enableThinking = false,
        )
        assertFalse(out.contains("<think>"), "关闭思考时不该出现任何 <think>，实际：$out")
        assertFalse(out.contains("</think>"), "关闭思考时不该出现 </think>")
    }

    @Test
    fun `renders multi turn history in order`() {
        val out = MnnPromptTemplate.render(
            listOf(
                "system" to system,
                "user" to "第一问",
                "assistant" to "第一答",
                "user" to "第二问",
            ),
            enableThinking = false,
        )
        val iUser1 = out.indexOf("第一问")
        val iAsst = out.indexOf("第一答")
        val iUser2 = out.indexOf("第二问")
        assertTrue(iUser1 in 1 until iAsst, "顺序错：user1=$iUser1 assistant=$iAsst")
        assertTrue(iAsst < iUser2, "顺序错：assistant=$iAsst user2=$iUser2")
        // 每一轮都要有各自的 role 头：4 条消息 + 最后的 assistant 生成提示 = 5 个
        assertEquals(5, Regex("<\\|im_start\\|>").findAll(out).count(), "role 头数量不对：$out")
    }

    @Test
    fun `skips empty non assistant messages`() {
        val out = MnnPromptTemplate.render(
            listOf("system" to system, "user" to "", "user" to "有效"),
            enableThinking = false,
        )
        assertTrue(out.contains("有效"))
        // 空 user 不该产生一个空的 user 段
        assertEquals(1, Regex("<\\|im_start\\|>user").findAll(out).count())
    }

    @Test
    fun `works without a system message`() {
        val out = MnnPromptTemplate.render(listOf("user" to "只有用户"), enableThinking = false)
        assertTrue(out.startsWith("<|im_start|>user\n"))
        assertTrue(out.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `handles empty message list`() {
        val out = MnnPromptTemplate.render(emptyList(), enableThinking = false)
        assertEquals("<|im_start|>assistant\n", out)
    }

    @Test
    fun `produces a prompt clearly longer than bare concatenation`() {
        // 侧面护栏：加上标记后长度一定比裸拼长。
        // 真机上"prompt_len 只有 36"就是裸拼的信号之一。
        val msgs = listOf("system" to system, "user" to "Tell me about the ocean.")
        val rendered = MnnPromptTemplate.render(msgs, enableThinking = true)
        val bare = msgs.joinToString("") { it.second }
        assertTrue(
            rendered.length > bare.length + 40,
            "渲染结果(${rendered.length}) 相对裸拼(${bare.length}) 太短，可能没加标记",
        )
    }
}
