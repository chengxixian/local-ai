package com.mnnkit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.mnnkit.app.data.skill.FEATURED_SOURCE
import com.mnnkit.app.data.skill.InstalledSkill
import com.mnnkit.app.data.skill.SkillEntry
import com.mnnkit.app.data.skill.SkillManager
import com.mnnkit.app.ui.DockClearance
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnEmptyState
import com.mnnkit.app.ui.MnnListItem
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.glass.LocalTopBarInset
import com.mnnkit.app.ui.staggeredEntry
import com.mnnkit.app.ui.theme.MnnSpacing
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Repository links browse a store; only a chosen skill card triggers installation. */
@Composable
fun SkillsScreen(
    backdrop: LayerBackdrop,
    skillManager: SkillManager,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page, end = MnnSpacing.page, top = MnnSpacing.page + LocalTopBarInset.current,
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    val state by skillManager.state.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    val linkState = rememberTextFieldState()
    val searchState = rememberTextFieldState()
    val query = searchState.text.toString().trim()
    val busy = state.loading || state.busyId != null
    val available = state.available.filter { entry ->
        query.isEmpty() || listOf(entry.name, entry.description, entry.path, "${entry.owner}/${entry.repo}").any { it.contains(query, ignoreCase = true) }
    }
    val installed = state.installed.filter { skill ->
        query.isEmpty() || listOf(skill.manifest.name, skill.manifest.description, skill.id).any { it.contains(query, ignoreCase = true) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(), contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        item {
            Box(Modifier.staggeredEntry(0)) {
                MnnCard {
                    Text("Skill 库", style = MiuixTheme.textStyles.headline2)
                    Text(
                        "已安装 ${state.installed.size} 个 · 已启用 ${state.installed.count { it.enabled }} 个",
                        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    MnnButton(
                        onClick = skillManager::refreshSource, enabled = !busy,
                        style = MnnButtonStyle.Primary, content = { Text(if (state.loading) "正在发现技能…" else "联网刷新来源") },
                    )
                    if (busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary)
                        Text(state.progress ?: "正在处理…", style = MiuixTheme.textStyles.footnote1)
                    }
                    state.message?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = if (state.error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            MnnCard {
                Text("选择来源", style = MiuixTheme.textStyles.title2)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                    MnnButton(
                        onClick = { skillManager.selectSource(FEATURED_SOURCE) }, enabled = !busy,
                        style = if (state.selectedSourceId == FEATURED_SOURCE) MnnButtonStyle.Primary else MnnButtonStyle.Tonal,
                        content = { Text("精选索引") },
                    )
                    state.sources.forEach { source ->
                        MnnButton(
                            onClick = { skillManager.selectSource(source.id) }, enabled = !busy,
                            style = if (state.selectedSourceId == source.id) MnnButtonStyle.Primary else MnnButtonStyle.Tonal,
                            content = { Text(source.label + source.ref.branch.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty() + source.ref.path.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()) },
                        )
                    }
                }
                val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
                if (selectedSource != null && selectedSource.ref.repositoryId !in setOf("anthropics/skills", "openai/skills")) {
                    MnnButton(onClick = { skillManager.removeSource(selectedSource.id) }, enabled = !busy, style = MnnButtonStyle.Tonal, content = { Text("移除此来源（保留已安装技能）") })
                }
                TextField(state = searchState, modifier = Modifier.fillMaxWidth(), label = "搜索名称、描述或仓库路径", useLabelAsPlaceholder = true)
                Text(
                    "来源与浏览结果会保存在本机。搜索当前来源；切换来源可浏览其他仓库。",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item {
            Box(Modifier.staggeredEntry(1)) {
                MnnCard {
                    MnnListItem(
                        title = "添加 GitHub 来源", subtitle = "支持仓库、子目录或 SKILL.md 链接；先浏览，再选择安装",
                        leading = Icons.Rounded.Extension, showDivider = showImport,
                        trailing = {
                            Text(if (showImport) "收起" else "展开", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary, modifier = Modifier.clickable { showImport = !showImport })
                        },
                    )
                    if (showImport) {
                        TextField(state = linkState, modifier = Modifier.fillMaxWidth(), label = "owner/repo 或 https://github.com/…/tree/main/skills", useLabelAsPlaceholder = true)
                        MnnButton(
                            onClick = { skillManager.browseFromLink(linkState.text.toString()) }, enabled = linkState.text.isNotBlank() && !busy,
                            style = MnnButtonStyle.Primary, content = { Text("浏览并选择技能") },
                        )
                        Text(
                            "默认分支自动识别；分支名含 / 时，在链接中写成 %2F。仓库压缩包最多 64 MB。不会自动执行技能内的脚本，也不会授予新工具能力。",
                            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (installed.isNotEmpty()) {
            item { Text("已安装 · ${installed.size}", style = MiuixTheme.textStyles.title2, modifier = Modifier.padding(start = 4.dp)) }
            items(installed, key = { "inst-${it.id}" }) { skill -> InstalledSkillCard(skill, skillManager, busy) }
        }
        item {
            Text("可选技能 · ${available.size} / ${state.available.size}", style = MiuixTheme.textStyles.title2, modifier = Modifier.padding(start = 4.dp))
        }
        items(available, key = { "avail-${it.id}" }) { entry ->
            Box(Modifier.staggeredEntry(2)) { AvailableSkillCard(entry, state.busyId == entry.id, busy, skillManager) }
        }
        if (available.isEmpty() && !state.loading) {
            item {
                MnnEmptyState(
                    text = if (query.isNotEmpty()) "没有匹配的技能" else "此来源暂无技能",
                    hint = if (query.isNotEmpty()) "试试其他关键词或切换来源" else "点击「联网刷新来源」，或添加包含 SKILL.md 的 GitHub 仓库",
                )
            }
        }
        item {
            MnnStatusBanner(
                text = "安装前请确认来源可信并遵守技能声明的许可。未声明许可不代表可自由使用。技能中的脚本仅保存为文件，不会执行。",
                icon = Icons.Rounded.Info, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun InstalledSkillCard(skill: InstalledSkill, skillManager: SkillManager, busy: Boolean) {
    MnnCard {
        MnnListItem(
            title = skill.manifest.name, subtitle = skill.manifest.description.ifBlank { "（没有描述）" }, leading = Icons.Rounded.Extension,
            trailing = { Text(if (skill.enabled) "已启用" else "已停用", style = MiuixTheme.textStyles.footnote1, color = if (skill.enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary) },
        )
        skill.manifest.validationError()?.let { problem ->
            MnnStatusBanner(text = "规范校验未通过：$problem", icon = Icons.Rounded.Warning, color = MiuixTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
            MnnButton(onClick = { skillManager.setEnabled(skill.id, !skill.enabled) }, enabled = !busy, style = MnnButtonStyle.Tonal, content = { Text(if (skill.enabled) "停用" else "启用") })
            MnnButton(onClick = { skillManager.uninstall(skill.id) }, enabled = !busy, style = MnnButtonStyle.Primary, content = { Text("卸载") })
        }
        Text(skill.id + "\n许可：" + skill.manifest.license.ifBlank { "未声明（请查看来源）" }, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun AvailableSkillCard(entry: SkillEntry, installing: Boolean, anyBusy: Boolean, skillManager: SkillManager) {
    MnnCard {
        MnnListItem(
            title = entry.name, subtitle = entry.description.ifBlank { "（没有描述）" }, leading = Icons.Rounded.Extension,
            trailing = {
                MnnButton(
                    onClick = { skillManager.install(entry) }, enabled = !anyBusy && entry.problem == null, style = if (entry.installed) MnnButtonStyle.Tonal else MnnButtonStyle.Primary,
                    content = { Text(when { installing -> "安装中…"; entry.installed -> "更新"; entry.problem != null -> "不可安装"; else -> "安装" }) },
                )
            },
        )
        if (entry.installed) Text("已安装", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
        entry.problem?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
        Text(
            "${entry.owner}/${entry.repo} · ${entry.path.ifBlank { "仓库根目录" }}\n许可：${entry.license.ifBlank { if (entry.origin == "bundled") "联网读取 SKILL.md 后确认" else "未声明（请查看来源）" }}",
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
