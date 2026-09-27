package com.mnnkit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mnnkit.app.data.api.ApiProvider
import com.mnnkit.app.data.api.ApiProviderStore
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.theme.MnnRadii
import com.mnnkit.app.ui.theme.MnnSpacing
import com.mnnkit.app.ui.theme.MnnTextColor
import com.mnnkit.core.model.ModelItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型选择面板。
 *
 * 结构按需求分成**两段**，各自标题下陈列各自的模型：
 *
 * ```
 * ┌─ 选择模型 ─────────────────────────────┐
 * │ 本机模型                                │
 * │   推理全在本机，无需联网                  │
 * │   ┌────────────────────────────────┐   │
 * │   │ Qwen3.5-0.8B-MNN   522 MB  使用中│   │
 * │   │ MiniMind2-MNN       65 MB   加载 │   │
 * │   └────────────────────────────────┘   │
 * │  ─────────────────────────────────────  │
 * │ API 云端模型                            │
 * │   调用云端或局域网服务，需要联网           │
 * │   ┌────────────────────────────────┐   │
 * │   │ DeepSeek / deepseek-chat   使用  │   │
 * │   └────────────────────────────────┘   │
 * └────────────────────────────────────────┘
 * ```
 *
 * **不给「仅使用本机模型」这种抽象选项** —— 它就是「本机模型」段里的一项，
 * 单独列出来会让人以为还有第三种模式。这里改为：本机段第一项叫
 * 「不使用 API（仅本机模型）」，语义落在同一段里。
 */
@Composable
fun ModelPickerDialog(
    installedLlmModels: List<ModelItem>,
    loadedModelPath: String?,
    providers: ApiProviderStore.Snapshot,
    onPickLocal: (ModelItem) -> Unit,
    onPickApi: (ApiProvider) -> Unit,
    onDisableApi: () -> Unit,
    onDisableLocal: (ModelItem) -> Unit,
    onManageApi: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(MnnSpacing.card),
                verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
            ) {
                Text("选择模型", style = MiuixTheme.textStyles.headline2, color = MnnTextColor.primary)

                LazyColumn(
                    modifier = Modifier.heightIn(max = 470.dp),
                    verticalArrangement = Arrangement.spacedBy(MnnSpacing.tight),
                ) {
                    // ══════════ 第一段：本机模型 ══════════
                    item {
                        SectionHeader(
                            icon = Icons.Rounded.Memory,
                            title = "本机模型",
                            subtitle = "推理全在本机，无需联网",
                        )
                    }

                    if (installedLlmModels.isEmpty()) {
                        item {
                            MnnStatusBanner(
                                text = "还没有已下载的模型 · 去「模型商店」下载",
                                icon = Icons.Rounded.Memory,
                                color = MnnTextColor.secondary,
                            )
                        }
                    } else {
                        items(installedLlmModels, key = { it.id }) { model ->
                            val active = model.localPath == loadedModelPath
                            PickRow(
                                title = model.displayName,
                                subtitle = model.sizeLabel,
                                icon = Icons.Rounded.Memory,
                                selected = active,
                                actionLabel = if (active) "使用中" else "加载",
                                onAction = { onPickLocal(model) },
                            )
                        }
                        // 卸载：已加载时才给，避免用户不知道当前占着内存
                        loadedModelPath?.let { path ->
                            val current = installedLlmModels.firstOrNull { it.localPath == path }
                            if (current != null) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        MnnCapsuleButton(
                                            text = "卸载「${current.displayName}」",
                                            onClick = { onDisableLocal(current) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ══════════ 第二段：API 云端模型 ══════════
                    item {
                        SectionHeader(
                            icon = Icons.Rounded.Cloud,
                            title = "API 云端模型",
                            subtitle = "调用云端或局域网服务，需要联网",
                        )
                    }

                    if (providers.providers.isEmpty()) {
                        item {
                            MnnStatusBanner(
                                text = "还没有配置 API 提供商 · 点下方「管理 API」添加",
                                icon = Icons.Rounded.Cloud,
                                color = MnnTextColor.secondary,
                            )
                        }
                    } else {
                        items(providers.providers, key = { it.id }) { p ->
                            val active = providers.activeId == p.id
                            PickRow(
                                title = "${p.name.ifBlank { "未命名" }} / ${p.llmModel.ifBlank { "—" }}",
                                // 把能力显示出来，用户一眼看出这家能不能做语音、生图
                                subtitle = buildString {
                                    val caps = buildList {
                                        if (p.canChat) add("对话")
                                        if (p.canAsr) add("ASR")
                                        if (p.canTts) add("TTS")
                                        if (p.canImage) add("生图")
                                    }
                                    append(if (caps.isEmpty()) "未启用任何能力" else caps.joinToString(" · "))
                                },
                                icon = Icons.Rounded.Cloud,
                                selected = active,
                                actionLabel = if (active) "使用中" else "使用",
                                onAction = { onPickApi(p) },
                            )
                        }
                        if (providers.activeId != null) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    MnnCapsuleButton(text = "取消使用 API", onClick = onDisableApi)
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
                ) {
                    MnnCapsuleButton(
                        text = "管理 API",
                        onClick = onManageApi,
                        modifier = Modifier.weight(1f),
                    )
                    MnnCapsuleButton(
                        text = "关闭",
                        onClick = onDismiss,
                        emphasized = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 段标题：图标 + 标题 + 一句说明。 */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = MnnSpacing.inline, bottom = MnnSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column {
            Text(title, style = MiuixTheme.textStyles.title2, color = MnnTextColor.primary)
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MnnTextColor.secondary,
            )
        }
    }
}

/** 一行可选中的模型。整行可点，右侧给明确的动作标签。 */
@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MnnRadii.medium))
            .clickable(enabled = !selected, onClick = onAction),
        colors = if (selected) {
            CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.defaultColors()
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(MnnSpacing.card),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MnnTextColor.secondary
                },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) {
                        MiuixTheme.colorScheme.onPrimaryContainer
                    } else {
                        MnnTextColor.primary
                    },
                )
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MnnTextColor.secondary,
                )
            }
            if (selected) {
                Text(
                    actionLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            } else {
                MnnCapsuleButton(text = actionLabel, onClick = onAction, emphasized = true)
            }
        }
    }
}
