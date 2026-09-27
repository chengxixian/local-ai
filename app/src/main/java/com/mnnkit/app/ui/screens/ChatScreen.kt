package com.mnnkit.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import androidx.compose.material.icons.rounded.Tune
import com.mnnkit.app.data.api.OpenAiCompatibleClient
import com.mnnkit.app.ui.DockClearance
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnListItem
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.glass.LocalTopBarInset
import com.mnnkit.app.ui.glass.liquidGlass
import com.mnnkit.app.ui.staggeredEntry
import com.mnnkit.app.ui.theme.MnnRadii
import com.mnnkit.app.ui.theme.MnnSpacing
import com.mnnkit.app.ui.theme.MnnTextColor
import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.chat.GenerationMetrics
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 对话界面需要的渲染状态。 */
data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val generating: Boolean = false,
    val loadedModelName: String? = null,
    val loadedModelPath: String? = null,
    val error: String? = null,
    val memoryNotice: String? = null,
    /**
     * 是否已选中一个**能对话**的 API 提供商。
     *
     * ⚠️ 必须参与 [canSend] 的判断。曾经只看 `loadedModelPath != null`，
     * 而「切到 API」时会 `unload()` 本地模型以释放内存 ——
     * 结果 `loadedModelPath` 变 null，发送按钮**永久灰掉**，
     * 表现就是「选了 API 但发不出消息」。
     */
    val apiChatReady: Boolean = false,
) {
    val canSend: Boolean get() = !generating && (loadedModelPath != null || apiChatReady)
}

data class ChatMessageUi(
    val role: String,
    val text: String,
    val fromMemory: Boolean = false,
    /** 生成图或附件图的本地绝对路径；非 null 时气泡里显示缩略图，点击可放大。 */
    val imagePath: String? = null,
    /** 朗读产出的音频文件绝对路径，用于「下载音频」。 */
    val audioPath: String? = null,
    /**
     * 推理模型的思考过程（API 的 `delta.reasoning_content`）。
     *
     * **与 [text] 是两条独立的流**，不能拼在一起 —— 否则用户看到的「回答」
     * 里会夹进整段「让我想想…」的思考文字。界面上默认折叠，点标题可展开。
     */
    val reasoning: String? = null,
    val generationMetrics: GenerationMetrics? = null,
    /** Transient only: never restored as a still-running response. */
    val isStreaming: Boolean = false,
)

/** 输入区浮层的高度预留（列表底部要给它让位，否则最后一条消息被盖住）。 */
private val InputBarReservedHeight = 180.dp

/**
 * 对话界面。
 *
 *  - 消息列表（`LazyColumn`，在采集层里）；
 *  - 输入区由 `AppShell` 的 `glassOverlay` 插槽摆放 ——
 *    它需要玻璃，而玻璃必须在采集层之外，否则自引用会导致渲染树递归闪退。
 *
 * 每条气泡支持：复制文字、朗读、下载音频、下载图片、点图片放大。
 */
@Composable
fun ChatScreen(
    backdrop: LayerBackdrop,
    state: ChatUiState,
    /** 点「模型」按钮 —— 打开模型 / API 选择面板。 */
    onOpenModelPicker: () -> Unit,
    /** 当前生效的模型显示名（本地模型名或 API 提供商名）。 */
    activeModelLabel: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onSpeak: (String) -> Unit,
    onDownloadImage: (String) -> Unit,
    onDownloadAudio: (String) -> Unit,
    onPickFile: () -> Unit,
    attachedFileName: String?,
    onClearAttachment: () -> Unit,
    input: TextFieldState,
    ttsAvailable: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page,
        // 底部额外留出悬浮底栏的高度，滚到底时最后一项不被遮挡
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current

    // Scroll only when a new message arrives. Re-running for every streamed token
    // overrides the user's drag and snaps long answers back to their first line.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size) // model card occupies index 0
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                top = contentPadding.calculateTopPadding() + LocalTopBarInset.current,
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding() + InputBarReservedHeight,
            ),
            verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
        ) {
            item {
                Box(Modifier.staggeredEntry(0)) {
                    ActiveModelCard(
                        state = state,
                        onOpenModelPicker = onOpenModelPicker,
                        activeModelLabel = activeModelLabel,
                    )
                }
            }

            if (state.messages.isEmpty()) {
                item {
                    Box(Modifier.staggeredEntry(1)) {
                        MnnCard {
                            Text("开始对话", style = MiuixTheme.textStyles.title2)
                            Text(
                                "本机模型在设备上推理；API 模型会发送对话到所选服务。\n" +
                                    "记忆库里的事实会自动作为上下文注入（可在「记忆」页管理）。",
                                style = MiuixTheme.textStyles.body2,
                                color = MnnTextColor.secondary,
                            )
                        }
                    }
                }
            }

            items(state.messages) { msg ->
                MessageBubble(
                    msg = msg,
                    onSpeak = onSpeak,
                    ttsAvailable = ttsAvailable,
                    backdrop = backdrop,
                    onDownloadImage = onDownloadImage,
                    onDownloadAudio = onDownloadAudio,
                )
            }
        }
        // 输入区不在这里画 —— 它需要玻璃，由 AppShell 的 glassOverlay 插槽摆放。
    }
}

