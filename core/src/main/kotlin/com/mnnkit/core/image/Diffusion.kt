package com.mnnkit.core.image

import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.flow.Flow

/** 文生图推理进度。 */
sealed interface DiffusionProgress {
    /** 文本编码阶段 */
    data object EncodingText : DiffusionProgress

    /** 去噪步进；[step]/[totalSteps] 用于进度条 */
    data class Denoising(val step: Int, val totalSteps: Int) : DiffusionProgress

    /** 解码 VAE 输出像素 */
    data object Decoding : DiffusionProgress

    /** 完成，[pngBytes] 为可直接写盘的 PNG 数据 */
    data class Done(val pngBytes: ByteArray, val width: Int, val height: Int) : DiffusionProgress {
        override fun equals(other: Any?): Boolean =
            other is Done && width == other.width && height == other.height && pngBytes.contentEquals(other.pngBytes)

        override fun hashCode(): Int = 31 * (31 * pngBytes.contentHashCode() + width) + height
    }

    data class Failed(val message: String) : DiffusionProgress
}

/** 文生图请求参数。 */
data class DiffusionRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.5f,
    val seed: Int = -1,
    /** 图生图时的初始图（PNG 字节）；纯文生图时为 null */
    val initImagePng: ByteArray? = null,
    val denoiseStrength: Float = 0.75f,
) {
    override fun equals(other: Any?): Boolean =
        other is DiffusionRequest && prompt == other.prompt && negativePrompt == other.negativePrompt &&
            width == other.width && height == other.height && steps == other.steps &&
            cfgScale == other.cfgScale && seed == other.seed && denoiseStrength == other.denoiseStrength &&
            (initImagePng?.contentEquals(other.initImagePng ?: ByteArray(0)) ?: (other.initImagePng == null))

    override fun hashCode(): Int = prompt.hashCode() * 31 + steps
}

/** 文生图引擎。 */
interface DiffusionEngine {

    val isAvailable: Boolean

    var loadedModel: ModelItem?

    suspend fun load(model: ModelItem)

    suspend fun unload()

    /** 执行一次生成，逐步汇报进度。 */
    fun generate(request: DiffusionRequest): Flow<DiffusionProgress>
}
