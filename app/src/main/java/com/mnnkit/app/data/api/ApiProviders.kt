package com.mnnkit.app.data.api

import com.mnnkit.app.data.Storage
import com.mnnkit.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * API 提供商的配置与调用入口。
 *
 * 照 [com.mnnkit.app.data.ModelManager] 的模式：`StateFlow` 暴露状态、
 * `suspend` 方法走 IO、异常收成 `state.error` 字符串，**从不向 UI 抛异常**。
 *
 * ## 「当前用哪个」的语义
 *
 * [activeId] 为空表示**用本地模型**；非空表示优先用那个 API 提供商。
 * 对话页顶部的「模型」面板就是在这两者之间切。
 */
class ApiProviders(
    private val storage: Storage,
    http: Http,
) {

    private val store = ApiProviderStore(storage.root)
    private val client = OpenAiCompatibleClient(http)

    private val _state = MutableStateFlow(ApiProviderStore.Snapshot())
    val state: StateFlow<ApiProviderStore.Snapshot> = _state.asStateFlow()

    /** 最近一次操作的提示 / 错误，界面用横幅展示。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 从磁盘读。启动时调用。 */
    fun load() {
        _state.value = store.load()
    }

    /** 当前生效的提供商；null 表示用本地模型。 */
    fun active(): ApiProvider? = _state.value.active

    /** 「模型」面板里显示的当前模型名。 */
    fun activeLabel(): String? = active()?.let { "${it.name} / ${it.llmModel}" }

    fun setActive(id: String?) {
        val next = _state.value.copy(activeId = id)
        _state.value = next
        store.save(next)
    }

    // ── 增删改 ──

    fun add(provider: ApiProvider) {
        val next = _state.value.copy(providers = _state.value.providers + provider)
        _state.value = next
        store.save(next)
        _notice.value = "已添加 ${provider.name}"
    }

    fun update(provider: ApiProvider) {
        val next = _state.value.copy(
            providers = _state.value.providers.map { if (it.id == provider.id) provider else it },
        )
        _state.value = next
        store.save(next)
        _notice.value = "已保存 ${provider.name}"
    }

    fun remove(id: String) {
        val next = _state.value.copy(
            providers = _state.value.providers.filterNot { it.id == id },
            // 删掉的正好是当前生效的，就退回本地模型，避免挂着一个不存在的 id
            activeId = _state.value.activeId?.takeIf { it != id },
        )
        _state.value = next
        store.save(next)
        _notice.value = "已删除"
    }

    /** 清除全部配置（含密钥）。 */
    fun clearAll() {
        store.clear()
        _state.value = ApiProviderStore.Snapshot()
        _notice.value = "已清除全部 API 配置"
    }

    fun clearNotice() {
        _notice.value = null
    }

    // ── 调用 ──

    /**
     * 测试连通性：发一条极短的对话请求。
     *
     * 这是唯一能让用户确认「key 填对了、baseUrl 没写错」的手段 ——
     * 否则只能等到真正对话时才发现问题。
     */
    suspend fun test(provider: ApiProvider): String = withContext(Dispatchers.IO) {
        provider.validationError()?.let { return@withContext "配置有误：$it" }
        if (!provider.canChat) return@withContext "该提供商没填对话模型名，无法测试"
        runCatching {
            val reply = client.chat(
                provider = provider,
                messages = listOf("user" to "回复两个字：正常"),
                maxTokens = 16,
            )
            "连接成功，模型回复：${reply.take(80)}"
        }.getOrElse { "连接失败：${it.message}" }
    }

    /** 流式对话。[thinking] 见 [OpenAiCompatibleClient.ThinkingEffort]。 */
    fun chatStream(
        provider: ApiProvider,
        messages: List<Pair<String, String>>,
        temperature: Double,
        topP: Double,
        maxTokens: Int,
        systemPrompt: String?,
        thinking: OpenAiCompatibleClient.ThinkingEffort = OpenAiCompatibleClient.ThinkingEffort.DEFAULT,
    ) = client.chatStream(
        provider, messages, temperature, topP, maxTokens, systemPrompt, thinking,
    )

    /** 语音识别。 */
    suspend fun transcribe(provider: ApiProvider, audio: File): String =
        withContext(Dispatchers.IO) { client.transcribe(provider, audio) }

    /** 语音合成，产出 mp3。 */
    suspend fun speak(provider: ApiProvider, text: String, target: File): File =
        withContext(Dispatchers.IO) { client.speak(provider, text, target) }

    /** 文生图。 */
    suspend fun generateImage(
        provider: ApiProvider,
        prompt: String,
        target: File,
        size: String? = null,
    ): File = withContext(Dispatchers.IO) {
        client.generateImage(provider, prompt, target, size)
    }

    /** 新建一个空提供商（界面「添加」用）。 */
    fun newProvider(): ApiProvider = ApiProvider(
        id = System.currentTimeMillis().toString(),
        name = "",
        baseUrl = "https://api.openai.com/v1",
    )

    /**
     * 内置的常见提供商预设，方便用户一键填入 baseUrl。
     *
     * ## ⚠️ DeepSeek 不带 `/v1`
     *
     * 官方文档与示例给的就是 **`https://api.deepseek.com/chat/completions`** ——
     * 路径里**没有 `/v1`**。所以 baseUrl 必须填 `https://api.deepseek.com`，
     * 客户端再拼 `/chat/completions` 才对得上。
     *
     * （我此前按「OpenAI 兼容服务都带 /v1」的惯性写成了
     * `https://api.deepseek.com/v1`。实测那个地址**也能返回 200**，
     * 所以不是致命错误，但既然官方给的是不带 `/v1` 的形式，
     * 就照官方来 —— 少一层非标准前缀，也少一个可能的踩坑点。）
     *
     * 其余各家照官方「兼容模式」端点原样填（它们本身就以 `/v1`、`/v4` 结尾）。
     */
    fun presets(): List<Pair<String, String>> = listOf(
        "DeepSeek" to "https://api.deepseek.com",
        "OpenAI" to "https://api.openai.com/v1",
        "阿里百炼（DashScope 兼容）" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "硅基流动 SiliconFlow" to "https://api.siliconflow.cn/v1",
        "智谱 GLM" to "https://open.bigmodel.cn/api/paas/v4",
        "月之暗面 Kimi" to "https://api.moonshot.cn/v1",
        "本地 Ollama" to "http://127.0.0.1:11434/v1",
        "本地 vLLM / LM Studio" to "http://127.0.0.1:8000/v1",
    )

    /**
     * 各提供商**实测/官方文档确认**的模型名，供界面做候选。
     *
     * 用户手打模型名极容易错一个字符就 404。这里给已知的组合，
     * 界面可以做成可点的候选；用户也仍可自己填（服务商随时上新品）。
     *
     * DeepSeek 的取值来自官方文档
     * （https://api-docs.deepseek.com/api/create-chat-completion）：
     * `model` 是 `deepseek-flash` 或 `deepseek-v4-pro`。
     */
    fun knownModels(baseUrl: String): List<String> = when {
        baseUrl.contains("deepseek") -> listOf("deepseek-flash", "deepseek-v4-pro")
        baseUrl.contains("openai.com") -> listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")
        baseUrl.contains("moonshot") -> listOf("moonshot-v1-8k", "moonshot-v1-32k", "kimi-k2-0905-preview")
        baseUrl.contains("bigmodel") -> listOf("glm-4-flash", "glm-4-plus", "glm-4v-flash")
        baseUrl.contains("siliconflow") -> listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-7B-Instruct")
        baseUrl.contains("dashscope") -> listOf("qwen-plus", "qwen-turbo", "qwen-max")
        else -> emptyList()
    }
}
