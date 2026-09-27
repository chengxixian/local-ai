package com.mnnkit.core.chat

import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.flow.Flow

/**
 * LLM 推理引擎抽象。
 *
 * 之所以抽出接口：MNN 原生库是运行时按需下载/编译产物，
 * 接口化后 UI 与业务层不依赖 native 是否就绪，也便于单元测试。
 */
interface LlmEngine {

    /** 引擎是否可用（原生库已加载）。 */
    val isAvailable: Boolean

    /** 当前已加载的模型；未加载时为 null。 */
    var loadedModel: ModelItem?

    /**
     * 加载模型。[model] 必须指向本地已就绪的模型目录。
     * 会释放先前加载的模型。
     */
    suspend fun load(model: ModelItem, config: GenerationConfig = GenerationConfig())

    /** 释放模型与显存/KV cache。 */
    suspend fun unload()

    /**
     * 流式生成。返回增量文本流，流结束即生成结束。
     * 调用方取消协程即停止生成。
     */
    fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String>

    /** 抽取文本 embedding；引擎不支持时返回 null。 */
    suspend fun embed(text: String): FloatArray?

    /** 重置会话上下文（清空 KV cache），用于开新话题。 */
    suspend fun resetContext()
}
