package com.mnnkit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.mnnkit.app.data.skill.InstalledSkill
import com.mnnkit.app.data.skill.SkillEntry
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.mnnkit.app.data.skill.SkillManager
import com.mnnkit.app.ui.DockClearance
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnEmptyState
import com.mnnkit.app.ui.MnnListItem
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.staggeredEntry
import com.mnnkit.app.ui.theme.MnnSpacing
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Skill 库。
 *
 * 结构对齐 [ModelsScreen]：一块状态卡 + 导入卡 + 已安装列表 + 商店列表。
 *
 * Skill 的「生效」方式是**渐进式披露**（见 [com.mnnkit.app.data.skill.SkillPromptBuilder]）：
 * 只有**已启用**的 Skill 会被注入对话提示词，且默认只注入名称 + 描述，
 * 正文在需要时才展开 —— 否则端侧小模型的上下文会被塞爆。
 */
@Composable
fun SkillsScreen(
    backdrop: LayerBackdrop,
    skillManager: SkillManager,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page,
        // 底部留出悬浮 dock 的高度，滚到底时最后一项不被遮挡。
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    val state by skillManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    val linkState = rememberTextFieldState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        // ── 状态 ──
        item {
            Box(Modifier.staggeredEntry(0)) {
                MnnCard {
                    Text("Skill 库", style = MiuixTheme.textStyles.headline2)
                    Text(
                        "已安装 ${state.installed.size} 个 · 已启用 ${state.installed.count { it.enabled }} 个",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Button(
                        onClick = { scope.launch { skillManager.refresh() } },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(if (state.loading) "刷新中…" else "刷新商店") }
                    state.message?.let {
                        Text(
                            it,
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (state.error) MiuixTheme.colorScheme.error
                            else MiuixTheme.colorScheme.primary,
                        )
                    }
                    if (state.error) {
                        Text(
                            "操作失败，详见下方提示",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ── 源说明 ──
        if (state.available.isEmpty() && !state.loading) {
            item {
                Box(Modifier.staggeredEntry(1)) {
                    MnnStatusBanner(
                        text = "商店暂无条目。内置索引为空或未联网 —— 已安装的 Skill 不受影响，仍可用下方链接手动安装。",
                        icon = Icons.Rounded.Info,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── 从 GitHub 安装 ──
        item {
            Box(Modifier.staggeredEntry(2)) {
                MnnCard {
                    MnnListItem(
                        title = "从 GitHub 安装",
                        subtitle = "支持 owner/repo 简写，或带 /tree/分支/子目录 的完整链接",
                        leading = Icons.Rounded.Extension,
                        showDivider = showImport,
                        trailing = {
                            Text(
                                if (showImport) "收起" else "展开",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showImport = !showImport },
                            )
                        },
                    )
                    if (showImport) {
                        TextField(
                            state = linkState,
                            modifier = Modifier.fillMaxWidth(),
                            label = "anthropics/skills 或 https://github.com/…/tree/main/xxx",
                            useLabelAsPlaceholder = true,
                        )
                        Button(
                            onClick = {
                                skillManager.installFromLink(linkState.text.toString())
                                linkState.clearText()
                            },
                            enabled = linkState.text.isNotBlank(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text("安装") }
                        Text(
                            "说明：本应用通过 GitHub API 读取文件（raw.githubusercontent.com 在部分网络下不可达）。" +
                                "只下载文本类文件，二进制会被跳过。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        // ── 已安装 ──
        if (state.installed.isNotEmpty()) {
            item {
                Text(
                    "已安装",
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            items(state.installed, key = { "inst-" + it.manifest.name }) { skill ->
                Box(Modifier.staggeredEntry(1)) {
                    InstalledSkillCard(skill = skill, skillManager = skillManager)
                }
            }
        }

        // ── 商店 ──
        if (state.available.isNotEmpty()) {
            item {
                Text(
                    "可选（来自内置索引）",
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            items(state.available, key = { "avail-" + it.id }) { entry ->
                Box(Modifier.staggeredEntry(2)) {
                    AvailableSkillCard(
                        entry = entry,
                        busy = state.busyId == entry.id,
                        skillManager = skillManager,
                    )
                }
            }
        }

        if (state.installed.isEmpty() && state.available.isEmpty() && !state.loading) {
            item {
                MnnEmptyState(
                    text = "还没有任何 Skill",
                    hint = "展开上面的「从 GitHub 安装」，粘贴一个仓库地址试试",
                )
            }
        }
    }
}

@Composable
private fun InstalledSkillCard(
    skill: InstalledSkill,
    skillManager: SkillManager,
) {
    MnnCard {
        MnnListItem(
            title = skill.manifest.name,
            subtitle = skill.manifest.description.ifBlank { "（没有描述）" },
            leading = Icons.Rounded.Extension,
            trailing = {
                Text(
                    if (skill.enabled) "已启用" else "已停用",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (skill.enabled) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier.clickable {
                        skillManager.setEnabled(skill.manifest.name, !skill.enabled)
                    },
                )
            },
        )
        skill.manifest.validationError()?.let { problem ->
            MnnStatusBanner(
                text = "规范校验未通过：$problem（仍可使用，但可能不被模型正确识别）",
                icon = Icons.Rounded.Warning,
                color = MiuixTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
            Button(
                onClick = { skillManager.setEnabled(skill.manifest.name, !skill.enabled) },
                colors = if (skill.enabled) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
            ) { Text(if (skill.enabled) "停用" else "启用") }
            Button(onClick = { skillManager.uninstall(skill.manifest.name) }) { Text("卸载") }
        }
        Text(
            "目录：${skill.dir.name}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun AvailableSkillCard(
    entry: SkillEntry,
    busy: Boolean,
    skillManager: SkillManager,
) {
    MnnCard {
        MnnListItem(
            title = entry.name,
            subtitle = entry.description.ifBlank { "（没有描述）" },
            leading = Icons.Rounded.Extension,
            trailing = {
                if (entry.installed) {
                    Text(
                        "已安装",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                } else {
                    Button(
                        onClick = {
                            skillManager.installFromGitHub(
                                owner = entry.owner,
                                repo = entry.repo,
                                branch = entry.branch,
                                path = entry.path,
                                displayName = entry.name,
                            )
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(if (busy) "安装中…" else "安装") }
                }
            },
        )
        Text(
            buildString {
                append(entry.owner).append('/').append(entry.repo)
                if (entry.path.isNotBlank()) append(" · ").append(entry.path)
                if (entry.license.isNotBlank()) append(" · ").append(entry.license)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
