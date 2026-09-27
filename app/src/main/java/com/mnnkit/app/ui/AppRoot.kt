package com.mnnkit.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mnnkit.app.data.api.OpenAiCompatibleClient
import com.mnnkit.app.ui.screens.ModelPickerDialog
import com.mnnkit.app.speech.SpeechRouter
import com.mnnkit.app.speech.SpeechAudio
import com.mnnkit.app.speech.AudioIo
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.input.clearText
import com.mnnkit.app.ui.theme.MnnSpacing
import androidx.compose.foundation.layout.padding
import com.mnnkit.app.AppContainer
import com.mnnkit.app.ui.glass.LocalGlassBackdrop
import com.mnnkit.app.ui.glass.rememberGlassBackdrop
import com.mnnkit.app.ui.screens.ChatMessageUi
import com.mnnkit.app.ui.screens.ChatScreen
import com.mnnkit.app.ui.screens.ChatUiState
import com.mnnkit.app.ui.screens.FeatureScreen
import com.mnnkit.app.ui.screens.ImportUiState
import com.mnnkit.app.ui.screens.McpScreen
import com.mnnkit.app.ui.screens.SkillsScreen
import com.mnnkit.app.ui.screens.MemoryScreen
import com.mnnkit.app.ui.screens.GlassInputBar
import com.mnnkit.app.ui.screens.ModelsScreen
import com.mnnkit.app.ui.screens.SettingsScreen
import com.mnnkit.app.ui.screens.VoiceScreen
import com.mnnkit.app.ui.theme.MnnAccentColor
import com.mnnkit.app.ui.theme.MnnTheme
import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.model.ModelStatus
import com.mnnkit.app.util.MediaExport
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用根组合。
 *
 * 采集层（backdrop）的建立与「背景 + 内容」的摆放都在 [AppShell] 里，
 * 这里只负责业务状态与页面分发。屏幕通过 [LocalGlassBackdrop] 取采集源。
 */