/**
 * 顶部「当前模型」卡：**只显示当前生效的模型 + 一个「模型」按钮**。
 *
 * 刻意**不**在卡里铺开已安装模型列表：
 *  - 列表一长就把对话挤到屏幕外（这是被明确反馈过的问题）；
 *  - 本地模型与 API 提供商是两个不同来源，混在一个卡里反而让人分不清
 *    「我现在用的是本地的还是云端的」。
 * 切换统一走 [ModelPickerDialog]，那里分「本机模型 / API 提供商」两段陈列。
 */
@Composable
private fun ActiveModelCard(
    state: ChatUiState,
    onOpenModelPicker: () -> Unit,
    activeModelLabel: String?,
) {
    MnnCard {
        MnnListItem(
            title = activeModelLabel ?: "未选择模型",
            subtitle = state.loadedModelPath
                ?: "点右侧「模型」选择本机模型或 API 云端模型",
            leading = Icons.Rounded.Memory,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.tight)) {
                    // 这里原来有一个「新话题」按钮，但只在 loadedModelPath != null
                    // （也就是只有本地模型）时显示 —— 用 API 时用户根本看不到它。
                    // 现在统一收到顶栏右上角的「新对话」，对本地/API 两条路径都可见，
                    // 所以卡片里不再重复放一个同名按钮。
                    // ⚠️ 这里**不用** miuix 的 `Button`。
                    //
                    // miuix 的 `Button` 会**覆盖子内容的文字色** ——
                    // 由 `ButtonDefaults.buttonColorsPrimary()` 内部算出的
                    // `onPrimary` 决定。真机上它算出的是**灰字配浅蓝底**，
                    // 对比度极低，用户反馈「模型两个字是灰的看不清」。
                    //
                    // 而且给 `Text` 显式指定颜色也**无效**（父级覆盖）。
                    // 所以改用下面这个自己画的胶囊按钮：背景与文字色
                    // 都由我们显式给定，不依赖任何主题配色计算。
                    MnnCapsuleButton(
                        text = "模型",
                        onClick = onOpenModelPicker,
                        emphasized = true,
                    )
                }
            },
        )

        state.memoryNotice?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
        }
        state.error?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
    }
}

/**
 * 一条消息气泡。
 *
 * ```
 * ┌──────────────────────────────────────────┐
 * │ 我 / 助手        [复制] [朗读] [下载]      │  ← 头部：角色 + 操作
 * ├──────────────────────────────────────────┤
 * │ [图片缩略图，点击放大]                     │
 * │ 正文文字…                                 │
 * └──────────────────────────────────────────┘
 * ```
 *
 * 操作按钮放头部而不是飘在气泡外：气泡宽度是自适应的（用户 86% / 助手 94%），
 * 外飘按钮在小屏上会溢出。
 */
