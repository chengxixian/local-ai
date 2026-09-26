package com.mnnkit.app.data

/**
 * 推理后端。
 *
 * ## 取值不是我编的
 *
 * MNN 的 LLM 引擎在 `transformers/llm/engine/src/llm.cpp` 里有一个
 * `backend_type_convert()`，它把配置里的 `backend_type` 字符串映射成 `MNNForwardType`：
 *
 * ```
 * "cpu"     -> MNN_FORWARD_CPU
 * "metal"   -> MNN_FORWARD_METAL      (仅 Apple)
 * "cuda"    -> MNN_FORWARD_CUDA       (仅 NVIDIA)
 * "opencl"  -> MNN_FORWARD_OPENCL
 * "opengl"  -> MNN_FORWARD_OPENGL
 * "vulkan"  -> MNN_FORWARD_VULKAN
 * "hexagon" -> MNN_FORWARD_HEXAGON    (仅高通 DSP)
 * "qnn"     -> MNN_FORWARD_QNN        (仅高通 NPU，需专用模型)
 * "npu"     -> MNN_FORWARD_NN         (通用 NPU，需专用模型)
 * 其它       -> MNN_FORWARD_AUTO
 * ```
 *
 * ## 为什么只暴露三种
 *
 * Android 手机上真正可用的只有 CPU / GPU / NPU 三类；
 * `metal` / `cuda` 不可能出现在 Android 上，`opengl` 已过时（官方也用 opencl）。
 *
 * ## ⚠️ 切到 GPU / NPU 之后的两个现实约束
 *
 * 1. **模型可能不支持。** NPU（`npu` / `qnn`）需要**专门转换过的模型**
 *    ——普通 `.mnn` 拿到 NPU 上会加载失败。MNN 自己的报错里就写了这一点：
 *    `wrong backend (e.g. NPU on CPU-only device)`。
 *    所以选 NPU 加载失败时，**先换回 CPU 确认模型本身没问题**。
 * 2. **OpenCL 需要设备支持。** 部分机型/驱动上 OpenCL 初始化会失败，
 *    失败后 MNN 会退回 CPU，性能反而不如直接用 CPU（多一次初始化开销）。
 *
 * @param id 传给 MNN 的字符串，必须与 `backend_type_convert()` 完全一致（小写）。
 */
enum class InferenceBackend(val id: String, val label: String, val note: String) {
    CPU(
        id = "cpu",
        label = "CPU",
        note = "兼容性最好，任何模型都能跑。速度最慢，但最稳 —— 排查问题时先用它。",
    ),
    OPENCL(
        id = "opencl",
        label = "GPU (OpenCL)",
        note = "部分模型可提速。需要设备驱动支持 OpenCL；初始化失败时会自动退回 CPU。",
    ),
    VULKAN(
        id = "vulkan",
        label = "GPU (Vulkan)",
        note = "另一条 GPU 路径。与 OpenCL 二选一即可，哪个快取决于机型驱动。",
    ),
    NPU(
        id = "npu",
        label = "NPU",
        note = "需要**专门为 NPU 转换过的模型**。普通 .mnn 会加载失败 —— " +
            "失败时先换回 CPU 确认模型本身可用。",
    ),
    ;

    companion object {
        /** 默认用 CPU：兼容性最好，也是排查问题的基准。 */
        val DEFAULT = CPU

        fun fromId(id: String?): InferenceBackend =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
