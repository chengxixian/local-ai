package com.mnnkit.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用设置。用 SharedPreferences 持久化，用 StateFlow 暴露给 Compose。
 *
 * 刻意不引 DataStore：键值很少，SharedPreferences 是框架内置，
 * 且可在 Application.onCreate 里同步读取，省掉一个依赖和一次异步竞态。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mnnkit_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        /** 下载源偏好：modelscope / hf-mirror / huggingface */
        val downloadSource: String,
        val hfMirrorHost: String,
        /** 是否在回复后自动朗读 */
        val autoSpeak: Boolean,
        /** 语音识别完成后自动发送给 LLM */
        val autoSendAfterStt: Boolean,
        /** 记忆库开关 */
        val memoryEnabled: Boolean,
        /** 每轮注入的记忆条数 */
        val memoryTopK: Int,
        /** 是否把整轮对话写入记忆 */
        val memoryAutoWrite: Boolean,
        val temperature: Float,
        val topP: Float,
        val maxNewTokens: Int,
        val systemPrompt: String,
        val threadCount: Int,
        /** 是否使用 OpenCL/GPU 后端（旧字段，保留兼容；新代码用 [backendType]） */
        val useGpu: Boolean,
        /**
         * 推理后端。取值必须是 MNN LLM 引擎认识的字符串 ——
         * 权威列表见 `MNN/transformers/llm/engine/src/llm.cpp` 的 `backend_type_convert()`：
         * `cpu` / `metal` / `cuda` / `opencl` / `opengl` / `vulkan` / `hexagon` / `qnn` / `npu`。
         * 未知值会落到 `MNN_FORWARD_AUTO`。
         *
         * 本工程只暴露 Android 上真正有意义的三种，见 [InferenceBackend]。
         */
        val backendType: String,
        /**
         * 思考（推理）强度，取值见 [com.mnnkit.app.data.api.OpenAiCompatibleClient.ThinkingEffort]。
         *
         * 对**本地**模型它决定 `enable_thinking`（是否输出思考片段）；
         * 对 **API** 它映射成官方的 `thinking.reasoning_effort`。
         * 默认关闭 —— 端侧小模型开着思考经常把 token 预算耗在思考上，
         * 正文还没开始就结束（真机实测过 `decode_len=1`）。
         */
        val thinkingEffort: String,
        /** 是否使用 MCP 工具调用 */
        val mcpEnabled: Boolean,
        /** 是否启用 Skill */
        val skillsEnabled: Boolean,
        /** 各用途当前选中的模型 id，键为 ModelKind.id */
        val selectedModelIds: Map<String, String>,
        /** 强调色来源，取值见 MnnAccentColor.name；默认 Dynamic（跟随壁纸） */
        val accentColor: String,
        /** 主题模式：system / light / dark */
        val themeMode: String,
    ) {
        companion object {
            val DEFAULT = State(
                downloadSource = "modelscope",
                hfMirrorHost = "hf-mirror.com",
                autoSpeak = false,
                autoSendAfterStt = true,
                memoryEnabled = true,
                memoryTopK = 5,
                memoryAutoWrite = true,
                temperature = 0.7f,
                topP = 0.9f,
                maxNewTokens = 512,
                systemPrompt = "你是一个运行在手机本地的 AI 助手。回答要准确、简洁，使用与用户相同的语言。",
                threadCount = 4,
                useGpu = true,
                backendType = InferenceBackend.DEFAULT.id,
                thinkingEffort = "none",
                mcpEnabled = true,
                skillsEnabled = true,
                selectedModelIds = emptyMap(),
                accentColor = "Dynamic",
                themeMode = "system",
            )
        }
    }

    private fun read(): State {
        val d = State.DEFAULT
        return State(
            downloadSource = prefs.getString(K_DL_SOURCE, d.downloadSource) ?: d.downloadSource,
            hfMirrorHost = prefs.getString(K_HF_MIRROR, d.hfMirrorHost) ?: d.hfMirrorHost,
            autoSpeak = prefs.getBoolean(K_AUTO_SPEAK, d.autoSpeak),
            autoSendAfterStt = prefs.getBoolean(K_AUTO_SEND_STT, d.autoSendAfterStt),
            memoryEnabled = prefs.getBoolean(K_MEMORY, d.memoryEnabled),
            memoryTopK = prefs.getInt(K_MEMORY_TOPK, d.memoryTopK),
            memoryAutoWrite = prefs.getBoolean(K_MEMORY_WRITE, d.memoryAutoWrite),
            temperature = prefs.getFloat(K_TEMP, d.temperature),
            topP = prefs.getFloat(K_TOPP, d.topP),
            maxNewTokens = prefs.getInt(K_MAX_TOKENS, d.maxNewTokens),
            systemPrompt = prefs.getString(K_SYS_PROMPT, d.systemPrompt) ?: d.systemPrompt,
            threadCount = prefs.getInt(K_THREADS, d.threadCount),
            useGpu = prefs.getBoolean(K_GPU, d.useGpu),
            backendType = prefs.getString(K_BACKEND, d.backendType) ?: d.backendType,
            thinkingEffort = prefs.getString(K_THINKING, d.thinkingEffort) ?: d.thinkingEffort,
            mcpEnabled = prefs.getBoolean(K_MCP, d.mcpEnabled),
            skillsEnabled = prefs.getBoolean(K_SKILLS, d.skillsEnabled),
            selectedModelIds = prefs.getString(K_SELECTED, null)
                ?.split('\n')
                ?.mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                }
                ?.toMap()
                ?: emptyMap(),
            accentColor = prefs.getString(K_ACCENT, d.accentColor) ?: d.accentColor,
            themeMode = prefs.getString(K_THEME_MODE, d.themeMode) ?: d.themeMode,
        )
    }

    fun update(transform: (State) -> State) {
        val next = transform(_state.value)
        prefs.edit().apply {
            putString(K_DL_SOURCE, next.downloadSource)
            putString(K_HF_MIRROR, next.hfMirrorHost)
            putBoolean(K_AUTO_SPEAK, next.autoSpeak)
            putBoolean(K_AUTO_SEND_STT, next.autoSendAfterStt)
            putBoolean(K_MEMORY, next.memoryEnabled)
            putInt(K_MEMORY_TOPK, next.memoryTopK)
            putBoolean(K_MEMORY_WRITE, next.memoryAutoWrite)
            putFloat(K_TEMP, next.temperature)
            putFloat(K_TOPP, next.topP)
            putInt(K_MAX_TOKENS, next.maxNewTokens)
            putString(K_SYS_PROMPT, next.systemPrompt)
            putInt(K_THREADS, next.threadCount)
            putBoolean(K_GPU, next.useGpu)
            putString(K_BACKEND, next.backendType)
            putString(K_THINKING, next.thinkingEffort)
            putBoolean(K_MCP, next.mcpEnabled)
            putBoolean(K_SKILLS, next.skillsEnabled)
            putString(
                K_SELECTED,
                next.selectedModelIds.entries.joinToString("\n") { "${it.key}=${it.value}" },
            )
            putString(K_ACCENT, next.accentColor)
            putString(K_THEME_MODE, next.themeMode)
        }.apply()
        _state.value = next
    }

    private companion object {
        const val K_DL_SOURCE = "download_source"
        const val K_HF_MIRROR = "hf_mirror_host"
        const val K_AUTO_SPEAK = "auto_speak"
        const val K_AUTO_SEND_STT = "auto_send_stt"
        const val K_MEMORY = "memory_enabled"
        const val K_MEMORY_TOPK = "memory_topk"
        const val K_MEMORY_WRITE = "memory_auto_write"
        const val K_TEMP = "temperature"
        const val K_TOPP = "top_p"
        const val K_MAX_TOKENS = "max_new_tokens"
        const val K_SYS_PROMPT = "system_prompt"
        const val K_THREADS = "thread_count"
        const val K_GPU = "use_gpu"
        const val K_BACKEND = "backend_type"
        const val K_THINKING = "thinking_effort"
        const val K_MCP = "mcp_enabled"
        const val K_SKILLS = "skills_enabled"
        const val K_SELECTED = "selected_models"
        const val K_ACCENT = "accent_color"
        const val K_THEME_MODE = "theme_mode"
    }
}
