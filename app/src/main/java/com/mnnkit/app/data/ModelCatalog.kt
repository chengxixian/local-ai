package com.mnnkit.app.data

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.model.ModelFormat
import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.model.ModelSource
import com.mnnkit.core.model.QuantType
import com.mnnkit.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模型商店的数据聚合层。
 *
 * 三个数据源，最终都归一化成 [ModelItem]：
 *
 *  1. **MNN 官方模型市场**（`meta.alicdn.com/data/mnn/apis/model_market.json`）
 *     —— 官方 App 用的同一份清单，按 `llm_models` / `tts_models` / `asr_models` 分类，
 *     每条带 `sources{HuggingFace, ModelScope, Modelers}` 多源映射。这是主数据源。
 *
 *  2. **HuggingFace `taobao-mnn` 组织**（`huggingface.co/api/models?author=taobao-mnn`）
 *     —— 100+ 个 MNN 格式模型，直接列出全部；HF 不可达时自动走 hf-mirror。
 *
 *  3. **内置兜底条目** —— 官方清单与 HF 都拉不到时的最小可用集合。
 *
 * 所有模型都是**运行时下载**，不随 APK 打包。
 */
class ModelCatalog(
    private val http: Http,
    private val cacheDir: File,
    private val marketUrls: List<String> = DEFAULT_MARKET_URLS,
    private val hfEndpoints: List<String> = DEFAULT_HF_ENDPOINTS,
) {

    /** 商店里可切换的来源。 */
    enum class StoreSource(val id: String, val label: String) {
        OFFICIAL("official", "MNN 官方精选"),
        HUGGINGFACE("huggingface", "HuggingFace"),
        MODELSCOPE("modelscope", "魔搭 ModelScope"),
        ;

        companion object {
            fun fromId(id: String?): StoreSource =
                entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OFFICIAL
        }
    }

    data class CatalogResult(
        val items: List<ModelItem>,
        val source: StoreSource,
        /** 是否来自网络（false 表示磁盘缓存或内置兜底） */
        val fromNetwork: Boolean,
        val note: String? = null,
    )

    private val memoryCache = HashMap<StoreSource, List<ModelItem>>()

    /** 读取某个来源的模型列表。 */
    suspend fun load(source: StoreSource, forceRefresh: Boolean = false): CatalogResult =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                memoryCache[source]?.let {
                    return@withContext CatalogResult(it, source, fromNetwork = false, note = "内存缓存")
                }
                diskCache(source)?.let {
                    memoryCache[source] = it
                    return@withContext CatalogResult(it, source, fromNetwork = false, note = "磁盘缓存")
                }
            }

            try {
                val items = when (source) {
                    StoreSource.OFFICIAL -> fetchOfficialMarket()
                    StoreSource.HUGGINGFACE -> fetchFromHuggingFace()
                    StoreSource.MODELSCOPE -> fetchFromModelScope()
                }
                if (items.isNotEmpty()) {
                    memoryCache[source] = items
                    writeDiskCache(source, items)
                    CatalogResult(items, source, fromNetwork = true)
                } else {
                    fallback(source, "该来源暂无模型")
                }
            } catch (e: Exception) {
                fallback(source, e.message ?: "加载失败")
            }
        }

    private fun fallback(source: StoreSource, note: String): CatalogResult {
        // 官方来源失败时，先给磁盘缓存，再给内置条目
        diskCache(source)?.let {
            memoryCache[source] = it
            return CatalogResult(it, source, fromNetwork = false, note = "$note（已用缓存）")
        }
        val items = when (source) {
            StoreSource.HUGGINGFACE -> FALLBACK_HF
            else -> FALLBACK
        }
        return CatalogResult(items, source, fromNetwork = false, note = "$note（已用内置列表）")
    }

    // ------------------------------------------------------------------
    // 来源 1：官方模型市场
    // ------------------------------------------------------------------

    private fun fetchOfficialMarket(): List<ModelItem> {
        var lastError: Exception? = null
        for (url in marketUrls) {
            try {
                val text = http.getText(url, timeoutMs = 30_000)
                val json = JsonParser.parseOrNull(text) ?: continue
                val items = parseOfficialMarket(json)
                if (items.isNotEmpty()) return items
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("官方模型清单拉取失败")
    }

    /**
     * 解析官方模型市场清单。
     *
     * 实测的真实结构（`meta.alicdn.com/data/mnn/apis/model_market.json`，71 KB）：
     * ```
     * {
     *   "version": "6",
     *   "tagTranslations": {...},
     *   "quickFilterTags": [8 项],
     *   "vendorOrder": [19 项],
     *   "models":     [130 项],   // ← LLM（含文生图，靠 ImageGen 标签区分）
     *   "tts_models": [2 项],
     *   "asr_models": [2 项],
     *   "libs":       [1 项]
     * }
     * ```
     * 注意键名是 **`models`** 而不是 `llm_models` —— 早期的错误假设会让 LLM 列表恒为空。
     */
    private fun parseOfficialMarket(root: Json): List<ModelItem> {
        val out = ArrayList<ModelItem>()

        // LLM（含文生图）
        root.arr("models")?.forEach { node ->
            val tags = node.arr("tags")?.mapNotNull { it.asString } ?: emptyList()
            val kind = if (tags.any { it.equals("ImageGen", true) }) {
                ModelKind.DIFFUSION
            } else {
                ModelKind.LLM
            }
            buildFromOfficial(node, kind)?.let { out.add(it) }
        }

        root.arr("tts_models")?.forEach { node ->
            buildFromOfficial(node, ModelKind.TTS)?.let { out.add(it) }
        }

        root.arr("asr_models")?.forEach { node ->
            buildFromOfficial(node, ModelKind.STT)?.let { out.add(it) }
        }

        // libs 里是 QNN 运行时之类，不是模型；跳过
        return out.distinctBy { it.repoId }
    }

    /** 官方清单里的标签翻译表，用于把英文标签显示成中文。 */
    fun tagTranslations(root: Json): Map<String, String> =
        root.path("tagTranslations")?.asObject?.mapValues { it.value.asString.orEmpty() } ?: emptyMap()

    private fun buildFromOfficial(node: Json, kind: ModelKind): ModelItem? {
        val name = node.str("modelName")?.takeIf { it.isNotBlank() } ?: return null
        val sources = node.path("sources") ?: return null

        // 每个模型保留全部可用来源，供 UI 让用户切换（HF / 魔搭）
        val repoMs = sources.str("ModelScope")?.takeIf { it.isNotBlank() }
        val repoHf = sources.str("HuggingFace")?.takeIf { it.isNotBlank() }
        val repoModelers = sources.str("Modelers")?.takeIf { it.isNotBlank() }

        val primaryRepo = repoModelers ?: repoMs ?: repoHf ?: return null
        val primarySource = when {
            repoModelers != null -> ModelSource.MODELSCOPE
            repoMs != null -> ModelSource.MODELSCOPE
            else -> ModelSource.HUGGINGFACE
        }

        val tags = node.arr("tags")?.mapNotNull { it.asString } ?: emptyList()
        val categories = node.arr("categories")?.mapNotNull { it.asString } ?: emptyList()
        val fileSize = node.long("file_size")
        val sizeGb = node.path("size_gb")?.asDouble ?: 0.0

        return ModelItem(
            id = "official/${kind.id}/$name",
            displayName = name,
            kind = kind,
            source = primarySource,
            repoId = primaryRepo,
            vendor = node.str("vendor").orEmpty(),
            description = buildDescription(kind, tags, categories),
            sizeBytes = fileSize ?: (sizeGb * 1024 * 1024 * 1024).toLong(),
            quant = inferQuant(name),
            tags = (tags + categories).distinct(),
            // 附带其它来源，UI 可让用户切换下载源
            altRepos = buildMap {
                repoMs?.let { put(ModelSource.MODELSCOPE.id, it) }
                repoHf?.let { put(ModelSource.HUGGINGFACE.id, it) }
            },
            sizeIsExact = fileSize != null,
            // 官方模型市场里的条目都有 llm.mnn，是可直接运行的 MNN 格式
            format = ModelFormat.MNN,
        )
    }

    // ------------------------------------------------------------------
    // 来源 2：HuggingFace taobao-mnn 组织
    // ------------------------------------------------------------------

    private fun fetchFromHuggingFace(): List<ModelItem> {
        var lastError: Exception? = null
        for (endpoint in hfEndpoints) {
            try {
                val url = "$endpoint/api/models?author=$HF_AUTHOR&limit=1000&full=false"
                val text = http.getText(url, timeoutMs = 40_000)
                val arr = JsonParser.parseOrNull(text)?.asArray ?: continue
                val items = arr.mapNotNull { buildFromHf(it) }
                if (items.isNotEmpty()) return items
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("HuggingFace 拉取失败")
    }

    private fun buildFromHf(node: Json): ModelItem? {
        val id = node.str("id") ?: return null
        if (!id.startsWith("$HF_AUTHOR/")) return null
        val shortName = id.substringAfter('/')

        val tags = node.arr("tags")?.mapNotNull { it.asString } ?: emptyList()
        val downloads = node.long("downloads") ?: 0L
        val likes = node.long("likes") ?: 0L

        val kind = when {
            tags.any { it.contains("speech-recognition", true) || it.contains("asr", true) } -> ModelKind.STT
            tags.any { it.contains("text-to-speech", true) || it.contains("tts", true) } -> ModelKind.TTS
            tags.any { it.contains("text-to-image", true) || it.contains("imagegen", true) } -> ModelKind.DIFFUSION
            else -> ModelKind.LLM
        }

        return ModelItem(
            id = "hf/${kind.id}/$shortName",
            displayName = shortName,
            kind = kind,
            source = ModelSource.HUGGINGFACE,
            repoId = id,
            vendor = inferVendor(shortName),
            description = buildString {
                append("HuggingFace 上的 MNN 格式模型")
                if (downloads > 0) append("（下载量 $downloads）")
            },
            // HF 列表接口不含体积，留在用户展开时再查文件清单
            sizeBytes = 0L,
            quant = inferQuant(shortName),
            tags = (tags.filter { it.length < 24 }.take(6) + listOf("HF", "下载 $downloads", "赞 $likes")),
            altRepos = mapOf(ModelSource.HF_MIRROR.id to id),
            sizeIsExact = false,
            // taobao-mnn 组织下的都是官方导出的 MNN 格式
            format = ModelFormat.MNN,
        )
    }

    // ------------------------------------------------------------------
    // 来源 3：魔搭 ModelScope 搜索
    // ------------------------------------------------------------------

    /**
     * 用 ModelScope 搜索接口列出模型。
     *
     * 注意：ModelScope **没有**公开的"按组织列模型"接口，
     * 只能用 PUT 的搜索接口（GET 会 404）。
     *
     * 这里**不按框架过滤** —— 非 MNN 格式（PyTorch / GGUF / ONNX）的模型同样收录，
     * 它们标为 [ModelFormat.needsConversion]，由转换流程处理。
     */
    private suspend fun fetchFromModelScope(): List<ModelItem> {
        val client = com.mnnkit.core.remote.RepoClient(http)
        val results = client.searchModelScope("mnn", pageSize = 100, pageNumber = 1)
        return results.map { r ->
            val fmt = ModelFormat.detectFromFrameworks(r.frameworks)
                ?: if (r.name.contains("MNN", ignoreCase = true) || r.tags.any { it.equals("mnn", true) }) {
                    ModelFormat.MNN
                } else {
                    ModelFormat.UNKNOWN
                }
            ModelItem(
                id = "ms/${r.repoId}",
                displayName = r.name,
                kind = inferKind(r.name, r.tags),
                source = ModelSource.MODELSCOPE,
                repoId = r.repoId,
                vendor = r.owner,
                description = r.description.ifBlank {
                    "魔搭社区模型" + if (r.downloads > 0) "（下载 ${r.downloads}）" else ""
                },
                sizeBytes = r.sizeBytes,
                quant = inferQuant(r.name),
                tags = (r.tags + r.frameworks + "魔搭").filter { it.isNotBlank() }.distinct().take(8),
                sizeIsExact = r.sizeBytes > 0,
                format = fmt,
                convertFromRepo = if (fmt.needsConversion) r.repoId else null,
            )
        }
    }

    /**
     * 搜索任意格式的模型（不限于 MNN）。
     *
     * 用户在商店搜索框里输入关键词时走这条路径，因此"非 MNN 模型也能下载"。
     */
    suspend fun searchAll(keyword: String, pageSize: Int = 60): List<ModelItem> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            val client = com.mnnkit.core.remote.RepoClient(http)
            val results = runCatching {
                client.searchModelScope(keyword, pageSize = pageSize, pageNumber = 1)
            }.getOrDefault(emptyList())

            results.map { r ->
                val fmt = ModelFormat.detectFromFrameworks(r.frameworks)
                    ?: if (r.name.contains("MNN", true)) ModelFormat.MNN else ModelFormat.UNKNOWN
                ModelItem(
                    id = "search/${r.repoId}",
                    displayName = r.name,
                    kind = inferKind(r.name, r.tags),
                    source = ModelSource.MODELSCOPE,
                    repoId = r.repoId,
                    vendor = r.owner,
                    description = r.description.ifBlank { "魔搭社区模型" },
                    sizeBytes = r.sizeBytes,
                    quant = inferQuant(r.name),
                    tags = (r.tags + r.frameworks).filter { it.isNotBlank() }.distinct().take(8),
                    sizeIsExact = r.sizeBytes > 0,
                    format = fmt,
                    convertFromRepo = if (fmt.needsConversion) r.repoId else null,
                )
            }
        }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 把 HF 的仓库 id 映射到当前选定下载源对应的仓库 id。 */
    fun repoIdFor(item: ModelItem, source: ModelSource): String =
        item.altRepos[source.id] ?: item.repoId

    private fun buildDescription(kind: ModelKind, tags: List<String>, categories: List<String>): String {
        val all = (tags + categories).map { it.lowercase() }
        val features = buildList {
            if (all.any { it.contains("vision") }) add("图像理解")
            if (all.any { it.contains("audio") && !it.contains("audiogen") }) add("音频理解")
            if (all.any { it.contains("audiogen") }) add("语音生成")
            if (all.any { it.contains("think") }) add("深度思考")
            if (all.any { it.contains("code") }) add("代码")
            if (all.any { it.contains("math") }) add("数学")
            if (all.any { it.contains("safety") }) add("安全审核")
            if (all.any { it.contains("imagegen") }) add("文生图")
            if (all.any { it.contains("asr") }) add("语音识别")
            if (all.any { it.contains("tts") }) add("语音合成")
        }
        val head = when (kind) {
            ModelKind.LLM -> "MNN 官方转换的大语言模型"
            ModelKind.STT -> "MNN 官方转换的语音识别模型"
            ModelKind.TTS -> "MNN 官方转换的语音合成模型"
            ModelKind.DIFFUSION -> "MNN 官方转换的文生图模型"
        }
        return if (features.isEmpty()) head else "$head，支持${features.joinToString("、")}。"
    }

    private fun inferKind(name: String, tags: List<String> = emptyList()): ModelKind {
        val n = (name + " " + tags.joinToString(" ")).lowercase()
        return when {
            n.contains("asr") || n.contains("whisper") || n.contains("zipformer") ||
                n.contains("paraformer") || n.contains("sensevoice") -> ModelKind.STT
            n.contains("tts") || n.contains("vits") || n.contains("piper") ||
                n.contains("supertonic") || n.contains("kokoro") || n.contains("melo") -> ModelKind.TTS
            n.contains("diffusion") || n.contains("sana") || n.contains("stable-diffusion") ||
                n.contains("imagegen") || n.contains("flux") -> ModelKind.DIFFUSION
            else -> ModelKind.LLM
        }
    }

    private fun inferVendor(name: String): String = when {
        name.startsWith("Qwen", true) -> "Qwen"
        name.startsWith("DeepSeek", true) -> "DeepSeek"
        name.startsWith("Llama", true) -> "Llama"
        name.startsWith("gemma", true) -> "Gemma"
        name.startsWith("Smol", true) -> "Smol"
        name.startsWith("MiniCPM", true) -> "MiniCPM"
        name.startsWith("InternVL", true) -> "InternVL"
        name.startsWith("MobileLLM", true) -> "MobileLLM"
        name.startsWith("Hunyuan", true) -> "Hunyuan"
        name.startsWith("ERNIE", true) -> "ERNIE"
        name.startsWith("glm", true) || name.startsWith("chatglm", true) -> "THUDM"
        name.startsWith("phi", true) -> "Phi"
        else -> "MNN"
    }

    private fun inferQuant(name: String): QuantType {
        val n = name.lowercase()
        return when {
            n.contains("q4") || n.contains("int4") || n.contains("4bit") -> QuantType.INT4
            n.contains("q8") || n.contains("int8") || n.contains("8bit") -> QuantType.INT8
            n.contains("fp16") -> QuantType.FP16
            else -> QuantType.UNKNOWN
        }
    }

    // ---------------- 缓存 ----------------

    private fun cacheFile(source: StoreSource) = File(cacheDir, "store_${source.id}.json")

    private fun diskCache(source: StoreSource): List<ModelItem>? {
        val f = cacheFile(source)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            val json = JsonParser.parse(f.readText())
            val arr = json.arr("items") ?: return null
            arr.mapNotNull { fromJson(it) }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeDiskCache(source: StoreSource, items: List<ModelItem>) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(source).writeText(
                Json.Obj(mapOf("items" to Json.Arr(items.map { toJson(it) }))).stringify()
            )
        }
    }

    private fun toJson(m: ModelItem): Json = Json.Obj(
        mapOf(
            "id" to Json.Str(m.id),
            "displayName" to Json.Str(m.displayName),
            "kind" to Json.Str(m.kind.id),
            "source" to Json.Str(m.source.id),
            "repoId" to Json.Str(m.repoId),
            "vendor" to Json.Str(m.vendor),
            "description" to Json.Str(m.description),
            "sizeBytes" to Json.Num(m.sizeBytes.toDouble()),
            "quant" to Json.Str(m.quant.id),
            "tags" to Json.Arr(m.tags.map { Json.Str(it) }),
            "altRepos" to Json.Obj(m.altRepos.mapValues { Json.Str(it.value) }),
            "sizeIsExact" to Json.Bool(m.sizeIsExact),
            "format" to Json.Str(m.format.id),
            "convertFromRepo" to (m.convertFromRepo?.let { Json.Str(it) } ?: Json.Null),
        )
    )

    private fun fromJson(node: Json): ModelItem? {
        val id = node.str("id") ?: return null
        val kind = ModelKind.fromId(node.str("kind")) ?: return null
        return ModelItem(
            id = id,
            displayName = node.str("displayName") ?: id,
            kind = kind,
            source = ModelSource.fromId(node.str("source")),
            repoId = node.str("repoId") ?: return null,
            vendor = node.str("vendor").orEmpty(),
            description = node.str("description").orEmpty(),
            sizeBytes = node.long("sizeBytes") ?: 0L,
            quant = QuantType.fromId(node.str("quant")),
            tags = node.arr("tags")?.mapNotNull { it.asString } ?: emptyList(),
            altRepos = node.path("altRepos")?.asObject?.mapValues { it.value.asString.orEmpty() }
                ?.filterValues { it.isNotBlank() } ?: emptyMap(),
            sizeIsExact = node.bool("sizeIsExact") ?: false,
            format = ModelFormat.fromId(node.str("format")),
            convertFromRepo = node.str("convertFromRepo"),
        )
    }

    companion object {
        const val HF_AUTHOR = "taobao-mnn"

        val DEFAULT_MARKET_URLS = listOf(
            "https://meta.alicdn.com/data/mnn/apis/model_market.json",
            "https://meta.alicdn.com/data/mnn/apis/model_market_dev.json",
        )

        /** HF 官方优先，失败自动回落 hf-mirror。 */
        val DEFAULT_HF_ENDPOINTS = listOf(
            "https://huggingface.co",
            "https://hf-mirror.com",
        )

        /** 官方清单 + HF 都不可用时的最小可用集合（全部经实测存在）。 */
        val FALLBACK = listOf(
            ModelItem(
                id = "official/llm/Qwen3-0.6B-MNN",
                displayName = "Qwen3-0.6B-MNN",
                kind = ModelKind.LLM,
                source = ModelSource.MODELSCOPE,
                repoId = "MNN/Qwen3-0.6B-MNN",
                vendor = "Qwen",
                description = "MNN 官方转换的大语言模型，支持深度思考。",
                sizeBytes = 454_473_637L,
                quant = QuantType.INT4,
                tags = listOf("recommended", "qwen", "Think"),
                altRepos = mapOf(ModelSource.HUGGINGFACE.id to "taobao-mnn/Qwen3-0.6B-MNN"),
                sizeIsExact = true,
            ),
            ModelItem(
                id = "official/llm/Qwen3-1.7B-MNN",
                displayName = "Qwen3-1.7B-MNN",
                kind = ModelKind.LLM,
                source = ModelSource.MODELSCOPE,
                repoId = "MNN/Qwen3-1.7B-MNN",
                vendor = "Qwen",
                description = "MNN 官方转换的大语言模型，支持深度思考。",
                sizeBytes = 1_235_523_444L,
                quant = QuantType.INT4,
                tags = listOf("recommended", "qwen", "Think"),
                altRepos = mapOf(ModelSource.HUGGINGFACE.id to "taobao-mnn/Qwen3-1.7B-MNN"),
                sizeIsExact = true,
            ),
            ModelItem(
                id = "official/tts/bert-vits2-MNN",
                displayName = "bert-vits2-MNN",
                kind = ModelKind.TTS,
                source = ModelSource.MODELSCOPE,
                repoId = "MNN/bert-vits2-MNN",
                vendor = "MNN",
                description = "MNN 官方转换的语音合成模型，中英双语。",
                sizeBytes = 1_392_023_806L,
                quant = QuantType.UNKNOWN,
                tags = listOf("TTS", "中文", "英文"),
            ),
            ModelItem(
                id = "official/stt/zipformer-bilingual-zh-en",
                displayName = "streaming-zipformer-bilingual-zh-en-2023-02-20",
                kind = ModelKind.STT,
                source = ModelSource.MODELSCOPE,
                repoId = "MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20",
                vendor = "k2-fsa",
                description = "MNN 官方转换的语音识别模型，中英双语流式识别。",
                sizeBytes = 295_334_711L,
                quant = QuantType.INT8,
                tags = listOf("ASR", "中文", "英文", "流式"),
            ),
            ModelItem(
                id = "official/diffusion/stable-diffusion-v1-5-mnn-opencl",
                displayName = "stable-diffusion-v1-5-mnn-opencl",
                kind = ModelKind.DIFFUSION,
                source = ModelSource.MODELSCOPE,
                repoId = "MNN/stable-diffusion-v1-5-mnn-opencl",
                vendor = "stability.ai",
                description = "MNN 官方转换的文生图模型，使用 OpenCL GPU 加速。",
                sizeBytes = 1_154_249_052L,
                quant = QuantType.INT8,
                tags = listOf("ImageGen", "文生图"),
                altRepos = mapOf(ModelSource.HUGGINGFACE.id to "taobao-mnn/stable-diffusion-v1-5-mnn-opencl"),
            ),
        )

        /** HF 来源离线时的兜底（少量高频模型）。 */
        val FALLBACK_HF = listOf(
            "Qwen3-0.6B-MNN" to ModelKind.LLM,
            "Qwen3-1.7B-MNN" to ModelKind.LLM,
            "Qwen3-4B-MNN" to ModelKind.LLM,
            "Qwen2.5-1.5B-Instruct-MNN" to ModelKind.LLM,
            "SmolVLM-500M-Instruct-MNN" to ModelKind.LLM,
            "Llama-3.2-1B-Instruct-MNN" to ModelKind.LLM,
            "gemma-3-1b-it-qat-q4_0-gguf-MNN" to ModelKind.LLM,
        ).map { (name, kind) ->
            ModelItem(
                id = "hf/${kind.id}/$name",
                displayName = name,
                kind = kind,
                source = ModelSource.HUGGINGFACE,
                repoId = "$HF_AUTHOR/$name",
                vendor = "MNN",
                description = "HuggingFace 上的 MNN 格式模型",
                sizeBytes = 0L,
                tags = listOf("HF"),
                sizeIsExact = false,
            )
        }
    }
}
