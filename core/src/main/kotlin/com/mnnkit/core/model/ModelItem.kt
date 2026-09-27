package com.mnnkit.core.model

/**
 * 模型用途分类。端侧模型在本应用中被归为四类，
 * LLM 可与 STT/TTS 组合成语音闭环，也可各自独立使用。
 */
enum class ModelKind(val id: String, val label: String) {
    LLM("llm", "大语言模型"),
    STT("stt", "语音识别"),
    TTS("tts", "语音合成"),
    DIFFUSION("diffusion", "文生图");

    companion object {
        fun fromId(id: String?): ModelKind? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** 模型可用性状态（相对本机磁盘而言）。 */
enum class ModelStatus {
    /** 未下载，仅有远程元数据 */
    REMOTE,
    /** 正在下载 / 转换 */
    INSTALLING,
    /** 已就绪可加载 */
    READY,
    /** 安装或转换失败 */
    FAILED,
}

/**
 * 模型文件来源。一个模型目录通常由多个文件组成
 * （如 llm.mnn + llm.mnn.weight + tokenizer.txt + config.json）。
 */
data class ModelFile(
    val path: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
)

/** 远程模型仓库（如 ModelScope、HuggingFace 镜像）。 */
enum class ModelSource(val id: String, val label: String) {
    MODELSCOPE("modelscope", "ModelScope 魔搭"),
    HF_MIRROR("hf-mirror", "HuggingFace 镜像"),
    HUGGINGFACE("huggingface", "HuggingFace"),
    LOCAL("local", "本地导入"),
    ;

    companion object {
        fun fromId(id: String?): ModelSource =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MODELSCOPE
    }
}

/** 量化/精度标识，用于在 UI 上提示显存与体积。 */
enum class QuantType(val id: String, val label: String) {
    INT4("int4", "4bit 量化"),
    INT8("int8", "8bit 量化"),
    FP16("fp16", "FP16"),
    FP32("fp32", "FP32"),
    UNKNOWN("unknown", "未知"),
    ;

    companion object {
        fun fromId(id: String?): QuantType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * 模型权重的原始格式。
 *
 * 这决定了它能否被本机直接加载：
 *  - [MNN] 是本应用唯一能直接推理的格式；
 *  - 其余格式必须先转换为 .mnn 才能运行（见 [needsConversion]）。
 */
enum class ModelFormat(val id: String, val label: String, val badge: String) {
    MNN("mnn", "MNN", "可直接运行"),
    SAFETENSORS("safetensors", "PyTorch / safetensors", "需转换"),
    GGUF("gguf", "GGUF (llama.cpp)", "需转换"),
    ONNX("onnx", "ONNX", "需转换"),
    BIN("bin", "PyTorch bin", "需转换"),
    UNKNOWN("unknown", "未知格式", "格式待确认"),
    ;

    /** 是否需要先转换成 .mnn 才能在本机运行。 */
    val needsConversion: Boolean get() = this != MNN

    companion object {
        fun fromId(id: String?): ModelFormat =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UNKNOWN

        /** 从仓库里出现过的文件名推断格式。 */
        fun detectFromFileNames(names: Collection<String>): ModelFormat {
            val lowered = names.map { it.lowercase() }
            return when {
                lowered.any { it.endsWith(".mnn") || it.endsWith(".mnn.weight") } -> MNN
                lowered.any { it.endsWith(".gguf") } -> GGUF
                lowered.any { it.endsWith(".safetensors") } -> SAFETENSORS
                lowered.any { it.endsWith(".onnx") } -> ONNX
                lowered.any { it.endsWith(".bin") || it.endsWith(".pth") || it.endsWith(".pt") } -> BIN
                else -> UNKNOWN
            }
        }

        /** 从仓库的 framework 字段推断（魔搭会返回 pytorch / MNN / onnx 等）。 */
        fun detectFromFrameworks(frameworks: Collection<String>): ModelFormat? {
            val lowered = frameworks.map { it.lowercase() }
            return when {
                lowered.any { it == "mnn" } -> MNN
                lowered.any { it == "onnx" } -> ONNX
                lowered.any { it == "gguf" || it == "llama.cpp" } -> GGUF
                lowered.any { it == "pytorch" || it == "pytorch" } -> null // 需看文件才能区分 safetensors/bin
                else -> null
            }
        }
    }
}

/**
 * 一个可安装的模型条目。既用于远程市场，也用于本地已安装模型。
 *
 * [kind] 决定它在哪个界面出现、以及在语音闭环里扮演什么角色。
 */
data class ModelItem(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val source: ModelSource,
    /** 仓库内路径，例如 "MNN/Qwen3-0.6B-MNN" */
    val repoId: String,
    val revision: String = "master",
    val vendor: String = "",
    val description: String = "",
    val sizeBytes: Long = 0L,
    val quant: QuantType = QuantType.UNKNOWN,
    val tags: List<String> = emptyList(),
    /** 需要的文件清单；为空时表示运行时动态枚举 */
    val files: List<ModelFile> = emptyList(),
    val status: ModelStatus = ModelStatus.REMOTE,
    /** 本地安装目录绝对路径（status == READY 时有效） */
    val localPath: String? = null,
    /** 转换来源信息（当该模型由用户链接转换而来时非空） */
    val convertedFrom: String? = null,
    /** 下载进度 0..1；仅在 status == INSTALLING 时有意义 */
    val progress: Float = 0f,
    /** 出错信息；仅在 status == FAILED 时有意义 */
    val error: String? = null,
    /**
     * 同一模型在其它下载源上的仓库 id，键为 [ModelSource.id]。
     * 用于让用户在商店里切换从 HuggingFace 还是魔搭下载同一个模型。
     */
    val altRepos: Map<String, String> = emptyMap(),
    /** [sizeBytes] 是否来自权威字段；false 表示是按 size_gb 估算的 */
    val sizeIsExact: Boolean = false,
    /** 权重原始格式；决定能否直接运行还是要先转换 */
    val format: ModelFormat = ModelFormat.MNN,
    /** 转换来源（原始模型仓库）；仅当 [format] 不是 MNN 时有意义 */
    val convertFromRepo: String? = null,
) {
    /** 体积的友好展示，例如 "451 MB" / "4.2 GB" */
    val sizeLabel: String
        get() = when {
            sizeBytes > 0L && sizeIsExact -> formatSize(sizeBytes)
            sizeBytes > 0L -> "约 " + formatSize(sizeBytes)
            // 0 或未知：给一个中性提示，不要写成"未知"这种像错误的值
            format == ModelFormat.MNN -> "大小待获取"
            else -> "大小待获取"
        }

    /** 该模型可用哪些下载源（含主源）。 */
    val availableSources: List<ModelSource>
        get() = buildList {
            add(source)
            altRepos.keys.mapNotNull { id -> ModelSource.entries.firstOrNull { it.id == id } }
                .forEach { if (it !in this) add(it) }
        }.distinct()

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            val b = bytes.toDouble()
            return when {
                b >= gb -> String.format("%.2f GB", b / gb)
                b >= mb -> String.format("%.0f MB", b / mb)
                b >= kb -> String.format("%.0f KB", b / kb)
                else -> "$bytes B"
            }
        }
    }
}
