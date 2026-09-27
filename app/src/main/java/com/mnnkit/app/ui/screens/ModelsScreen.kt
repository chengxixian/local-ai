package com.mnnkit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.mnnkit.app.data.DownloadState
import com.mnnkit.app.data.ModelCatalog
import com.mnnkit.app.data.ModelManager
import com.mnnkit.app.ui.DockClearance
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnEmptyState
import com.mnnkit.app.ui.MnnInfoRow
import com.mnnkit.app.ui.MnnListItem
import com.mnnkit.app.ui.MnnPill
import com.mnnkit.app.ui.MnnProgress
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.glass.LocalTopBarInset
import com.mnnkit.app.ui.staggeredEntry
import com.mnnkit.app.ui.theme.MnnRadii
import com.mnnkit.app.ui.theme.MnnSpacing
import com.mnnkit.core.model.ModelFormat
import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.model.ModelSource
import com.mnnkit.core.model.ModelStatus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型商店。
 *
 * 视觉对齐参考实现 KSuRoot 的页面写法：
 *  - 页面是 `LazyColumn`，`contentPadding` 由外层传入（已含系统栏 + 悬浮底栏预留高度）；
 *  - 每个卡片是一个 `item`，包在 `Box(Modifier.staggeredEntry(index))` 里做错峰入场；
 *  - 卡片用 miuix `Card`（`surfaceContainer`），**不铺玻璃** ——
 *    玻璃感统一交给底栏（那里才有折射）；
 *  - 颜色与文字一律取 miuix 语义色与 textStyles。
 */
