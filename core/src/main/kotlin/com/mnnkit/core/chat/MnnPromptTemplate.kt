package com.mnnkit.core.chat

/**
 * 把对话渲染成 Qwen / ChatML 格式的 prompt。
 *
 * ## 为什么要在应用层自己渲染
 *
 * 真机实测（v0.5.0，Qwen3.5-0.8B-MNN）出现「本地模型只吐一个词」。
 * 诊断日志把病因钉死了 —— 把 `debugInfo()` 里**渲染后的 prompt 原文**打出来：
 *
 * ```
 * 你是一个运行在手机本地的 AI 助手。回答要准确、简洁，使用与用户相同的语言。Tell么about the ocean。
 * ```
 *
 * 也就是说：system prompt 和 user 消息被**直接裸拼**，一个 `<|im_start|>`
 * 都没有。这正是 MNN `Tokenizer::apply_chat_template` 在
 * `chat_template_` 为空时的降级行为 —— 它只是把各条消息的 `content` 串起来。
 *
 * 而模型 `llm_config.json` 里明明带着完整的 `jinja.chat_template`，
 * `enable_thinking` 开关也验证过（v0.5.0 已修好不再被误覆盖）。
 * 说明问题出在 MNN 那一侧的模板装载/渲染路径上，**不在我们的配置里**。
 * 继续往 MNN 内部追的性价比很低，而且那是个我们改不动的预编译 `libMNN.so`。
 *
 * 所以这里自己渲染：格式是公开且稳定的，渲染结果**可被单测断言**，
 * 不再依赖 `libMNN.so` 内部状态。
 *
 * ## 格式（Qwen 系 ChatML）
 *
 * ```
 * <|im_start|>system
 * {system}<|im_end|>
 * <|im_start|>user
 * {content}<|im_end|>
 * <|im_start|>assistant
 * <think>
 * ```
 *
 * 最后那个 `<think>` 是关键：Qwen3 系模板在开启思考时插入它。
 * 若不开思考，模板会插入一个**空** `<think>\n\n</think>` 块，而 Qwen3.5
 * 在空思考块之后经常直接输出 EOS（实测 `decode_len=1`）。
 * 这里始终插入 `<think>`，让模型真的开始思考。
 */
object MnnPromptTemplate {

    const val IM_START = "<|im_start|>"
    const val IM_END = "<|im_end|>"
    const val THINK_OPEN = "<think>"

    /**
     * 把消息列表渲染成单个 prompt 字符串。
     *
     * @param messages 角色 + 内容，按时间顺序。**第一条建议是 system**；
     *   没有 system 时也能工作（只是少了系统提示）。
     * @param enableThinking 为 true 时最后插入 `<think>`，让模型先思考。
     *   为 false 时**什么都不插**（不是插空思考块 —— 那正是要避开的坑）。
     */
    fun render(
        messages: List<Pair<String, String>>,
        enableThinking: Boolean = true,
    ): String {
        val sb = StringBuilder()
        for ((role, content) in messages) {
            if (content.isEmpty() && role != "assistant") continue
            sb.append(IM_START).append(role).append('\n')
            sb.append(content)
            sb.append(IM_END).append('\n')
        }
        // 生成提示：让模型接着 assistant 的角色说
        sb.append(IM_START).append("assistant").append('\n')
        if (enableThinking) {
            sb.append(THINK_OPEN)
        }
        return sb.toString()
    }
}
