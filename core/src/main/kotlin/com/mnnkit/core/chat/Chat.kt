package com.mnnkit.core.chat

/**
 * 对话消息。role 取值对齐 OpenAI / MNN 的约定。
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    /** 该条消息是否由记忆库注入（UI 上可折叠显示） */
    val fromMemory: Boolean = false,
) {
    enum class Role(val wire: String) {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool"),
        ;

        companion object {
            fun fromWire(w: String): Role =
                entries.firstOrNull { it.wire.equals(w, ignoreCase = true) } ?: USER
        }
    }
}

/**
 * 生成参数。MNN 的 LLM 引擎没有 setTemperature/topP 之类的 setter，
 * 全部通过 `set_config(JSON)` 传入，因此这里统一建模成一个可序列化的配置。
 */
data class GenerationConfig(
    val maxNewTokens: Int = 512,
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val minP: Double = 0.0,
    val repetitionPenalty: Double = 1.05,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val samplerType: String = "mixed",
) {
    /** 转成 MNN set_config 接受的 JSON 片段。 */
    fun toJsonMap(): Map<String, Any> = buildMap {
        put("max_new_tokens", maxNewTokens)
        put("temperature", temperature)
        put("topP", topP)
        put("topK", topK)
        put("min_p", minP)
        put("penalty", repetitionPenalty)
        put("sampler_type", samplerType)
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个运行在手机本地的 AI 助手。回答要准确、简洁，使用与用户相同的语言。"
    }
}

/** 生成过程中的状态回调。 */
interface ChatListener {
    /** 增量 token 文本 */
    fun onToken(text: String)

    /** 生成完成，返回完整文本 */
    fun onComplete(fullText: String)

    fun onError(error: Throwable)
}

/** 一次会话的句柄，用于支持"停止生成"。 */
interface ChatHandle {
    fun cancel()
}

/** 模型加载/推理统一异常。 */
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
