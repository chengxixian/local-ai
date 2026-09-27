package com.mnnkit.app.image

import com.mnnkit.core.image.DiffusionEngine
import com.mnnkit.core.image.DiffusionProgress
import com.mnnkit.core.image.DiffusionRequest
import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 文生图引擎的降级实现。
 *
 * MNN 的 diffusion 原生接口需要额外的 JNI 包装（官方 App 的 diffusion_jni.cpp /
 * sana_jni.cpp 尚未移植），因此这里先给出明确的不可用语义，
 * UI 会显示"需要下载文生图模型并等待原生接口接入"，而不是静默失败。
 */
class UnavailableDiffusionEngine(
    private val reason: String = "文生图原生接口尚未接入",
) : DiffusionEngine {

    override val isAvailable: Boolean = false

    override var loadedModel: ModelItem? = null

    override suspend fun load(model: ModelItem) {
        throw UnsupportedOperationException(reason)
    }

    override suspend fun unload() {
        loadedModel = null
    }

    override fun generate(request: DiffusionRequest): Flow<DiffusionProgress> = flow {
        emit(DiffusionProgress.Failed(reason))
    }
}
