package com.mnnkit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.mnnkit.app.data.InferenceBackend
import com.mnnkit.app.data.ModelManager
import com.mnnkit.app.ui.DockClearance
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnInfoRow
import com.mnnkit.app.ui.MnnListItem
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.staggeredEntry
import com.mnnkit.app.ui.theme.MnnAccentColor
import com.mnnkit.app.ui.theme.MnnSpacing
import com.mnnkit.core.memory.MemoryEntry
import com.mnnkit.core.memory.MemoryKind
import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.model.ModelStatus
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通用功能页：尚未接入的功能用它给出诚实的说明，而不是空白页。
 */
@Composable
fun FeatureScreen(
    backdrop: LayerBackdrop,
    title: String,
    status: String,
    description: String,
    bullets: List<String>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page,
        // 底部留出悬浮 dock 的高度，滚到底时最后一项不被遮挡。
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        item {
            Box(Modifier.staggeredEntry(0)) {
                MnnCard {
                    Text(title, style = MiuixTheme.textStyles.headline2)
                    MnnStatusBanner(
                        text = status,
                        icon = Icons.Rounded.Info,
                        color = MiuixTheme.colorScheme.error,
                    )
                    Text(
                        description,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item {
            Box(Modifier.staggeredEntry(1)) {
                MnnCard {
                    Text("已规划的能力", style = MiuixTheme.textStyles.title2)
                    bullets.forEach { b ->
                        MnnListItem(title = b, leading = Icons.Rounded.Info)
                    }
                }
            }
        }
    }
}

/**
 * 语音页：STT / TTS 的独立使用入口。
 *
 * 需求要求两个方向都能单独用：语音转文字（STT）与文字朗读（TTS）。
 */
@Composable
fun VoiceScreen(
    backdrop: LayerBackdrop,
    modelManager: ModelManager,
    sttReady: Boolean,
    ttsReady: Boolean,
    recording: Boolean,
    transcript: String,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page,
        // 底部留出悬浮 dock 的高度，滚到底时最后一项不被遮挡。
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    val state by modelManager.state.collectAsState()
    val sttModels = remember(state.models) { state.models.filter { it.kind == ModelKind.STT } }
    val ttsModels = remember(state.models) { state.models.filter { it.kind == ModelKind.TTS } }
    val readState = rememberTextFieldState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        item {
            Box(Modifier.staggeredEntry(0)) {
                MnnCard {
                    Text("语音转文字", style = MiuixTheme.textStyles.headline2)
                    MnnStatusBanner(
                        text = if (sttReady) "识别模型已就绪" else "需要先下载并加载一个语音识别模型",
                        icon = Icons.Rounded.Mic,
                        color = if (sttReady) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                    MnnButton(

                        onClick = if (recording) onStopRecording else onStartRecording,
                        enabled = sttReady,
                        style = MnnButtonStyle.Tonal,
                        content = {
                            Text(if (recording) "停止录音" else "开始录音")
                        },
                    )
                    if (transcript.isNotBlank()) {
                        MnnInfoRow(
                            icon = Icons.Rounded.GraphicEq,
                            label = "识别结果",
                            value = transcript,
                        )
                        MnnButton(
                            onClick = { onSpeak(transcript) }, enabled = ttsReady,
                            style = MnnButtonStyle.Primary,
                            content = {
                                Text("朗读这段文字")
                            },
                        )
                    }
                }
            }
        }

        item {
            Box(Modifier.staggeredEntry(1)) {
                MnnCard {
                    Text("文字朗读", style = MiuixTheme.textStyles.headline2)
                    MnnStatusBanner(
                        text = if (ttsReady) "合成模型已就绪" else "需要先下载并加载一个语音合成模型",
                        icon = Icons.Rounded.RecordVoiceOver,
                        color = if (ttsReady) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                    TextField(
                        state = readState,
                        modifier = Modifier.fillMaxWidth(),
                        label = "输入要朗读的文字…",
                        useLabelAsPlaceholder = true,
                    )
                    MnnButton(

                        onClick = { onSpeak(readState.text.toString()) },
                        enabled = ttsReady && readState.text.isNotBlank(),
                        style = MnnButtonStyle.Primary,
                        content = {
                            Text("朗读")
                        },
                    )
                }
            }
        }

        item {
            Text(
                "语音模型",
                style = MiuixTheme.textStyles.title2,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        items(sttModels + ttsModels, key = { it.id }) { item ->
            VoiceModelCard(item = item, modelManager = modelManager)
        }
    }
}

@Composable
private fun VoiceModelCard(item: ModelItem, modelManager: ModelManager) {
    val state by modelManager.state.collectAsState()
    val dl = state.downloads[item.id]
    MnnCard {
        MnnListItem(
            title = "${item.kind.label} · ${item.displayName}",
            subtitle = item.sizeLabel,
            leading = Icons.Rounded.GraphicEq,
            trailing = {
                when (dl?.status ?: item.status) {
                    ModelStatus.INSTALLING ->
                        MnnButton(
                            onClick = { modelManager.cancel(item.id) },
                            style = MnnButtonStyle.Primary,
                            content = {
                                Text("取消")
                            },
                        )

                    ModelStatus.READY -> Text(
                        "已安装",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )

                    else -> MnnButton(

                        onClick = { modelManager.install(item) },
                        style = MnnButtonStyle.Primary,
                        content = {
                            Text("下载")
                        },
                    )
                }
            },
        )
    }
}

/**
 * 设置页。
 *
 * 外观（动态取色 / 主题模式）放在这里 —— 记忆页只管记忆。
 */
@Composable
fun SettingsScreen(
    backdrop: LayerBackdrop,
    accentColor: MnnAccentColor,
    onAccentColorChange: (MnnAccentColor) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    settings: com.mnnkit.app.data.AppSettings.State,
    onSettingsChange: ((com.mnnkit.app.data.AppSettings.State) -> com.mnnkit.app.data.AppSettings.State) -> Unit,
    // ── API 接入 ──
    apiProviders: com.mnnkit.app.data.api.ApiProviderStore.Snapshot,
    apiNotice: String?,
    apiTestingId: String?,
    apiTestResult: String?,
    apiPresets: List<Pair<String, String>>,
    onApiAdd: () -> Unit,
    onApiUpdate: (com.mnnkit.app.data.api.ApiProvider) -> Unit,
    onApiRemove: (String) -> Unit,
    onApiSetActive: (String?) -> Unit,
    onApiTest: (com.mnnkit.app.data.api.ApiProvider) -> Unit,
    onApiClearAll: () -> Unit,
    onApiClearNotice: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page,
        // 底部留出悬浮 dock 的高度，滚到底时最后一项不被遮挡。
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        // ── 外观 ──
        item {
            Box(Modifier.staggeredEntry(0)) {
                MnnCard {
                    Text("外观 · 动态取色", style = MiuixTheme.textStyles.headline2)
                    Text(
                        "默认跟随壁纸（Monet）—— 换张壁纸，全应用主色跟着变。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                        items(MnnAccentColor.entries.toList(), key = { it.name }) { accent ->
                            val selected = accent == accentColor
                            Card(modifier = Modifier.clickable { onAccentColorChange(accent) }) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(
                                                accent.seedColor() ?: MiuixTheme.colorScheme.primary
                                            )
                                    )
                                    Text(
                                        accent.label,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = if (selected) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Text("深浅模式", style = MiuixTheme.textStyles.title2)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                        items(
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                            key = { it.first },
                        ) { (id, label) ->
                            val selected = id == themeMode
                            Card(modifier = Modifier.clickable { onThemeModeChange(id) }) {
                                Text(
                                    label,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (selected) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 推理参数 ──
        item {
            Box(Modifier.staggeredEntry(1)) {
                MnnCard {
                    Text("推理", style = MiuixTheme.textStyles.headline2)

                    // ── 后端选择：CPU / GPU / NPU ──
                    Text("计算后端", style = MiuixTheme.textStyles.title2)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                        items(InferenceBackend.entries.toList(), key = { it.id }) { backend ->
                            val selected = backend.id == settings.backendType
                            Card(
                                modifier = Modifier.clickable {
                                    onSettingsChange { it.copy(backendType = backend.id) }
                                },
                            ) {
                                Text(
                                    backend.label,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (selected) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    Text(
                        InferenceBackend.fromId(settings.backendType).note,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    MnnStatusBanner(
                        text = "改后端后需**重新加载模型**才生效；若加载失败，先换回 CPU 确认模型本身没问题。",
                        icon = Icons.Rounded.Warning,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )

                    MnnInfoRow(
                        icon = Icons.Rounded.Memory,
                        label = "线程数",
                        value = "${settings.threadCount}",
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                        items(listOf(2, 4, 6, 8), key = { it }) { n ->
                            val selected = n == settings.threadCount
                            Card(
                                modifier = Modifier.clickable {
                                    onSettingsChange { it.copy(threadCount = n) }
                                },
                            ) {
                                Text(
                                    "$n",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (selected) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    MnnInfoRow(
                        icon = Icons.Rounded.Memory,
                        label = "最大生成长度",
                        value = "${settings.maxNewTokens} tokens",
                    )
                    MnnButton(

                        onClick = {
                            // 在 256 / 512 / 1024 / 2048 之间循环
                            val next = when {
                                settings.maxNewTokens < 512 -> 512
                                settings.maxNewTokens < 1024 -> 1024
                                settings.maxNewTokens < 2048 -> 2048
                                else -> 256
                            }
                            onSettingsChange { it.copy(maxNewTokens = next) }
                        },
                        style = MnnButtonStyle.Primary,
                        content = {
                            Text("切换")
                        },
                    )
                }
            }
        }

        // ── 语音 ──
        item {
            Box(Modifier.staggeredEntry(2)) {
                MnnCard {
                    Text("语音", style = MiuixTheme.textStyles.headline2)
                    SettingToggle(
                        title = "回复后自动朗读",
                        checked = settings.autoSpeak,
                        onChange = { v -> onSettingsChange { it.copy(autoSpeak = v) } },
                    )
                    SettingToggle(
                        title = "识别完自动发送给模型",
                        checked = settings.autoSendAfterStt,
                        onChange = { v -> onSettingsChange { it.copy(autoSendAfterStt = v) } },
                    )
                }
            }
        }

        // ── 记忆 ──
        item {
            Box(Modifier.staggeredEntry(3)) {
                MnnCard {
                    Text("记忆", style = MiuixTheme.textStyles.headline2)
                    SettingToggle(
                        title = "启用记忆库",
                        checked = settings.memoryEnabled,
                        onChange = { v -> onSettingsChange { it.copy(memoryEnabled = v) } },
                    )
                    SettingToggle(
                        title = "自动写入记忆",
                        checked = settings.memoryAutoWrite,
                        onChange = { v -> onSettingsChange { it.copy(memoryAutoWrite = v) } },
                    )
                    MnnInfoRow(
                        icon = Icons.Rounded.Memory,
                        label = "每轮注入条数",
                        value = "${settings.memoryTopK}",
                    )
                }
            }
        }
        // ── API 接入 ──
        // 放最后：它不是高频设置，但需要足够空间（可展开编辑多个提供商）。
        item {
            Box(Modifier.staggeredEntry(4)) {
                ApiSettingsCard(
                    providers = apiProviders,
                    notice = apiNotice,
                    testingId = apiTestingId,
                    testResult = apiTestResult,
                    onAdd = onApiAdd,
                    onUpdate = onApiUpdate,
                    onRemove = onApiRemove,
                    onSetActive = onApiSetActive,
                    onTest = onApiTest,
                    onClearAll = onApiClearAll,
                    onClearNotice = onApiClearNotice,
                    presets = apiPresets,
                )
            }
        }
    }
}

/** 一行开关。用 miuix 语义色画出开/关两态，不引 Material 的 Switch。 */
@Composable
private fun SettingToggle(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MiuixTheme.textStyles.body2)
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (checked) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            )
        }
    }
}

/** 记忆库页面。 */
@Composable
fun MemoryScreen(
    backdrop: LayerBackdrop,
    entries: List<MemoryEntry>,
    counts: Map<MemoryKind, Int>,
    onAdd: (String, MemoryKind) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit,
    exportedPath: String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page,
        // 底部留出悬浮 dock 的高度，滚到底时最后一项不被遮挡。
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    val addState = rememberTextFieldState()
    var kind by remember { mutableStateOf(MemoryKind.FACT) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        // ⚠️ 「外观 · 动态取色」区块**已从这里移走** —— 它属于设置页，
        // 放在记忆页是历史遗留（记忆页只该管记忆）。见 SettingsScreen 的「外观」卡。

        item {
            Box(Modifier.staggeredEntry(0)) {
                MnnCard {
                    Text("长期记忆", style = MiuixTheme.textStyles.headline2)
                    Text(
                        "记忆会在每次对话时自动检索并注入上下文。" +
                            "支持向量语义检索；模型不支持 embedding 时自动退化为关键词检索。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        counts.entries.joinToString("  ") { "${it.key.label} ${it.value}" }
                            .ifBlank { "暂无记忆" },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            Box(Modifier.staggeredEntry(1)) {
                MnnCard {
                    Text("手动添加", style = MiuixTheme.textStyles.title2)
                    TextField(
                        state = addState,
                        modifier = Modifier.fillMaxWidth(),
                        label = "例如：我习惯用中文问答，回答请简短",
                        useLabelAsPlaceholder = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                        MemoryKind.entries.take(4).forEach { k ->
                            Card(modifier = Modifier.clickable { kind = k }) {
                                Text(
                                    k.label,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (k == kind) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                        MnnButton(

                            onClick = {
                                onAdd(addState.text.toString(), kind)
                                addState.clearText()
                            },
                            enabled = addState.text.isNotBlank(),
                            style = MnnButtonStyle.Primary,
                            content = {
                                Text("记住")
                            },
                        )
                        MnnButton(
                            onClick = onExport,
                            style = MnnButtonStyle.Primary,
                            content = {
                                Text("导出")
                            },
                        )
                        MnnButton(
                            onClick = onClearAll,
                            style = MnnButtonStyle.Primary,
                            content = {
                                Text("清空全部")
                            },
                        )
                    }
                    exportedPath?.let {
                        Text(
                            "已导出到：$it",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                MnnCard {
                    Text(
                        "还没有任何记忆。试着对助手说「记住我喜欢简洁的回答」。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        items(entries, key = { it.id }) { entry ->
            MnnCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.kind.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    MnnButton(
                        onClick = { onDelete(entry.id) },
                        style = MnnButtonStyle.Primary,
                        content = {
                            Text("删除")
                        },
                    )
                }
                Text(entry.content, style = MiuixTheme.textStyles.body2)
                Text(
                    buildString {
                        append("来源 ${entry.source.ifBlank { "手动" }}")
                        append(" · 权重 ${"%.1f".format(entry.importance)}")
                        append(" · 命中 ${entry.accessCount} 次")
                        if (entry.embedding != null) append(" · 已向量化")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}