@Composable
fun ModelsScreen(
    backdrop: LayerBackdrop,
    modelManager: ModelManager,
    importState: ImportUiState,
    onImportLink: (String, ModelKind) -> Unit,
    onRefresh: () -> Unit,
    onSwitchSource: (ModelCatalog.StoreSource) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page + LocalTopBarInset.current,
        // 底部留出悬浮 dock 的高度，滚到底时最后一项不被遮挡。
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    val state by modelManager.state.collectAsState()
    var selectedKind by remember { mutableStateOf(ModelKind.LLM) }
    var showImport by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }

    val list = remember(state.models, selectedKind, keyword) {
        state.models
            .filter { it.kind == selectedKind }
            .let { pool ->
                if (keyword.isBlank()) {
                    pool
                } else {
                    val k = keyword.trim().lowercase()
                    pool.filter {
                        it.displayName.lowercase().contains(k) ||
                            it.vendor.lowercase().contains(k) ||
                            it.tags.any { t -> t.lowercase().contains(k) }
                    }
                }
            }
    }
    val counts = remember(state.models) {
        ModelKind.entries.associateWith { k -> state.models.count { it.kind == k } }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        item {
            Box(Modifier.staggeredEntry(0)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = MnnSpacing.inline),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("模型商店", style = MiuixTheme.textStyles.headline2)
                        Text(
                            "模型不随安装包分发 · 已占用 " +
                                ModelItem.formatSize(state.installedBytes),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(MnnRadii.small))
                            .clickable { onRefresh() }
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "刷新",
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        item {
            Box(Modifier.staggeredEntry(1)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                    items(ModelCatalog.StoreSource.entries.toList(), key = { it.id }) { src ->
                        val selected = src == state.storeSource
                        Card(modifier = Modifier.clickable { onSwitchSource(src) }) {
                            Text(
                                src.label,
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

        if (!state.fromNetwork && state.note != null) {
            item {
                Box(Modifier.staggeredEntry(2)) {
                    MnnStatusBanner(
                        text = "当前展示：${state.note}",
                        icon = Icons.Rounded.Info,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            Box(Modifier.staggeredEntry(3)) {
                SearchCard(onSearch = { keyword = it }, currentKeyword = keyword)
            }
        }

        item {
            Box(Modifier.staggeredEntry(4)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                    items(ModelKind.entries.toList(), key = { it.id }) { kind ->
                        val selected = kind == selectedKind
                        Card(modifier = Modifier.clickable { selectedKind = kind }) {
                            Text(
                                "${kind.label} ${counts[kind] ?: 0}",
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

        item {
            Box(Modifier.staggeredEntry(5)) {
                ImportCard(
                    expanded = showImport,
                    onToggle = { showImport = !showImport },
                    importState = importState,
                    onImport = onImportLink,
                )
            }
        }

        state.error?.let { err ->
            item {
                Box(Modifier.staggeredEntry(6)) {
                    MnnStatusBanner(
                        text = "商店加载失败：$err",
                        icon = Icons.Rounded.Warning,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }

        if (state.loading && list.isEmpty()) {
            item { MnnEmptyState("正在从 ${state.storeSource.label} 获取模型清单…") }
        }

        if (!state.loading && list.isEmpty()) {
            item {
                MnnEmptyState(
                    text = if (keyword.isNotBlank()) "没有匹配「$keyword」的模型" else "该分类下暂无模型",
                    hint = "试试切换来源、换关键词，或用「从链接导入」添加",
                )
            }
        }

        items(list, key = { it.id }) { item ->
            Box(Modifier.staggeredEntry(2)) {
                ModelCard(
                    item = item,
                    download = state.downloads[item.id],
                    onInstall = { src -> modelManager.install(item, src) },
                    onCancel = { modelManager.cancel(item.id) },
                    onUninstall = { modelManager.uninstall(item) },
                )
            }
        }
    }
}

@Composable
private fun SearchCard(currentKeyword: String, onSearch: (String) -> Unit) {
    val fieldState = rememberTextFieldState()
    MnnCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextField(
                    state = fieldState,
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索模型名称 / 厂商 / 标签",
                    useLabelAsPlaceholder = true,
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }
            Spacer(Modifier.width(MnnSpacing.inline))
            MnnButton(
                onClick = { onSearch(fieldState.text.toString()) },
                style = MnnButtonStyle.Primary,
                content = {
                    Text("搜索")
                },
            )
            if (currentKeyword.isNotBlank()) {
                Spacer(Modifier.width(MnnSpacing.tight))
                MnnButton(
onClick = {
                    fieldState.clearText()
                    onSearch("")
                },
                    style = MnnButtonStyle.Primary,
                    content = {
                        Text("清除")
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    importState: ImportUiState,
    onImport: (String, ModelKind) -> Unit,
) {
    var kind by remember { mutableStateOf(ModelKind.LLM) }
    val linkState = rememberTextFieldState()

    MnnCard {
        MnnListItem(
            title = "从链接导入",
            subtitle = "粘贴 HuggingFace / 魔搭地址，HF 链接自动转镜像站",
            leading = Icons.Rounded.Extension,
            showDivider = expanded,
            trailing = {
                Text(
                    if (expanded) "收起" else "展开",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onToggle() },
                )
            },
        )
        if (expanded) {
            TextField(
                state = linkState,
                modifier = Modifier.fillMaxWidth(),
                label = "https://huggingface.co/Qwen/Qwen3-0.6B",
                useLabelAsPlaceholder = true,
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            Text("模型用途", style = MiuixTheme.textStyles.footnote1)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                items(ModelKind.entries.toList(), key = { "imp-${it.id}" }) { k ->
                    MnnButton(
                        onClick = { kind = k },
                        style = MnnButtonStyle.Tonal,
                        content = {
                            Text(k.label)
                        },
                    )
                }
            }
            val link = linkState.text.toString()
            MnnButton(
                onClick = { onImport(link, kind) },
                enabled = link.isNotBlank() && !importState.busy,
                style = MnnButtonStyle.Primary,
                content = {
                    Text(if (importState.busy) "识别中…" else "识别并安装")
                },
            )
            importState.message?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (importState.error) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
            }
            importState.mirrorHint?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            ConversionNotice()
        }
    }
}

/**
 * 「转换为 .mnn」说明。
 *
 * 如实交代工程约束：MNN 官方的 LLM 导出工具依赖 PyTorch，
 * 端侧只能转 ONNX / TFLite / Caffe / TorchScript 这类"已经带计算图"的格式。
 */
@Composable
private fun ConversionNotice() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MnnRadii.medium))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
    ) {
        Icon(
            Icons.Rounded.Transform,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Text(
            "MNN 官方转换器是纯 C++，已内置到本应用 —— " +
                "ONNX / TFLite / Caffe / TorchScript 可以在这台手机上直接转成 .mnn，无需电脑。\n" +
                "safetensors / GGUF 只含权重、没有计算图，需要 PyTorch 追踪，端侧无法完成；" +
                "请改用已是 .mnn 的模型，或用工程内的电脑端转换脚本。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ModelCard(
    item: ModelItem,
    download: DownloadState?,
    onInstall: (ModelSource) -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
) {
    var chosenSource by remember(item.id) { mutableStateOf(item.source) }
    val status = download?.status ?: item.status

    MnnCard {
        MnnInfoRow(
            icon = iconForKind(item.kind),
            label = item.displayName,
            value = buildString {
                if (item.vendor.isNotBlank()) append(item.vendor).append(" · ")
                append(item.sizeLabel)
                if (item.format.needsConversion) append(" · ").append(item.format.label)
            },
            tint = tintForKind(item.kind),
        )

        if (item.description.isNotBlank()) {
            Text(
                item.description,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        if (item.tags.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(item.tags.take(8)) { tag -> MnnPill(tag) }
            }
        }

        when (status) {
            ModelStatus.INSTALLING -> {
                MnnProgress(download?.progress ?: 0f)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${((download?.progress ?: 0f) * 100).toInt()}%  ${download?.speedLabel.orEmpty()}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        ModelItem.formatSize(download?.downloadedBytes ?: 0L) + " / " +
                            ModelItem.formatSize(download?.totalBytes ?: item.sizeBytes),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                MnnButton(
                    onClick = onCancel,
                    style = MnnButtonStyle.Primary,
                    content = {
                        Text("取消下载")
                    },
                )
            }

            ModelStatus.READY -> {
                MnnInfoRow(
                    icon = Icons.Rounded.Memory,
                    label = "已安装",
                    value = item.localPath.orEmpty(),
                    tint = MiuixTheme.colorScheme.primary,
                )
                MnnButton(
                    onClick = onUninstall,
                    style = MnnButtonStyle.Primary,
                    content = {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(MnnSpacing.tight))
                        Text("删除")
                    },
                )
            }

            ModelStatus.FAILED -> {
                MnnStatusBanner(
                    text = download?.message ?: item.error ?: "安装失败",
                    icon = Icons.Rounded.Warning,
                    color = MiuixTheme.colorScheme.error,
                )
                MnnButton(
                    onClick = { onInstall(chosenSource) },
                    style = MnnButtonStyle.Primary,
                    content = {
                        Text("重试")
                    },
                )
            }

            ModelStatus.REMOTE -> {
                val sources = item.availableSources.filter { it != ModelSource.LOCAL }
                if (sources.size > 1) {
                    Text("下载源", style = MiuixTheme.textStyles.footnote1)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                        items(sources, key = { "s-${item.id}-${it.id}" }) { s ->
                            MnnButton(
                                onClick = { chosenSource = s },
                                style = MnnButtonStyle.Tonal,
                                content = {
                                    Text(s.label)
                                },
                            )
                        }
                    }
                }
                MnnButton(
                    onClick = { onInstall(chosenSource) },
                    style = MnnButtonStyle.Primary,
                    content = {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(MnnSpacing.tight))
                        Text("下载并安装")
                    },
                )

                if (item.format.needsConversion) {
                    ConvertPanel(item = item)
                }
            }
        }
    }
}

/**
 * 端侧转换面板。
 *
 * MNN 的转换器（`libMNNConvertDeps.so`）是纯 C++，已编译进本应用，
 * 因此 ONNX / TFLite / Caffe / TorchScript 可在这台手机上直接转换。
 */
@Composable
private fun ConvertPanel(item: ModelItem) {
    var status by remember(item.id) { mutableStateOf<String?>(null) }
    var running by remember(item.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
        MnnStatusBanner(
            text = "需转换为 .mnn 才能在本机运行",
            icon = Icons.Rounded.Transform,
            color = MiuixTheme.colorScheme.primary,
        )

        if (item.format == ModelFormat.SAFETENSORS || item.format == ModelFormat.BIN) {
            Text(
                "该格式只含权重、没有计算图，端侧转换器无法建图。" +
                    "请选一个已是 .mnn 的模型，或用电脑端转换脚本。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Text(
                "本机可直接转换（内置纯 C++ 转换器，无需电脑）。先下载再点转换。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            MnnButton(
                onClick = {
                    running = true
                    status = null
                    scope.launch {
                        val r = runOnDeviceConversion(item)
                        status = r.summary
                        running = false
                    }
                },
                enabled = !running && item.localPath != null,
                style = MnnButtonStyle.Primary,
                content = {
                    Text(if (running) "转换中…" else "在本机转换")
                },
            )
            if (item.localPath == null) {
                Text(
                    "请先下载该模型",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        status?.let {
            Text(
                it,
                style = MiuixTheme.textStyles.footnote1,
                color = if (it.startsWith("转换完成")) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.error
                },
            )
        }
    }
}

private suspend fun runOnDeviceConversion(
    item: ModelItem,
): com.mnnkit.app.convert.ConvertResult = kotlinx.coroutines.withContext(
    kotlinx.coroutines.Dispatchers.IO
) {
    val dir = item.localPath?.let { java.io.File(it) }
        ?: return@withContext com.mnnkit.app.convert.ConvertResult(
            ok = false,
            outputFile = null,
            log = "",
            error = "请先下载该模型，再执行转换。",
        )

    val input = listOf("onnx", "tflite", "caffemodel", "pt", "pb")
        .asSequence()
        .mapNotNull { ext -> dir.walkTopDown().firstOrNull { it.extension.equals(ext, true) } }
        .firstOrNull()
        ?: return@withContext com.mnnkit.app.convert.ConvertResult(
            ok = false,
            outputFile = null,
            log = "",
            error = "目录里没有可转换的权重（支持 onnx / tflite / caffemodel / pt / pb）",
        )

    val outDir = java.io.File(dir.parentFile ?: dir, "${item.displayName}-mnn")
    val output = java.io.File(outDir, input.nameWithoutExtension + ".mnn")

    com.mnnkit.app.convert.NativeConverter.convert(
        com.mnnkit.app.convert.ConvertRequest(
            inputPath = input.absolutePath,
            outputFile = output,
            format = ModelFormat.ONNX,
            quantBits = 0,
            quantBlock = -1,
        )
    )
}

private fun iconForKind(kind: ModelKind): ImageVector = when (kind) {
    ModelKind.LLM -> Icons.Rounded.Memory
    ModelKind.STT -> Icons.Rounded.GraphicEq
    ModelKind.TTS -> Icons.Rounded.GraphicEq
    ModelKind.DIFFUSION -> Icons.Rounded.Image
}

@Composable
private fun tintForKind(kind: ModelKind): Color = when (kind) {
    ModelKind.LLM -> MiuixTheme.colorScheme.primary
    ModelKind.STT -> MiuixTheme.colorScheme.primary
    ModelKind.TTS -> MiuixTheme.colorScheme.tertiaryContainer
    ModelKind.DIFFUSION -> MiuixTheme.colorScheme.secondaryContainer
}

/** 链接导入的界面状态。 */
data class ImportUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val mirrorHint: String? = null,
)
