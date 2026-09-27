package com.mnnkit.app.llm

import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.chat.LlmEngine
import com.mnnkit.core.chat.InferenceException
import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LLM 引擎的降级实现：原生库不可用时使用。
 *
 * 它的存在让 UI 与业务层永远不需要判空 —— 无论 native 是否就绪，
 * 应用都能启动、都能浏览与下载模型，只是生成时会给出明确的错误提示。
 * 真正的实现在 [MnnLlmEngine]。
 */
class UnavailableLlmEngine(
    private val reason: String = "MNN 原生库未加载",
) : LlmEngine {

    override val isAvailable: Boolean = false

    override var loadedModel: ModelItem? = null

    override suspend fun load(model: ModelItem, config: GenerationConfig) {
        throw InferenceException("$reason，无法加载模型。请确认已安装 arm64-v8a 设备可用的 MNN 推理库。")
    }

    override suspend fun unload() {
        loadedModel = null
    }

    override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flow {
        throw InferenceException("$reason，无法进行推理。")
    }

    override suspend fun embed(text: String): FloatArray? = null

    override suspend fun resetContext() = Unit
}