@Composable
private fun MessageBubble(
    msg: ChatMessageUi,
    onSpeak: (String) -> Unit,
    ttsAvailable: Boolean,
    backdrop: LayerBackdrop,
    onDownloadImage: (String) -> Unit,
    onDownloadAudio: (String) -> Unit,
) {
    val isUser = msg.role == "user"
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val clipboard = LocalClipboardManager.current

    var zoomed by remember(msg) { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (isUser) {
                CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)
            } else {
                CardDefaults.defaultColors()
            },
        ) {
            Column(
                Modifier
                    .padding(MnnSpacing.card)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(MnnSpacing.tight),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isUser) "我" else "助手",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (isUser) {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                        modifier = Modifier.weight(1f),
                    )

                    if (msg.text.isNotBlank()) {
                        BubbleAction(
                            icon = Icons.Rounded.ContentCopy,
                            label = "复制",
                            tint = if (isUser) {
                                MiuixTheme.colorScheme.onPrimaryContainer
                            } else {
                                MnnTextColor.secondary
                            },
                        ) {
                            clipboard.setText(AnnotatedString(msg.text))
                        }
                    }

                    if (!isUser && ttsAvailable && msg.text.isNotBlank()) {
                        BubbleAction(
                            icon = Icons.Rounded.VolumeUp,
                            label = "朗读",
                            tint = MnnTextColor.secondary,
                        ) { onSpeak(msg.text) }
                    }

                    msg.audioPath?.let { path ->
                        BubbleAction(
                            icon = Icons.Rounded.Download,
                            label = "下载音频",
                            tint = MiuixTheme.colorScheme.primary,
                        ) { onDownloadAudio(path) }
                    }

                    msg.imagePath?.let { path ->
                        BubbleAction(
                            icon = Icons.Rounded.Download,
                            label = "下载图片",
                            tint = MiuixTheme.colorScheme.primary,
                        ) { onDownloadImage(path) }
                    }
                }

                msg.imagePath?.let { path ->
                    GeneratedImage(path = path, onClick = { zoomed = path })
                }

                // 思考过程：默认**折叠**。推理模型的 reasoning_content 可能比正文
                // 还长，直接铺开会把真正的回答顶到屏幕外。
                msg.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                    ReasoningBlock(
                        reasoning = reasoning,
                        // 正文还没开始 ⇒ 模型仍在思考阶段，标题显示「思考中…」。
                        streaming = msg.isStreaming && msg.text.isBlank(),
                    )
                }

                if (msg.text.isNotBlank() || msg.imagePath == null) {
                    Text(
                        msg.text.ifEmpty { "…" },
                        style = MiuixTheme.textStyles.body2,
                        color = if (isUser) {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
        if (msg.role == "assistant") {
            val metrics = msg.generationMetrics
            val rate = when {
                msg.isStreaming && msg.text.isBlank() && msg.reasoning.isNullOrBlank() -> "生成速度：等待生成…"
                msg.isStreaming -> "生成速度：测量中…"
                metrics != null -> {
                    val source = if (metrics.source == GenerationMetrics.Source.NATIVE_DECODE) {
                        "本机解码"
                    } else "API 端到端（含网络与等待）"
                    String.format(Locale.getDefault(), "%.1f tokens/s · %s", metrics.tokensPerSecond, source)
                }
                else -> "生成速度：不可用（未返回有效 token 统计）"
            }
            Text(
                text = rate,
                style = MiuixTheme.textStyles.footnote1,
                color = MnnTextColor.secondary,
                modifier = Modifier.padding(horizontal = MnnSpacing.tight, vertical = 4.dp),
            )
        }
        }
    }

    zoomed?.let { path ->
        ImageZoomDialog(
            path = path,
            backdrop = backdrop,
            onDismiss = { zoomed = null },
            onDownload = { onDownloadImage(path) },
        )
    }
}

/**
 * 「思考过程」折叠块。
 *
 * ## 为什么默认折叠
 * 推理模型（DeepSeek 的 reasoner / flash、QwQ 等）的 `reasoning_content`
 * 经常比正文还长。全铺开会把真正的回答顶出屏幕，用户以为「没回答」。
 *
 * ## 为什么用 `remember { mutableStateOf }` 而不是 rememberSaveable
 * 它只在**当前这条消息**上有效；[reasoning] 变化时不需要保留展开态
 * （同一条消息的思考文本是追加增长的，展开/收起由用户自己控制）。
 */
@Composable
private fun ReasoningBlock(reasoning: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "reasoning-arrow",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MnnRadii.small))
            .background(MnnTextColor.secondary.copy(alpha = 0.08f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Psychology,
                contentDescription = null,
                tint = MnnTextColor.secondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                // 正文还没到 ⇒ 模型还在思考阶段。
                text = if (streaming) "思考中…" else "思考过程",
                style = MiuixTheme.textStyles.footnote1,
                color = MnnTextColor.secondary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MnnTextColor.secondary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrowRotation),
            )
        }

        if (expanded) {
            Text(
                text = reasoning,
                style = MiuixTheme.textStyles.footnote1,
                color = MnnTextColor.secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            // 折叠态给一行预览，让用户知道里面有内容、值不值得点开。
            Text(
                text = reasoning,
                style = MiuixTheme.textStyles.footnote1,
                color = MnnTextColor.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 气泡头部的一个图标按钮。文字按钮会有 3~4 个、占满整行，把角色名挤没。 */
@Composable
private fun BubbleAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(MnnRadii.small))
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * 生成图缩略图。
 *
 * 用框架 [BitmapFactory] 手动解码，**不引 Coil** —— 本工程全程零第三方图片库，
 * 为一张本地图片加图片加载框架不划算。解码放 IO 线程。
 */
@Composable
private fun GeneratedImage(
    path: String,
    onClick: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MnnRadii.medium))
            .clickable(onClick = onClick)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "生成的图片，点击放大",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            // 解码中 / 失败：占位保持等高，避免列表跳动
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MiuixTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "图片加载中…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MnnTextColor.secondary,
                )
            }
        }
    }
}

