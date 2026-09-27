package com.mnnkit.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mnnkit.app.data.api.OpenAiCompatibleClient
import com.mnnkit.core.chat.ConversationStore
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
// 「新对话」确认弹窗用到的基础布局与组件。
// 注意 AppRoot 里其余 UI 都是拼各页的 Screen，所以这些是后加的。
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import com.mnnkit.app.ui.screens.MnnCapsuleButton
import com.mnnkit.app.ui.theme.MnnTextColor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mnnkit.app.ui.theme.MnnAccentColor
import com.mnnkit.app.ui.theme.MnnTheme
import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.chat.GenerationMetrics
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.model.ModelStatus
import com.mnnkit.app.util.MediaExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    var generationId by remember { mutableStateOf(0L) }
    var conversationEpoch by remember { mutableStateOf(0L) }
    var resetting by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }
    val conversationIo = remember { Mutex() }

    // ──────────────────────────── 对话历史持久化 ────────────────────────────
    //
    // 修的是「应用一退出上下文就没了」：消息原先只活在 remember 里，
    // 进程结束即丢。现在落到 files/chat/conversation.json（见 [ConversationStore]）。
    //
    // ⚠️ 三个容易踩的点：
    //  1. **不能"恢复完再挂保存"**。如果先 await 恢复、再 collect 保存，
    //     那期间用户若已经发了消息，恢复完成后的赋值会把新消息冲掉。
    //     这里用 restoring 标志解决：恢复时**把已有的新消息接在后面**。
    //  2. **保存要防抖**。流式生成每个 token 都改 messages，逐次写盘会
    //     在 IO 线程上写上百次文件。debounce 300ms + 只保留最新一次。
    //  3. **别存残缺回复**。生成中途退出会留下空气泡，
    //     ConversationStore.isPersistable 会把它过滤掉。
    var lastConversationAt by remember { mutableStateOf(0L) }
    var activeConversationId by remember { mutableStateOf(java.util.UUID.randomUUID().toString() + ".json") }
    var restoring by remember { mutableStateOf(true) }

    var confirmNewChat by remember { mutableStateOf(false) }
    var showConversationHistory by remember { mutableStateOf(false) }
    var conversationHistory by remember { mutableStateOf(emptyList<com.mnnkit.core.chat.ConversationArchiveStore.Entry>()) }

    fun storedMessages(items: List<ChatMessageUi>) = items.map { m ->
        ConversationStore.StoredMessage(m.role, m.text, m.reasoning, m.imagePath, m.audioPath, m.generationMetrics)
    }
    fun displayMessages(items: List<ConversationStore.StoredMessage>) = items.map { m ->
        ChatMessageUi(role = m.role, text = m.text, reasoning = m.reasoning, imagePath = m.imagePath, audioPath = m.audioPath, generationMetrics = m.generationMetrics)
    }

    /** Archive the current turn before changing the active snapshot. Never discard it on I/O failure. */
    fun changeConversation(targetId: String? = null) {
        if (restoring || resetting) return
        if (targetId != null && targetId == activeConversationId) {
            showConversationHistory = false
            return
        }
        val previous = generateJob
        val previousReset = resetJob
        generationId++
        previous?.cancel()
        resetting = true
        resetJob = scope.launch {
            try {
                previousReset?.join()
                previous?.cancelAndJoin()
                generateJob = null
                generating = false
                val current = storedMessages(messages).filter { it.isPersistable }
                val restored = conversationIo.withLock {
                    withContext(Dispatchers.IO) {
                        val target = targetId?.let { container.conversationArchiveStore.load(it)
                            ?: error("找不到这条历史对话") }
                        if (current.isNotEmpty()) {
                            check(container.conversationArchiveStore.archive(current, activeConversationId) != null) { "对话归档失败；原对话仍保留" }
                        }
                        val ok = if (target == null) container.conversationStore.clear()
                            else container.conversationStore.save(target.messages, targetId)
                        check(ok) { "写入当前对话失败；原对话仍保留" }
                        // Keep the reopened conversation in history: opening is not a one-time restore.
                        target
                    }
                }
                conversationEpoch++
                messages = displayMessages(restored?.messages.orEmpty())
                activeConversationId = targetId ?: java.util.UUID.randomUUID().toString() + ".json"
                lastConversationAt = restored?.updatedAt ?: 0L
                chatError = null
                memoryNotice = null
                generateJob = null
                generating = false
                showConversationHistory = false
                container.llmEngine.resetContext()
                conversationHistory = withContext(Dispatchers.IO) { container.conversationArchiveStore.list() }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { chatError = e.message ?: "切换对话失败，原对话仍保留" }
            finally { resetting = false }
        }
    }

    LaunchedEffect(Unit) {
        val epoch = conversationEpoch
        val snap = conversationIo.withLock {
            withContext(Dispatchers.IO) { container.conversationStore.load() }
        }
        if (conversationEpoch != epoch) {
            restoring = false
            return@LaunchedEffect
        }
        val restored = snap.messages.map { m ->
            ChatMessageUi(
                role = m.role, text = m.text, reasoning = m.reasoning,
                imagePath = m.imagePath, audioPath = m.audioPath,
                generationMetrics = m.generationMetrics,
            )
        }
        if (restored.isNotEmpty()) {
            messages = restored
            activeConversationId = snap.conversationId?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}\\.json")) }
                ?: java.util.UUID.randomUUID().toString() + ".json"
            lastConversationAt = snap.updatedAt
        }
        snap.error?.let { chatError = "上次的对话历史读取失败：$it" }
        restoring = false
        if (restored.isNotEmpty()) {
            val notice = "已恢复上次的对话（${restored.count { it.role == "user" }} 轮）"
            memoryNotice = notice
            delay(4000)
            if (conversationEpoch == epoch && memoryNotice == notice) memoryNotice = null
        }
    }

    LaunchedEffect(messages, restoring, conversationEpoch, generating) {
        if (restoring) return@LaunchedEffect
        // Do not erase a corrupt startup file; only an explicit new chat clears it.
        if (messages.isEmpty() && conversationEpoch == 0L) return@LaunchedEffect
        val snapshot = messages
        if (snapshot.isNotEmpty()) delay(300)
        val toStore = snapshot.map { m ->
            ConversationStore.StoredMessage(
                role = m.role, text = m.text, reasoning = m.reasoning,
                imagePath = m.imagePath, audioPath = m.audioPath,
                generationMetrics = m.generationMetrics,
            )
        }
        val ok = conversationIo.withLock {
            withContext(Dispatchers.IO) {
                ensureActive()
                if (snapshot.isEmpty()) container.conversationStore.clear()
                else {
                    val saved = container.conversationStore.save(toStore, activeConversationId)
                    if (saved && !generating && toStore.any { it.isPersistable }) {
                        container.conversationArchiveStore.archive(toStore, activeConversationId) != null
                    } else saved
                }
            }
        }
        if (!ok) android.util.Log.w("LocalAI-Gen", "对话历史写入失败（共 ${toStore.size} 条）")
    }
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
    val activeModelLabel = if (apiState.active?.canChat == true) {
        container.apiProviders.activeLabel()
    } else loadedModel?.displayName

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
        if (trimmed.isEmpty() || generating || restoring || resetting) return
        val apiForChat = container.apiProviders.active()?.takeIf { it.canChat }
        if (container.llmEngine.loadedModel == null && apiForChat == null) {
            chatError = "请先选择一个模型：本机模型或 API 云端模型（点「模型」按钮）"
            return
        }
        val settings = settingsState
        chatError = null
        val outgoing = attachedText?.let { "[已附加文件：${attachedName ?: "文件"}]\n$it\n\n$trimmed" }
            ?: trimmed
        val turnHistory = messages + ChatMessageUi("user", trimmed)
        val assistantIndex = turnHistory.size
        messages = turnHistory + ChatMessageUi("assistant", "", isStreaming = true)
        attachedName = null
        attachedText = null
        generating = true // Set before launch, so two rapid sends cannot enter.
        val id = ++generationId
        val epoch = conversationEpoch
        fun ownsTurn() = generationId == id && conversationEpoch == epoch
        fun updateAssistant(text: String, reasoning: String?, metrics: GenerationMetrics? = null) {
            if (!ownsTurn() || assistantIndex !in messages.indices) return
            messages = messages.toMutableList().also {
                it[assistantIndex] = it[assistantIndex].copy(
                    text = text, reasoning = reasoning, generationMetrics = metrics,
                )
            }
        }

        generateJob = scope.launch {
            val acc = StringBuilder()
            val think = StringBuilder()
            var completed = false
            var finalMetrics: GenerationMetrics? = null
            try {
                val systemPrompt = buildString {
                    append(settings.systemPrompt)
                    if (settings.skillsEnabled) {
                        com.mnnkit.app.data.skill.SkillPromptBuilder
                            .buildIndexPrompt(container.skillManager.enabledSkills())
                            ?.let { append("\n\n").append(it) }
                    }
                    if (settings.mcpEnabled) {
                        container.mcpManager.buildToolsPrompt()?.let { append("\n\n").append(it) }
                    }
                }
                val config = GenerationConfig(
                    maxNewTokens = settings.maxNewTokens, temperature = settings.temperature.toDouble(),
                    topP = settings.topP.toDouble(), systemPrompt = systemPrompt,
                )
                val memCtx = container.memoryManager.buildMemoryContext(outgoing, container.llmEngine)
                ensureActive()
                if (!ownsTurn()) return@launch
                val injected = container.memoryManager.lastInjected()
                memoryNotice = if (injected.isEmpty()) null else "本轮参考了 ${injected.size} 条记忆"
                val history = turnHistory.mapIndexed { i, m ->
                    ChatMessage(ChatMessage.Role.fromWire(m.role), if (i == turnHistory.lastIndex) outgoing else m.text)
                }
                val full = container.memoryManager.withMemoryContext(history, memCtx, config.systemPrompt)
                android.util.Log.i("LocalAI-Gen", "生成开始：路径=${if (apiForChat != null) "API" else "本地"}；消息数=${history.size}")
                if (apiForChat != null) {
                    container.apiProviders.chatStream(
                        provider = apiForChat,
                        messages = full.map { it.role.wire to it.content },
                        temperature = config.temperature, topP = config.topP, maxTokens = config.maxNewTokens,
                        // full already includes the system prompt and retrieved memory, as on local.
                        systemPrompt = null,
                        thinking = OpenAiCompatibleClient.ThinkingEffort.fromId(settings.thinkingEffort),
                    ).collect { delta ->
                        ensureActive()
                        when (delta) {
                            is OpenAiCompatibleClient.ChatDelta.Content -> acc.append(delta.text)
                            is OpenAiCompatibleClient.ChatDelta.Reasoning -> think.append(delta.text)
                            is OpenAiCompatibleClient.ChatDelta.Metrics -> finalMetrics = delta.metrics
                        }
                        updateAssistant(acc.toString(), think.toString().ifBlank { null })
                    }
                } else {
                    container.llmEngine.stream(full, config).collect { chunk ->
                        ensureActive()
                        acc.append(chunk)
                        updateAssistant(acc.toString(), null)
                    }
                    finalMetrics = container.llmEngine.lastGenerationMetrics
                }
                ensureActive()
                completed = true
                updateAssistant(acc.toString(), think.toString().ifBlank { null }, finalMetrics)
                if (ownsTurn() && acc.isEmpty()) {
                    chatError = if (think.isNotEmpty()) {
                        "模型只产生了思考内容，正文可能被 max_tokens 截断。可展开思考过程、关闭思考或加大最大生成长度。"
                    } else "模型没有返回内容。"
                }
                if (ownsTurn() && acc.isNotEmpty()) {
                    container.memoryManager.autoExtractFromTurn(trimmed, acc.toString(), container.llmEngine)
                    ensureActive()
                    if (ownsTurn()) reloadMemory()
                }
            } catch (e: CancellationException) {
                // User stop is not a failed request, and never edits a newer turn.
                throw e
            } catch (e: Exception) {
                if (ownsTurn()) {
                    chatError = if (completed) "回复已完成，记忆提取失败：${e.message}"
                        else "生成失败：${e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName}"
                }
            } finally {
                if (ownsTurn()) {
                    if (assistantIndex in messages.indices) {
                        messages = messages.toMutableList().also {
                            val msg = it[assistantIndex]
                            if (msg.text.isBlank() && msg.reasoning.isNullOrBlank()) it.removeAt(assistantIndex)
                            else it[assistantIndex] = msg.copy(isStreaming = false)
                        }
                    }
                    generating = false
                    generateJob = null
                }
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
            // 顶栏右上角「新对话」。**先弹确认框**，不直接清 ——
            // 清空会同时删掉磁盘上的历史（不可撤销），一个误触就没了。
            onNewChat = { confirmNewChat = true },
            onChatHistory = {
                scope.launch {
                    conversationHistory = conversationIo.withLock {
                        withContext(Dispatchers.IO) { container.conversationArchiveStore.list() }
                    }
                    showConversationHistory = true
                }
            },
            hasChat = messages.isNotEmpty(),
            // 玻璃浮层：输入区。它必须在采集层之外，所以走这个插槽，
            // 而不是画在 ChatScreen 里（那样会自引用崩溃）。
            glassOverlay = { overlayBackdrop ->
                if (topTab == TopTab.Chat) {
                    GlassInputBar(
                        input = chatInput,
                        generating = generating,
                        canSend = !generating && !restoring && !resetting &&
                            (container.llmEngine.loadedModel != null ||
                                container.apiProviders.active()?.canChat == true),
                        attachedFileName = attachedName,
                        onSend = {
                            send(chatInput.text.toString())
                            chatInput.clearText()
                        },
                        onStop = {
                            generateJob?.cancel()
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
                        val oldBackend = container.settings.state.value.backendType
                        container.settings.update(transform)
                        if (oldBackend != container.settings.state.value.backendType && loadedModel != null) {
                            memoryNotice = "推理后端已保存；当前模型仍使用加载时的后端。请在「模型」中重新选择该模型以应用。"
                        }
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
                        if (loadedModel != null) {
                            val nativeEngine = container.llmEngine as? com.mnnkit.app.llm.MnnLlmEngine
                            val backend = nativeEngine?.loadedBackend
                            val expected = when (backend) {
                                "cpu" -> 0
                                "opencl" -> 3
                                "vulkan" -> 7
                                "npu" -> 5
                                else -> null
                            }
                            val reported = com.mnnkit.core.json.JsonParser
                                .parseOrNull(nativeEngine?.backendReport.orEmpty())
                                ?.arr("executor_runtime_backends")
                                ?.mapNotNull { it.asInt }.orEmpty()
                            memoryNotice = when {
                                expected == null -> "模型已加载；未识别的后端配置，请核对模型日志。"
                                expected !in reported -> "后端警告：请求 $backend，运行时未报告相应后端（$reported）；可能回退 CPU。"
                                backend != "cpu" -> "已创建 $backend 运行时；逐算子执行位置未验证，仍可能回退 CPU。"
                                else -> "CPU 运行时已创建；逐算子执行位置未验证。"
                            }
                        }
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

        // 新对话前先归档当前会话；历史可通过顶栏按钮再次打开。
        if (confirmNewChat) {
            NewChatConfirmDialog(
                onConfirm = {
                    confirmNewChat = false
                    changeConversation()
                },
                onDismiss = { confirmNewChat = false },
            )
        }
        if (showConversationHistory) {
            ConversationHistoryDialog(
                entries = conversationHistory,
                onOpen = { id -> changeConversation(id) },
                onDelete = { id ->
                    if (resetting || restoring) {
                        chatError = "正在切换对话，请稍后重试"
                    } else {
                        val deletingActive = id == activeConversationId
                        val oldJob = if (deletingActive) generateJob else null
                        if (deletingActive) { generationId++; oldJob?.cancel(); resetting = true }
                        scope.launch {
                            try {
                                oldJob?.cancelAndJoin()
                                val removed = conversationIo.withLock {
                                    withContext(Dispatchers.IO) {
                                        if (!deletingActive) container.conversationArchiveStore.delete(id)
                                        else if (!container.conversationStore.clear()) false
                                        else if (container.conversationArchiveStore.delete(id)) true
                                        else {
                                            container.conversationStore.save(storedMessages(messages), id)
                                            false
                                        }
                                    }
                                }
                                if (removed) {
                                    if (deletingActive) {
                                        conversationEpoch++
                                        messages = emptyList()
                                        activeConversationId = java.util.UUID.randomUUID().toString() + ".json"
                                        generateJob = null
                                        generating = false
                                        showConversationHistory = false
                                        container.llmEngine.resetContext()
                                    }
                                    conversationHistory = withContext(Dispatchers.IO) { container.conversationArchiveStore.list() }
                                } else chatError = "删除历史对话未完成，请重试"
                            } catch (e: Exception) { chatError = "删除历史对话失败：${e.message}" }
                            finally { if (deletingActive) resetting = false }
                        }
                    }
                },
                onDismiss = { showConversationHistory = false },
            )
        }
    }
}

/**
 * 「新对话」确认弹窗。
 *
 * 当前对话归档后可以从右上角历史按钮恢复。
 */
@Composable
private fun NewChatConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(MnnSpacing.card),
                verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
            ) {
                Text(
                    "开始新对话？",
                    style = MiuixTheme.textStyles.headline2,
                    color = MnnTextColor.primary,
                )
                Text(
                    "当前对话将自动保存到对话历史，随后开始空白对话。" +
                        "可随时点击右上角「历史」重新打开。",
                    style = MiuixTheme.textStyles.body2,
                    color = MnnTextColor.secondary,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        MnnSpacing.tight,
                        androidx.compose.ui.Alignment.End,
                    ),
                ) {
                    MnnCapsuleButton(text = "取消", onClick = onDismiss)
                    MnnCapsuleButton(
                        text = "归档并新建",
                        onClick = onConfirm,
                        emphasized = true,
                    )
                }
            }
        }
    }
}
