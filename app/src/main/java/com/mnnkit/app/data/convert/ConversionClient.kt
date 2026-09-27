package com.mnnkit.app.data.convert

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.model.ModelFormat
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 模型转换服务客户端。
 *
 * ## 为什么转换不是在本机 App 内完成的
 *
 * MNN 官方的转换入口都需要 **Python + PyTorch / ONNX** 运行时：
 *   - `transformers/llm/export/llmexport.py` 第一行就 `import torch`
 *   - `mnnconvert`（`pip install MNN`）面向桌面平台发布
 *
 * 要在 Android 上跑这些，必须把整套 CPython + PyTorch + transformers 打进 APK
 * （数百 MB 到 1GB 以上的原生库）。这在工程上不成立，MNN 官方文档也明确
 * **没有任何"在 Android 设备上转换"的方案**（见 docs/research-mnn-official-docs.md §3）。
 *
 * ## 因此本应用采用的方案
 *
 * 应用负责：**浏览 / 下载任意格式模型 → 发起转换 → 接收产物 → 本地加载运行**。
 * 真正的转换由用户自己的电脑（或 Termux）上的 [ConversionService] 完成 ——
 * 服务脚本见工程内 `scripts/mnn-convert-service.py`，一条命令即可启动。
 *
 * 手机与电脑在同一局域网（或 USB 端口转发）时，本客户端通过 HTTP 与之通信。
 * 这既绕开了端侧无法运行 PyTorch 的硬约束，又满足"在软件内完成转换"的使用体验。
 */
class ConversionClient(
    private val http: Http = Http(),
) {

    /** 转换服务的默认探测地址（USB adb reverse 与常见局域网网段）。 */
    var baseUrl: String = DEFAULT_BASE_URL

    var lastError: String? = null
        private set

    /** 探测服务是否在线。 */
    suspend fun probe(url: String = baseUrl): ConversionServiceInfo? = withContext(Dispatchers.IO) {
        val target = url.trimEnd('/')
        try {
            val text = http.getText("$target/health", timeoutMs = 4_000)
            val json = JsonParser.parseOrNull(text) ?: return@withContext null
            if (json.bool("ok") != true) return@withContext null
            baseUrl = target
            lastError = null
            ConversionServiceInfo(
                version = json.str("version") ?: "unknown",
                platform = json.str("platform") ?: "unknown",
                hasTorch = json.bool("torch") ?: false,
                hasTransformers = json.bool("transformers") ?: false,
                hasMnnConvert = json.bool("mnnconvert") ?: false,
                workspace = json.str("workspace") ?: "",
            )
        } catch (e: Exception) {
            lastError = e.message
            null
        }
    }

    /**
     * 提交一次转换任务。
     *
     * [sourceRepo] 是原始模型仓库 id（如 `Qwen/Qwen3-0.6B`），
     * 服务端负责下载原始权重（或复用应用已下载的本地目录）并导出成 .mnn。
     */
    suspend fun submit(request: ConvertRequest): ConvertHandle = withContext(Dispatchers.IO) {
        val body = Json.Obj(
            mapOf(
                "repo" to Json.Str(request.repo),
                "kind" to Json.Str(request.kind.id),
                "format" to Json.Str(request.format.id),
                "quantBit" to Json.Num(request.quantBit.toDouble()),
                "quantBlock" to Json.Num(request.quantBlock.toDouble()),
                // 若应用已把原始权重下载到手机上，可告知服务端复用哪种获取方式
                "localDir" to (request.localDir?.let { Json.Str(it) } ?: Json.Null),
                "hfEndpoint" to Json.Str(request.hfEndpoint),
            )
        ).stringify()

        val text = http.postJson("${baseUrl.trimEnd('/')}/convert", body, timeoutMs = 30_000)
        val json = JsonParser.parse(text)
        val id = json.str("taskId") ?: throw IllegalStateException("转换服务未返回 taskId")
        ConvertHandle(id)
    }

    /** 查询任务状态。 */
    suspend fun status(handle: ConvertHandle): ConvertStatus = withContext(Dispatchers.IO) {
        val text = http.getText("${baseUrl.trimEnd('/')}/status/${handle.taskId}", timeoutMs = 15_000)
        val json = JsonParser.parse(text)
        ConvertStatus(
            taskId = handle.taskId,
            state = ConvertState.fromId(json.str("state")),
            progress = (json.path("progress")?.asDouble ?: 0.0).toFloat(),
            message = json.str("message").orEmpty(),
            outputFiles = json.arr("files")?.mapNotNull { it.asString } ?: emptyList(),
            outputBytes = json.long("outputBytes") ?: 0L,
            error = json.str("error"),
        )
    }

    /**
     * 轮询直到完成。[onProgress] 用于驱动界面进度条。
     *
     * 默认最多等 60 分钟（7B 级模型在电脑上导出加量化通常 10~40 分钟）。
     */
    suspend fun await(
        handle: ConvertHandle,
        pollIntervalMs: Long = 2_000,
        timeoutMs: Long = 60L * 60 * 1000,
        onProgress: (ConvertStatus) -> Unit = {},
    ): ConvertStatus = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var last = ConvertStatus(handle.taskId, ConvertState.PENDING)
        while (System.currentTimeMillis() - started < timeoutMs) {
            last = status(handle)
            onProgress(last)
            if (last.state.isTerminal) return@withContext last
            delay(pollIntervalMs)
        }
        last.copy(state = ConvertState.FAILED, error = "转换超时")
    }

    companion object {
        /**
         * 默认地址。
         *
         * `adb reverse tcp:8765 tcp:8765` 可让手机上的 127.0.0.1:8765 直达电脑，
         * 这是最省事、也最不依赖局域网的接法。
         */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8765"
    }
}

data class ConversionServiceInfo(
    val version: String,
    val platform: String,
    val hasTorch: Boolean,
    val hasTransformers: Boolean,
    val hasMnnConvert: Boolean,
    val workspace: String,
) {
    /** 是否具备做 LLM 导出的条件。 */
    val canExportLlm: Boolean get() = hasTorch && hasTransformers

    val summary: String
        get() = buildString {
            append("转换服务 $version（$platform）")
            append(if (canExportLlm) " · PyTorch 就绪" else " · 缺少 PyTorch")
            if (hasMnnConvert) append(" · mnnconvert 就绪")
        }
}

data class ConvertRequest(
    val repo: String,
    val kind: ModelKind,
    val format: ModelFormat,
    val quantBit: Int = 4,
    val quantBlock: Int = 64,
    /** 应用已下载到本机的原始权重目录（可选，服务端可据此避免重复下载） */
    val localDir: String? = null,
    val hfEndpoint: String = "https://hf-mirror.com",
)

@JvmInline
value class ConvertHandle(val taskId: String)

enum class ConvertState(val id: String) {
    PENDING("pending"),
    DOWNLOADING("downloading"),
    EXPORTING("exporting"),
    QUANTIZING("quantizing"),
    DONE("done"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED

    val label: String
        get() = when (this) {
            PENDING -> "排队中"
            DOWNLOADING -> "下载原始权重"
            EXPORTING -> "导出 MNN 图"
            QUANTIZING -> "量化"
            DONE -> "完成"
            FAILED -> "失败"
            CANCELLED -> "已取消"
        }

    companion object {
        fun fromId(id: String?): ConvertState =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PENDING
    }
}

data class ConvertStatus(
    val taskId: String,
    val state: ConvertState,
    val progress: Float = 0f,
    val message: String = "",
    val outputFiles: List<String> = emptyList(),
    val outputBytes: Long = 0L,
    val error: String? = null,
)