@Composable
fun AppRoot(container: AppContainer, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    // ---------------- 对话状态 ----------------
    var messages by remember { mutableStateOf(listOf<ChatMessageUi>()) }
    var generating by remember { mutableStateOf(false) }
    var chatError by remember { mutableStateOf<String?>(null) }
    var memoryNotice by remember { mutableStateOf<String?>(null) }
    var generateJob by remember { mutableStateOf<Job?>(null) }

    val modelState by container.modelManager.state.collectAsState()
    val skillState by container.skillManager.state.collectAsState()
    val mcpState by container.mcpManager.state.collectAsState()
    val settingsState by container.settings.state.collectAsState()

    // Android Context。用 LocalContext 拿的是 Activity context（能弹权限框）。
    // 必须在任何使用它的局部函数**之前**声明 —— 否则 Kotlin 会把它当成
    // 后面某个 lambda 的参数，报「Function invocation 'context(...)' expected」。
    val appContext = androidx.compose.ui.platform.LocalContext.current

    // 已加载的模型。
    //
    // ⚠️ 不能直接写 `val loadedModel = container.llmEngine.loadedModel` ——
    // 那是引擎里的**普通可变字段**，不是 Compose State，改它**不会触发重组**。
    // 症状：加载了第二个模型后界面仍显示第一个，看起来像「切换失败」，
    // 其实原生侧已经换好了，只是 UI 停在旧值上。
    //
    // 这里用一份 Compose 自己的镜像，加载/卸载后显式同步。
    var loadedModel by remember {
        mutableStateOf(container.llmEngine.loadedModel)
    }
    /** 把引擎的真实状态同步到 UI。任何改动模型的地方都要调一次。 */
    fun syncLoadedModel() {
        loadedModel = container.llmEngine.loadedModel
    }

    // API 提供商：列表、提示、连接测试状态
    val apiNotice by container.apiProviders.notice.collectAsState()
    var apiTestingId by remember { mutableStateOf<String?>(null) }
    var apiTestResult by remember { mutableStateOf<String?>(null) }

    // API 提供商列表（供「模型」面板与设置页使用）
    val apiState by container.apiProviders.state.collectAsState()

    // 生效中的模型名：本地模型优先，其次 API 提供商。
    val activeModelLabel = loadedModel?.displayName ?: container.apiProviders.activeLabel()

    // 本地是否已有可用的 TTS 模型（决定「朗读」按钮是否出现）
    val ttsModelReady = remember(modelState.models) {
        modelState.models.any { it.kind == ModelKind.TTS && it.status == ModelStatus.READY }
    }

    // 本地是否已有可用的 STT 模型
    val sttModelReady = remember(modelState.models) {
        modelState.models.any { it.kind == ModelKind.STT && it.status == ModelStatus.READY }
    }

    // ── 语音：录音 → 转写 ──
    var recording by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var voiceNotice by remember { mutableStateOf<String?>(null) }
    val recordedPcm = remember { mutableListOf<FloatArray>() }
    var recordJob by remember { mutableStateOf<Job?>(null) }

    /**
     * 开始录音。
     *
     * 边录边把 PCM 块攒起来 —— Sherpa 的流式识别需要模型已加载并做增量解码，
     * 而这里要支持「录一段再转」的一次性语义（也更省电）。
     * 转写时优先走 API（无需本地模型），否则用本地引擎。
     */
    fun startRecording() {
        if (recording) return
        runCatching { AudioIo.requireRecordPermission(appContext) }
            .onFailure { voiceNotice = it.message; return }

        recordedPcm.clear()
        transcript = ""
        voiceNotice = null
        recording = true

        recordJob = scope.launch {
            runCatching {
                AudioIo.record(appContext).collect { chunk -> recordedPcm.add(chunk) }
            }.onFailure {
                voiceNotice = "录音失败：${it.message}"
                recording = false
            }
        }
    }

    /** 停止录音并转写。 */
    fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
        recording = false

        val pcm = if (recordedPcm.isEmpty()) {
            FloatArray(0)
        } else {
            FloatArray(recordedPcm.sumOf { it.size }).also { out ->
                var o = 0
                recordedPcm.forEach { c -> c.copyInto(out, o); o += c.size }
            }
        }
        recordedPcm.clear()

        if (pcm.isEmpty()) {
            voiceNotice = "没有录到音频"
            return
        }

        scope.launch {
            voiceNotice = "转写中…"
            val rate = AudioIo.DEFAULT_SAMPLE_RATE
            val api = container.apiProviders.active()?.takeIf { it.canAsr }
            runCatching {
                if (api != null) {
                    // API 路径：先落一个 wav 文件（端点要求上传文件），再转写
                    val wav = File(
                        File(container.storage.root, "audio").apply { mkdirs() },
                        "asr-${System.currentTimeMillis()}.wav",
                    )
                    SpeechRouter.writeWav(wav, SpeechAudio.floatToPcm16(pcm), rate)
                    container.apiProviders.transcribe(api, wav).also { wav.delete() }
                } else {
                    // 本地路径：确保 STT 模型已加载
                    val model = container.sttEngine.loadedModel
                        ?: modelState.models.firstOrNull {
                            it.kind == ModelKind.STT && it.status == ModelStatus.READY
                        }?.also { container.sttEngine.load(it) }
                        ?: throw IllegalStateException(
                            "没有可用的语音识别：既没配置带 ASR 的 API，也没有本地 STT 模型。",
                        )
                    container.sttEngine.transcribe(pcm, rate)
                }
            }.onSuccess {
                transcript = it.trim()
                voiceNotice = if (transcript.isEmpty()) "没识别出内容" else null
            }.onFailure {
                voiceNotice = "转写失败：${it.message}"
            }
        }
    }

    // 模型 / API 选择面板
    var showModelPicker by remember { mutableStateOf(false) }
    val installedLlm = remember(modelState.models) {
        modelState.models.filter { it.kind == ModelKind.LLM && it.status == ModelStatus.READY }
    }

    // ---------------- 记忆状态 ----------------
    var memoryEntries by remember { mutableStateOf(container.memoryManager.list()) }
    var memoryCounts by remember { mutableStateOf(container.memoryManager.countByKind()) }
    var exportedPath by remember { mutableStateOf<String?>(null) }

    fun reloadMemory() {
        memoryEntries = container.memoryManager.list()
        memoryCounts = container.memoryManager.countByKind()
    }

    // ---------------- 链接导入状态 ----------------
    var importState by remember { mutableStateOf(ImportUiState()) }

    // ---------------- 附件（选择文件）----------------
    // 用 SAF 的 OpenDocument，不需要任何存储权限，也不需要 FileProvider。
    // 输入框文本状态也在这里持有 —— 输入区要在 AppShell 的 glassOverlay 插槽里画。
    val chatInput = androidx.compose.foundation.text.input.rememberTextFieldState()
    // 当前页由这里持有：glassOverlay 要知道现在是不是对话页
    var topTab by remember { mutableStateOf(TopTab.Chat) }
    var attachedName by remember { mutableStateOf<String?>(null) }
    var attachedText by remember { mutableStateOf<String?>(null) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val name = appContext.contentResolver
                .query(uri, null, null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
                ?: uri.lastPathSegment
                ?: "已选文件"
            val text = appContext.contentResolver.openInputStream(uri)?.use { ins ->
                ins.readBytes().toString(Charsets.UTF_8)
            }.orEmpty()
            attachedName = name
            // 只把文本类内容接进上下文；二进制会解出乱码，索性截断
            attachedText = text.take(20_000)
        }.onFailure {
            chatError = "读取文件失败：${it.message}"
        }
    }

    // ---------------- 发送消息 ----------------
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || generating) return

        // ⚠️ 门槛必须同时接受两条路径：本地模型 **或** API 提供商。
        //
        // 曾经只判 `llmEngine.loadedModel == null`。而「切到 API」时
        // 代码会 `unload()` 本地模型以释放那几百 MB 内存 ——
        // 于是 `loadedModel` 变 null，这里直接 return，
        // 消息**根本没发出去**，界面只留一句「请先加载一个已安装的模型」。
        // 表现就是「选了 API 但文字发不出去」。
        val hasLocal = container.llmEngine.loadedModel != null
        val hasApi = container.apiProviders.active()?.canChat == true
        if (!hasLocal && !hasApi) {
            chatError = "请先选择一个模型：本机模型或 API 云端模型（点「模型」按钮）"
            return
        }
        chatError = null
        // 附件内容作为一轮性的上下文前缀，跟这条消息一起发给模型
        val outgoing = attachedText?.let { "[已附加文件：${attachedName ?: "文件"}]\n$it\n\n$trimmed" }
            ?: trimmed
        messages = messages + ChatMessageUi("user", trimmed)
        // 发送后清掉附件，避免每条消息都重复带上
        attachedName = null
        attachedText = null

        generateJob = scope.launch {
            generating = true

            // 0) 组装 system prompt：基础提示 + 已启用的 Skill 索引 + 已连接 MCP 的工具清单。
            //
            // 两处都刻意用「渐进式披露」：Skill 只给名称+描述（正文按需展开），
            // MCP 只给工具名+描述+参数 schema。端侧小模型的上下文很窄，
            // 把所有 Skill 正文和工具全量塞进去会直接挤爆。
            val systemPrompt = buildString {
                append(settingsState.systemPrompt)
                if (settingsState.skillsEnabled) {
                    com.mnnkit.app.data.skill.SkillPromptBuilder
                        .buildIndexPrompt(container.skillManager.enabledSkills())
                        ?.let { append("\n\n").append(it) }
                }
                if (settingsState.mcpEnabled) {
                    container.mcpManager.buildToolsPrompt()
                        ?.let { append("\n\n").append(it) }
                }
            }

            val config = GenerationConfig(
                maxNewTokens = settingsState.maxNewTokens,
                temperature = settingsState.temperature.toDouble(),
                topP = settingsState.topP.toDouble(),
                systemPrompt = systemPrompt,
            )

            // 1) 检索记忆并注入上下文
            val memCtx = container.memoryManager.buildMemoryContext(outgoing, container.llmEngine)
            val injected = container.memoryManager.lastInjected()
            memoryNotice = if (injected.isEmpty()) null else "本轮参考了 ${injected.size} 条记忆"

            // 2) 组装消息序列
            // 最后一条（就是刚发出去的这条）要用 outgoing —— 它可能带上了附件内容。
            // UI 上仍显示用户原本输入的 trimmed，附件正文不占屏幕。
            val history = messages.mapIndexed { i, m ->
                val text = if (i == messages.lastIndex && m.role == "user") outgoing else m.text
                ChatMessage(ChatMessage.Role.fromWire(m.role), text)
            }
            val full = container.memoryManager.withMemoryContext(history, memCtx, config.systemPrompt)

            // 3) 流式生成
            //
            // 两条路径二选一：
            //   * 选了 API 提供商、且它配了对话模型 → 走云端 / 局域网 API；
            //   * 否则走本地 MNN 引擎。
            //
            // 关键：API 的 messages **从同一份 `history` 映射**，不另起一套组装逻辑。
            // 否则会出现「切到 API 后模型看不到记忆 / 看不到 Skill 索引」这种偏差 ——
            // 那种 bug 很难查，因为界面看起来一切正常。
            val acc = StringBuilder()
            // 思考过程单独累积：它是**另一条流**（delta.reasoning_content），
            // 不能拼进 acc —— 否则最终回答里会夹进整段「让我想想…」。
            val think = StringBuilder()
            messages = messages + ChatMessageUi("assistant", "")
            val apiForChat = container.apiProviders.active()?.takeIf { it.canChat }

            // 生成路径的诊断日志。排查「界面显示 A、实际用 B」这类问题时，
            // 这是唯一能一锤定音的证据 —— 不要靠界面文字判断用了哪个模型。
            android.util.Log.i(
                "LocalAI-Gen",
                "生成开始：路径=" + (if (apiForChat != null) "API(${apiForChat.name})" else "本地") +
                    "；引擎 loadedModel=" + (container.llmEngine.loadedModel?.id ?: "null") +
                    "；localPath=" + (container.llmEngine.loadedModel?.localPath ?: "null") +
                    "；消息数=" + history.size,
            )
            try {
                if (apiForChat != null) {
                    container.apiProviders.chatStream(
                        provider = apiForChat,
                        messages = history.map { it.role.wire to it.content },
                        temperature = config.temperature,
                        topP = config.topP,
                        maxTokens = config.maxNewTokens,
                        systemPrompt = config.systemPrompt,
                        // 思考强度按官方 `thinking.reasoning_effort` 传。
                        // 默认 OFF（= none）—— 官方默认是 enabled/high，
                        // 那会让推理过程吃满 max_tokens、正文为空。
                        thinking = OpenAiCompatibleClient.ThinkingEffort.fromId(
                            settingsState.thinkingEffort,
                        ),
                    ).collect { delta ->
                        // 两条流分开累积，界面按 [ChatMessageUi.reasoning] 折叠展示。
                        when (delta) {
                            is OpenAiCompatibleClient.ChatDelta.Content ->
                                acc.append(delta.text)

                            is OpenAiCompatibleClient.ChatDelta.Reasoning ->
                                think.append(delta.text)
                        }
                        messages = messages.dropLast(1) + ChatMessageUi(
                            role = "assistant",
                            text = acc.toString(),
                            reasoning = think.toString().ifBlank { null },
                        )
                    }
                } else {
                    container.llmEngine.stream(full, config).collect { token ->
                        acc.append(token)
                        messages = messages.dropLast(1) + ChatMessageUi("assistant", acc.toString())
                    }
                }
            } catch (e: Exception) {
                // ⚠️ `e.message` 可能是 null（例如 CancellationException、
                // 或某些 IOException）。直接把它拼进字符串会渲染出「失败：null」，
                // 用户看到的就是一个毫无信息量的 "null"。
                val why = e.message?.takeIf { it.isNotBlank() }
                    ?: e::class.java.simpleName
                chatError = "生成失败：$why"
                if (acc.isEmpty()) {
                    messages = messages.dropLast(1)
                }
            } finally {
                generating = false
            }

            // 流正常结束但正文一个字都没有。
            //
            // 分两种情况，提示不一样：
            //   * 有思考内容 ⇒ 推理模型把 `max_tokens` 全花在 reasoning 上了。
            //     气泡里已经折叠展示了思考，所以这里只补一句说明，不删消息。
            //   * 连思考也没有 ⇒ 服务端真的没返回任何内容。
            if (acc.isEmpty() && chatError == null) {
                val effort = OpenAiCompatibleClient.ThinkingEffort.fromId(
                    settingsState.thinkingEffort,
                )
                chatError = when {
                    think.isNotEmpty() -> "模型只产生了思考内容，正文被 max_tokens 截断。" +
                        "展开气泡里的「思考过程」可以看到它想了什么；" +
                        "把思考强度调成「关闭思考」或加大最大生成长度再试。" +
                        "当前档位：${effort.label}"

                    apiForChat != null -> "模型没有返回任何内容（思考与正文都是空的）。" +
                        "当前档位：${effort.label}"

                    else -> "模型没有返回内容。"
                }
            }

            // 4) 自动写入记忆
            if (acc.isNotEmpty()) {
                container.memoryManager.autoExtractFromTurn(trimmed, acc.toString(), container.llmEngine)
                reloadMemory()
            }
        }
    }

    MnnTheme(
        accentColor = MnnAccentColor.fromId(settingsState.accentColor),
        darkTheme = when (settingsState.themeMode) {
            "light" -> false
            "dark" -> true
            else -> null   // null = 跟随系统
        },
    ) {
        AppShell(
            llmAvailable = container.llmEngine.isAvailable,
            modelCount = modelState.models.size,
            installedCount = modelState.models.count { it.status == ModelStatus.READY },
            memoryCount = memoryEntries.size,
            skillCount = skillState.installed.size,
            mcpCount = mcpState.connectedCount,
            tab = topTab,
            onTabChange = { topTab = it },
            // 玻璃浮层：输入区。它必须在采集层之外，所以走这个插槽，
            // 而不是画在 ChatScreen 里（那样会自引用崩溃）。
            glassOverlay = { overlayBackdrop ->
                if (topTab == TopTab.Chat) {
                    GlassInputBar(
                        input = chatInput,
                        generating = generating,
                        canSend = !generating &&
                            (container.llmEngine.loadedModel != null ||
                                container.apiProviders.active()?.canChat == true),
                        attachedFileName = attachedName,
                        onSend = {
                            send(chatInput.text.toString())
                            chatInput.clearText()
                        },
                        onStop = {
                            generateJob?.cancel()
                            generateJob = null
                            generating = false
                        },
                        onPickFile = {
                            filePicker.launch(
                                arrayOf("text/*", "application/json", "application/pdf", "*/*")
                            )
                        },
                        onClearAttachment = {
                            attachedName = null
                            attachedText = null
                        },
                        backdrop = overlayBackdrop,
                        // 思考强度：一个值同时驱动本地模型（enable_thinking）
                        // 与 API（thinking.reasoning_effort），见 ThinkingEffort。
                        thinking = OpenAiCompatibleClient.ThinkingEffort.fromId(
                            settingsState.thinkingEffort,
                        ),
                        onThinkingChange = { e ->
                            container.settings.update { it.copy(thinkingEffort = e.id ?: "none") }
                        },
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .padding(
                                start = MnnSpacing.page,
                                end = MnnSpacing.page,
                            ),
                    )
                }
            },
        ) { page ->
            val backdrop = LocalGlassBackdrop.current ?: rememberGlassBackdrop()

            when (page) {
                TopTab.Chat -> ChatScreen(
                    backdrop = backdrop,
                    state = ChatUiState(
                        messages = messages,
                        generating = generating,
                        loadedModelName = loadedModel?.displayName,
                        loadedModelPath = loadedModel?.localPath,
                        error = chatError ?: if (
                            // API 模式下不依赖本地库；只有走本地推理时才需要检查
                            container.apiProviders.active()?.canChat != true &&
                            !container.llmEngine.isAvailable
                        ) {
                            "MNN 推理库未加载，无法生成。请确认设备为 arm64-v8a，" +
                                "或在「模型」面板里配置一个 API 提供商。"
                        } else {
                            null
                        },
                        memoryNotice = memoryNotice,
                        // 选了能对话的 API 也算「可发送」—— 否则切到 API（本地模型已被 unload 释放内存）
                        // 会让发送按钮永久灰掉，表现就是「选了 API 发不出去」。
                        apiChatReady = apiState.active?.canChat == true,
                    ),
                    onSend = ::send,
                    onStop = {
                        generateJob?.cancel()
                        generateJob = null
                        generating = false
                    },
                    onReset = {
                        scope.launch { container.llmEngine.resetContext() }
                        messages = emptyList()
                        memoryNotice = null
                    },
                    onSpeak = { text ->
                        // 朗读：合成 → 播放 → 把音频路径写回该条消息，让「下载音频」出现。
                        scope.launch {
                            chatError = null
                            runCatching {
                                val spoken = container.speechRouter.speak(
                                    text = text,
                                    localEngine = { container.ttsEngine },
                                    speed = 1.0f,
                                )
                                // 找到这条助手消息，挂上音频路径
                                val idx = messages.indexOfLast {
                                    it.role == "assistant" && it.text == text
                                }
                                if (idx >= 0) {
                                    messages = messages.toMutableList().also {
                                        it[idx] = it[idx].copy(audioPath = spoken.file.absolutePath)
                                    }
                                }
                                container.speechRouter.play(spoken, scope)
                                memoryNotice = "已朗读（${spoken.format.uppercase()}，" +
                                    "${spoken.file.length() / 1024} KB）"
                            }.onFailure {
                                chatError = "朗读失败：${it.message}"
                            }
                        }
                    },
                    onDownloadImage = { path ->
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                MediaExport.saveImage(
                                    context = appContext,
                                    source = File(path),
                                    displayName = "LocalAI-${System.currentTimeMillis()}.png",
                                )
                            }
                            chatError = if (r.ok) null else r.message
                            if (r.ok) memoryNotice = r.message
                        }
                    },
                    onDownloadAudio = { path ->
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                MediaExport.saveAudio(
                                    context = appContext,
                                    source = File(path),
                                    displayName = "LocalAI-${System.currentTimeMillis()}",
                                )
                            }
                            chatError = if (r.ok) null else r.message
                            if (r.ok) memoryNotice = r.message
                        }
                    },
                    onPickFile = {},
                    attachedFileName = attachedName,
                    onClearAttachment = {},
                    input = chatInput,
                    onOpenModelPicker = { showModelPicker = true },
                    activeModelLabel = activeModelLabel,
                    ttsAvailable = apiState.active?.canTts == true || ttsModelReady,
                )

                TopTab.Models -> ModelsScreen(
                    backdrop = backdrop,
                    modelManager = container.modelManager,
                    importState = importState,
                    onImportLink = { link, kind ->
                        scope.launch {
                            importState = ImportUiState(busy = true)
                            val hint = container.modelManager.describeMirror(link)
                            val result = container.modelManager.importFromLink(link, kind)
                            importState = result.fold(
                                onSuccess = { item ->
                                    container.modelManager.install(item)
                                    ImportUiState(
                                        busy = false,
                                        message = "已识别「${item.displayName}」，开始下载安装。",
                                        mirrorHint = hint,
                                    )
                                },
                                onFailure = { e ->
                                    ImportUiState(
                                        busy = false,
                                        message = e.message ?: "识别失败",
                                        error = true,
                                        mirrorHint = hint,
                                    )
                                },
                            )
                        }
                    },
                    onRefresh = {
                        scope.launch { container.modelManager.refresh(force = true) }
                    },
                    onSwitchSource = { src ->
                        scope.launch { container.modelManager.switchSource(src) }
                    },
                )

                TopTab.Voice -> VoiceScreen(
                    backdrop = backdrop,
                    modelManager = container.modelManager,
                    // STT 可用：配了带 ASR 的 API，或本地装了 STT 模型
                    sttReady = apiState.active?.canAsr == true || sttModelReady,
                    // TTS 可用：配了带 TTS 的 API，或本地装了 TTS 模型
                    ttsReady = apiState.active?.canTts == true || ttsModelReady,
                    recording = recording,
                    transcript = transcript,
                    onStartRecording = { startRecording() },
                    onStopRecording = { stopRecording() },
                    onSpeak = { text ->
                        scope.launch {
                            voiceNotice = null
                            runCatching {
                                val spoken = container.speechRouter.speak(
                                    text = text,
                                    localEngine = { container.ttsEngine },
                                )
                                voiceNotice = "已合成 ${spoken.format.uppercase()}：" +
                                    "${spoken.file.name}（${spoken.file.length() / 1024} KB）"
                                container.speechRouter.play(spoken, scope)
                            }.onFailure { voiceNotice = "合成失败：${it.message}" }
                        }
                    },
                )

                TopTab.Skills -> SkillsScreen(
                    backdrop = backdrop,
                    skillManager = container.skillManager,
                )

                TopTab.Mcp -> McpScreen(
                    backdrop = backdrop,
                    mcpManager = container.mcpManager,
                )

                TopTab.Memory -> MemoryScreen(
                    backdrop = backdrop,
                    entries = memoryEntries,
                    counts = memoryCounts,
                    onAdd = { content, kind ->
                        scope.launch {
                            container.memoryManager.remember(
                                content = content,
                                kind = kind,
                                source = "manual",
                                engine = container.llmEngine,
                            )
                            reloadMemory()
                        }
                    },
                    onDelete = { id ->
                        container.memoryManager.delete(id)
                        reloadMemory()
                    },
                    onClearAll = {
                        container.memoryManager.clear()
                        reloadMemory()
                    },
                    onExport = {
                        scope.launch {
                            val f = java.io.File(
                                container.storage.root,
                                "memory-export-${System.currentTimeMillis()}.json"
                            )
                            runCatching {
                                f.writeText(container.memoryManager.exportJson())
                                exportedPath = f.absolutePath
                            }
                        }
                    },
                    exportedPath = exportedPath,
                    // ⚠️ 强调色控制器**不在这里** —— 它属于「设置 → 外观」。
                    // 曾经误放在记忆页，已移除。这里只保留记忆相关的入参。
                )

                TopTab.Settings -> SettingsScreen(
                    backdrop = backdrop,
                    accentColor = MnnAccentColor.fromId(settingsState.accentColor),
                    onAccentColorChange = { accent ->
                        container.settings.update { it.copy(accentColor = accent.name) }
                    },
                    themeMode = settingsState.themeMode,
                    onThemeModeChange = { mode ->
                        container.settings.update { it.copy(themeMode = mode) }
                    },
                    settings = settingsState,
                    onSettingsChange = { transform ->
                        container.settings.update(transform)
                    },
                    // ── API 接入 ──
                    apiProviders = apiState,
                    apiNotice = apiNotice,
                    apiTestingId = apiTestingId,
                    apiTestResult = apiTestResult,
                    apiPresets = remember { container.apiProviders.presets() },
                    onApiAdd = { container.apiProviders.add(container.apiProviders.newProvider()) },
                    onApiUpdate = { container.apiProviders.update(it) },
                    onApiRemove = { container.apiProviders.remove(it) },
                    onApiSetActive = { container.apiProviders.setActive(it) },
                    onApiTest = { provider ->
                        scope.launch {
                            apiTestingId = provider.id
                            apiTestResult = null
                            // 先把当前编辑内容落盘，否则测的是旧配置 —— 用户会以为「明明填对了却连不上」
                            container.apiProviders.update(provider)
                            apiTestResult = container.apiProviders.test(provider)
                            apiTestingId = null
                        }
                    },
                    onApiClearAll = { container.apiProviders.clearAll() },
                    onApiClearNotice = { container.apiProviders.clearNotice() },
                )
            }
        }

        // ── 模型 / API 选择面板 ──
        // 放在 when 之外、AppShell 之内：它是覆盖全屏的弹窗，
        // 不属于任何单个标签页；放在 here 也保证切换标签时不会重复组合。
        if (showModelPicker) {
            ModelPickerDialog(
                installedLlmModels = installedLlm,
                loadedModelPath = loadedModel?.localPath,
                providers = apiState,
                onPickLocal = { model ->
                    showModelPicker = false
                    scope.launch {
                        chatError = null
                        runCatching {
                            // 选了本地模型就把 API 切回「不用」，否则下次对话仍走 API
                            container.apiProviders.setActive(null)
                            // load() 内部会先 release 旧会话再 init 新模型，
                            // 所以**不需要**在这里手动 unload —— 多一次
                            // release/init 往返反而更容易出问题。
                            android.util.Log.i(
                                "LocalAI-Gen",
                                "请求加载本地模型：id=${model.id} name=${model.displayName} " +
                                    "path=${model.localPath}",
                            )
                            container.llmEngine.load(model, GenerationConfig())
                        }.onFailure {
                            android.util.Log.e("LocalAI-Gen", "加载失败", it)
                            chatError = "加载失败：${it.message}"
                        }
                        android.util.Log.i(
                            "LocalAI-Gen",
                            "加载后引擎 loadedModel=" +
                                (container.llmEngine.loadedModel?.id ?: "null"),
                        )
                        // 无论成败都要同步：失败时引擎里可能已经是 null（旧会话被释放了），
                        // 不同步的话界面会继续显示一个其实已经不存在的模型。
                        syncLoadedModel()
                    }
                },
                onPickApi = { provider ->
                    showModelPicker = false
                    container.apiProviders.setActive(provider.id)
                    // 切到 API 时释放本地模型占用的内存 —— 那几百 MB 这时候白占着
                    scope.launch {
                        runCatching { container.llmEngine.unload() }
                        syncLoadedModel()
                    }
                },
                onDisableApi = {
                    showModelPicker = false
                    container.apiProviders.setActive(null)
                },
                onDisableLocal = {
                    showModelPicker = false
                    scope.launch {
                        runCatching { container.llmEngine.unload() }
                        syncLoadedModel()
                    }
                },
                onManageApi = {
                    showModelPicker = false
                    // 直接跳到设置页，省得用户自己找
                    topTab = TopTab.Settings
                },
                onDismiss = { showModelPicker = false },
            )
        }
    }
}
