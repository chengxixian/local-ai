package com.mnnkit.app.data.api

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import java.io.File

/**
 * 一个 API 提供商的配置。
 *
 * ## 设计取舍：统一按「OpenAI 兼容」抽象，而不是给每家的 SDK
 *
 * 现实里绝大多数服务商都提供 **OpenAI 兼容**端点
 * （`/v1/chat/completions`、`/v1/audio/transcriptions`、`/v1/audio/speech`、
 * `/v1/images/generations`），包括 OpenAI 自己、DeepSeek、阿里百炼、硅基流动、
 * 以及本地的 Ollama / vLLM / LM Studio。
 *
 * 所以抽象成一个 `baseUrl + apiKey + 各能力的模型名`，而不是为每家写适配器 ——
 * 那样代码量翻几倍，而且每加一家都要改代码。用户只要会填 baseUrl 就能接任意兼容服务。
 *
 * 少数不兼容的（Anthropic Messages API、Gemini generateContent）暂不支持，
 * 但字段留了 [ApiStyle] 以便将来扩展。
 *
 * ## 为什么 apiKey 单独存一个文件而不是 SharedPreferences
 *
 * 见 [ApiProviderStore] —— 密钥要能单独清除，且不该混在普通设置里被一起导出。
 *
 * @param id 稳定标识（时间戳），改名不影响引用
 * @param name 用户可读的名字，会显示在「模型」面板里
 * @param baseUrl 形如 `https://api.openai.com/v1`（**含** `/v1`，不要带末尾斜杠）
 * @param apiKey Bearer token
 * @param style 接口风格；目前只实现 [ApiStyle.OPENAI]
 * @param llmModel 对话模型名，留空表示不用这个提供商做对话
 * @param asrModel 语音识别模型名（如 `whisper-1`）
 * @param ttsModel 语音合成模型名（如 `tts-1`）
 * @param ttsVoice 合成音色（如 `alloy`）
 * @param imageModel 生图模型名（如 `dall-e-3`）
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val style: ApiStyle = ApiStyle.OPENAI,
    val llmModel: String = "",
    val asrModel: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "alloy",
    val imageModel: String = "",
) {
    /** 能用来对话。 */
    val canChat: Boolean get() = baseUrl.isNotBlank() && llmModel.isNotBlank()

    /** 能用来做语音识别。 */
    val canAsr: Boolean get() = baseUrl.isNotBlank() && asrModel.isNotBlank()

    /** 能用来做语音合成。 */
    val canTts: Boolean get() = baseUrl.isNotBlank() && ttsModel.isNotBlank()

    /** 能用来生图。 */
    val canImage: Boolean get() = baseUrl.isNotBlank() && imageModel.isNotBlank()

    /** 有任意一项能力可用。 */
    val isUsable: Boolean get() = canChat || canAsr || canTts || canImage

    /**
     * 配置问题说明；无问题返回 null。
     *
     * 界面直接展示这段文字，所以要说清**缺什么**而不是笼统说「配置不完整」。
     */
    fun validationError(): String? = when {
        name.isBlank() -> "缺少名称"
        baseUrl.isBlank() -> "缺少 baseUrl"
        !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
            "baseUrl 必须以 http:// 或 https:// 开头"
        style == ApiStyle.OPENAI && !isUsable ->
            "至少要填一个模型名（对话 / 语音识别 / 语音合成 / 生图 任选）"
        else -> null
    }

    /** 去掉末尾斜杠，拼路径时不会出现 `//`。 */
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    fun endpoint(path: String): String = normalizedBaseUrl + "/" + path.trimStart('/')

    fun toJson(): Json = Json.Obj(
        buildMap {
            put("id", Json.Str(id))
            put("name", Json.Str(name))
            put("baseUrl", Json.Str(baseUrl))
            put("apiKey", Json.Str(apiKey))
            put("style", Json.Str(style.id))
            put("llmModel", Json.Str(llmModel))
            put("asrModel", Json.Str(asrModel))
            put("ttsModel", Json.Str(ttsModel))
            put("ttsVoice", Json.Str(ttsVoice))
            put("imageModel", Json.Str(imageModel))
        }
    )

    companion object {
        fun from(json: Json): ApiProvider? {
            val o = json.asObject ?: return null
            val id = o["id"]?.asString ?: return null
            return ApiProvider(
                id = id,
                name = o["name"]?.asString ?: "未命名",
                baseUrl = o["baseUrl"]?.asString.orEmpty(),
                apiKey = o["apiKey"]?.asString.orEmpty(),
                style = ApiStyle.fromId(o["style"]?.asString),
                llmModel = o["llmModel"]?.asString.orEmpty(),
                asrModel = o["asrModel"]?.asString.orEmpty(),
                ttsModel = o["ttsModel"]?.asString.orEmpty(),
                ttsVoice = o["ttsVoice"]?.asString ?: "alloy",
                imageModel = o["imageModel"]?.asString.orEmpty(),
            )
        }
    }
}

/** 接口风格。目前只实现 OpenAI 兼容。 */
enum class ApiStyle(val id: String, val label: String) {
    OPENAI("openai", "OpenAI 兼容"),
    ;

    companion object {
        fun fromId(id: String?): ApiStyle =
            entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

/**
 * API 提供商的持久化。
 *
 * 存 `<external-files>/api_providers.json`，格式：
 * ```json
 * { "providers": [ {...}, {...} ], "activeId": "1699..." }
 * ```
 *
 * `activeId` 记录当前生效的提供商 —— 「模型」面板里选的就是它。
 * 为空表示当前用本地模型。
 */
class ApiProviderStore(private val root: File) {

    private val file: File get() = File(root, "api_providers.json")

    data class Snapshot(
        val providers: List<ApiProvider> = emptyList(),
        val activeId: String? = null,
    ) {
        val active: ApiProvider? get() = providers.firstOrNull { it.id == activeId }
    }

    fun load(): Snapshot {
        if (!file.exists()) return Snapshot()
        return runCatching {
            val text = file.readText()
            val json = JsonParser.parseOrNull(text) ?: return Snapshot()
            val arr = json.arr("providers").orEmpty()
            Snapshot(
                providers = arr.mapNotNull { ApiProvider.from(it) },
                activeId = json.str("activeId"),
            )
        }.getOrElse { Snapshot() }
    }

    fun save(snapshot: Snapshot) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = Json.Obj(
                mapOf(
                    "providers" to Json.Arr(snapshot.providers.map { it.toJson() }),
                    "activeId" to (snapshot.activeId?.let { Json.Str(it) } ?: Json.Null),
                )
            )
            file.writeText(json.stringify(pretty = true))
        }
    }

    /** 清空所有提供商与密钥（「清除全部 API 配置」用）。 */
    fun clear() {
        runCatching { file.delete() }
    }
}
