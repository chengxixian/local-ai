package com.mnnkit.app

import android.content.Context
import com.mnnkit.app.data.AppSettings
import com.mnnkit.app.data.ModelCatalog
import com.mnnkit.app.data.ModelManager
import com.mnnkit.app.data.mcp.McpManager
import com.mnnkit.app.data.skill.SkillManager
import com.mnnkit.app.speech.SherpaSttEngine
import com.mnnkit.app.speech.SherpaTtsEngine
import com.mnnkit.app.speech.SpeechRouter
import com.mnnkit.core.speech.SttEngine
import com.mnnkit.core.speech.TtsEngine
import java.io.File
import com.mnnkit.app.data.Storage
import com.mnnkit.app.data.memory.MemoryManager
import com.mnnkit.app.data.memory.MemoryStore
import com.mnnkit.app.image.UnavailableDiffusionEngine
import com.mnnkit.app.data.api.ApiProviders
import com.mnnkit.app.llm.MnnLlmEngine
import com.mnnkit.app.llm.UnavailableLlmEngine
import com.mnnkit.core.chat.LlmEngine
import com.mnnkit.core.image.DiffusionEngine
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.net.Http
import com.mnnkit.core.remote.RepoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简手工依赖注入。
 *
 * 不引 Hilt/Koin：依赖图很浅（十几个单例），手工构造更透明，
 * 也避免注解处理器带来的构建复杂度。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val storage = Storage(appContext)

    val settings = AppSettings(appContext)

    val http = Http()

    val repoClient = RepoClient(http)

    val catalog = ModelCatalog(http, storage.cacheDir)

    val modelManager = ModelManager(
        storage = storage,
        repoClient = repoClient,
        http = http,
        catalog = catalog,
        scope = appScope,
    )

    val memoryStore = MemoryStore(appContext)

    val memoryManager = MemoryManager(memoryStore, settings)

    /**
     * Skill 库：内置索引 + GitHub 安装 + 启用状态。
     *
     * 索引来自 `assets/skills_index.json`（缺失时商店为空，但已安装的仍可用）。
     */
    val skillManager = SkillManager(
        context = appContext,
        storage = storage,
        http = http,
        scope = appScope,
    )

    /**
     * MCP 客户端：配置存 `mcp_servers.json`，连接后拉工具清单。
     *
     * 构造后需要 [McpManager.load] 才会读到磁盘上的配置，
     * 这一步在 [com.mnnkit.app.MnnKitApplication] 里做。
     */
    val mcpManager = McpManager(
        storage = storage,
        http = http,
        scope = appScope,
    )

    /**
     * LLM 引擎：优先用 MNN 原生实现；原生库缺失时退化为明确的错误提示实现，
     * 保证 UI 永远可以启动、浏览与下载模型。
     */
    val llmEngine: LlmEngine by lazy {
        runCatching {
            // 推理后端从设置读入（CPU / OpenCL / Vulkan / NPU）。
            // 引擎是 by lazy、进程内只构造一次，用户改后端后重启应用生效。
            MnnLlmEngine(
                backendType = settings.state.value.backendType,
                // 「关闭思考」= thinkingEffort 为 none（默认）。
                enableThinking = settings.state.value.thinkingEffort != "none",
            ) as LlmEngine
        }
            .getOrElse { UnavailableLlmEngine(it.message ?: "MNN 原生库加载失败") }
    }

    /** 文生图引擎。原生实现未接入时保持不可用状态，UI 会给出明确提示。 */
    val diffusionEngine: DiffusionEngine by lazy { UnavailableDiffusionEngine() }

    /**
     * API 提供商（云端 / 局域网 OpenAI 兼容服务）。
     *
     * 与本地模型**并存**：用户在「模型」面板里选哪个，就走哪条路径。
     * 配置存在 `files/api_providers.json`（含密钥，见 [com.mnnkit.app.data.api.ApiProviderStore]）。
     */
    val apiProviders: ApiProviders by lazy { ApiProviders(storage, http) }

    /**
     * 语音合成 / 播放的统一入口。
     *
     * 自动在「API TTS（产出真 mp3）」与「本地 Sherpa-MNN（产出 PCM → m4a）」
     * 之间选择，见 [SpeechRouter]。
     */
    val speechRouter: SpeechRouter by lazy {
        SpeechRouter(
            context = appContext,
            apiProviders = apiProviders,
            outputDir = File(storage.root, "audio").apply { mkdirs() },
        )
    }

    /** 语音识别引擎（本地 Sherpa-MNN）。API 路径由 [apiProviders] 直接提供。 */
    val sttEngine: SttEngine by lazy { SherpaSttEngine() }

    /** 语音合成引擎（本地 Sherpa-MNN），供 [speechRouter] 在无 API 时回退使用。 */
    val ttsEngine: TtsEngine by lazy { SherpaTtsEngine() }

    /**
     * 已被用户选为"当前使用"的模型，按用途分别记录。
     * 用 SharedPreferences 里的 id 字段持久化。
     */
    fun selectedModel(kind: ModelKind): String? =
        settings.state.value.selectedModelIds[kind.id]

    fun selectModel(kind: ModelKind, modelId: String) {
        settings.update { s ->
            s.copy(selectedModelIds = s.selectedModelIds + (kind.id to modelId))
        }
    }
}