/**
 * 输入区：液态玻璃 + 选择文件 + 思考强度设置。
 *
 * ⚠️ **必须在采集层之外调用**（见 [com.mnnkit.app.ui.AppShell] 的 `glassOverlay` 插槽）。
 * 玻璃挂在**空 Box** 上，内容做它的**兄弟** —— 这是库的绘制顺序决定的。
 */
@Composable
fun GlassInputBar(
    input: TextFieldState,
    generating: Boolean,
    canSend: Boolean,
    attachedFileName: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickFile: () -> Unit,
    onClearAttachment: () -> Unit,
    backdrop: LayerBackdrop,
    /** 当前思考强度。 */
    thinking: OpenAiCompatibleClient.ThinkingEffort,
    onThinkingChange: (OpenAiCompatibleClient.ThinkingEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThinking by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .liquidGlass(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(MnnRadii.large),
                    fallbackColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                )
        )
        Column(
            Modifier.padding(MnnSpacing.card),
            verticalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
        ) {
            TextField(
                state = input,
                modifier = Modifier.fillMaxWidth(),
                label = "输入消息…",
                useLabelAsPlaceholder = true,
            )

            attachedFileName?.let { name ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
                ) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                    Text(
                        name,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "移除",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MnnTextColor.secondary,
                        modifier = Modifier.clickable { onClearAttachment() },
                    )
                }
            }

            // ── 思考强度面板（点齿轮展开）──
            if (showThinking) {
                ThinkingPanel(current = thinking, onChange = onThinkingChange)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 选择文件
                Box(
                    Modifier
                        .clip(RoundedCornerShape(MnnRadii.small))
                        .clickable { onPickFile() }
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = "选择文件",
                        tint = MnnTextColor.secondary,
                    )
                }

                // 思考强度：齿轮 + 当前档位。收起时也把档位显示出来，
                // 否则用户不知道现在是开还是关 —— 那正是最容易困惑的地方。
                Row(
                    Modifier
                        .clip(RoundedCornerShape(MnnRadii.small))
                        .clickable { showThinking = !showThinking }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = "思考强度",
                        tint = if (thinking == OpenAiCompatibleClient.ThinkingEffort.OFF) {
                            MnnTextColor.secondary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        thinking.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (thinking == OpenAiCompatibleClient.ThinkingEffort.OFF) {
                            MnnTextColor.secondary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                }

                if (generating) {
                    MnnButton(
                        onClick = onStop,
                        style = MnnButtonStyle.Primary,
                        content = {
                            Text("停止生成")
                        },
                    )
                } else {
                    MnnButton(

                        onClick = onSend,
                        enabled = input.text.isNotBlank() && canSend,
                        style = MnnButtonStyle.Primary,
                        content = {
                            Text("发送")
                        },
                    )
                }
            }
        }
    }
}

/**
 * 思考强度选择面板。
 *
 * 用**档位**而不是一个二值开关：官方的 `reasoning_effort` 本来就是
 * `none / low / high / max` 四档（见 DeepSeek API 文档），
 * 做成开关会丢掉「思考多少」这个信息。同时保留「不指定」档 ——
 * 不是所有 OpenAI 兼容服务都认 `thinking` 字段，那一档就不发这个字段。
 */
@Composable
private fun ThinkingPanel(
    current: OpenAiCompatibleClient.ThinkingEffort,
    onChange: (OpenAiCompatibleClient.ThinkingEffort) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.tight),
    ) {
        // 显式给主文字色：这个面板嵌在玻璃浮层里，
        // 靠继承拿色会拿到偏暗的容器色 —— 真机实测「思考强度」这四个字是暗的。
        Text(
            "思考强度",
            style = MiuixTheme.textStyles.title2,
            color = MnnTextColor.primary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.tight)) {
            items(OpenAiCompatibleClient.ThinkingEffort.entries.toList(), key = { it.name }) { e ->
                val selected = e == current
                Card(
                    modifier = Modifier.clickable { onChange(e) },
                    colors = if (selected) {
                        CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.defaultColors()
                    },
                ) {
                    Text(
                        e.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MnnTextColor.secondary
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Text(
            current.note,
            style = MiuixTheme.textStyles.footnote1,
            color = MnnTextColor.secondary,
        )
    }
}
