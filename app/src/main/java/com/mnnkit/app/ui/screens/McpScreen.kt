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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
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
import com.mnnkit.app.data.mcp.McpManager
import com.mnnkit.app.ui.DockClearance
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnEmptyState
import com.mnnkit.app.ui.MnnListItem
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.staggeredEntry
import com.mnnkit.app.ui.theme.MnnSpacing
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MCP 服务端管理。
 *
 * 三块：
 *  1. 已配置服务端（连接 / 断开 / 删除 / 启用）
 *  2. 粘贴 mcp.json 导入
 *  3. 工具清单 + 手动试跑（这是端侧模型调工具之前的验证手段）
 *
 * stdio 型配置会被标灰并给出原因 —— Android 10+ 因 SELinux W^X
 * 无法 `execve` 应用目录下的二进制，这不是「还没做」，是**做不到**。
 */
@Composable
fun McpScreen(
    backdrop: LayerBackdrop,
    mcpManager: McpManager,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = MnnSpacing.page,
        end = MnnSpacing.page,
        top = MnnSpacing.page,
        // 底部留出悬浮 dock 的高度，滚到底时最后一项不被遮挡。
        bottom = MnnSpacing.page + DockClearance,
    ),
) {
    val state by mcpManager.state.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    val jsonState = rememberTextFieldState()
    var toolArgs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.item),
    ) {
        // ── 状态 ──
        item {
            Box(Modifier.staggeredEntry(0)) {
                MnnCard {
                    Text("MCP 服务端", style = MiuixTheme.textStyles.headline2)
                    Text(
                        "已配置 ${state.servers.size} 个 · 已连接 ${state.connectedCount} 个 · " +
                            "可用工具 ${state.toolCount} 个",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    state.message?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    }
                    state.error?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }

        // ── 导入 ──
        item {
            Box(Modifier.staggeredEntry(1)) {
                MnnCard {
                    MnnListItem(
                        title = "导入配置",
                        subtitle = "粘贴 mcp.json（官方 mcpServers 格式）",
                        leading = Icons.Rounded.Hub,
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
                            state = jsonState,
                            modifier = Modifier.fillMaxWidth(),
                            label = "{ \"mcpServers\": { … } }",
                            useLabelAsPlaceholder = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
                            MnnButton(
                                onClick = {
                                    mcpManager.importFromJson(jsonState.text.toString())
                                    jsonState.clearText()
                                },
                                enabled = jsonState.text.isNotBlank(),
                                style = MnnButtonStyle.Primary,
                                content = {
                                    Text("导入")
                                },
                            )
                            MnnButton(
                                onClick = { jsonState.setTextAndPlaceCursorAtEnd(mcpManager.sampleConfig()) },
                                style = MnnButtonStyle.Primary,
                                content = {
                                    Text("填入示例")
                                },
                            )
                        }
                        Text(
                            "Android 只支持 HTTP 传输。stdio 型配置（command/args）会被识别但无法运行 —— " +
                                "Android 10+ 的 SELinux 策略不允许执行应用数据目录里的二进制。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        // ── 服务端列表 ──
        if (state.servers.isEmpty()) {
            item {
                MnnEmptyState(
                    text = "还没有配置任何 MCP 服务端",
                    hint = "展开「导入配置」，可以先点「填入示例」看看格式",
                )
            }
        }

        items(state.servers, key = { "srv-" + it.config.name }) { server ->
            Box(Modifier.staggeredEntry(2)) {
                ServerCard(
                    server = server,
                    busy = state.busyName == server.config.name,
                    mcpManager = mcpManager,
                )
            }
        }

        // ── 工具清单 ──
        // 每个服务端一个参数输入框 + 每个工具一个调用按钮。
        // 刻意不做「一个工具一个输入框」：工具可能有十几个，
        // 那会让界面变成一堵输入框的墙，而且参数框之间没有区别。
        val connected = state.servers.filter { it.connected && it.tools.isNotEmpty() }
        if (connected.isNotEmpty()) {
            item {
                Text(
                    "可用工具（手动试跑）",
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            items(connected, key = { "tools-" + it.config.name }) { server ->
                val argsField = rememberTextFieldState()
                MnnCard {
                    Text(
                        "${server.config.name} · ${server.tools.size} 个工具",
                        style = MiuixTheme.textStyles.title2,
                    )
                    TextField(
                        state = argsField,
                        modifier = Modifier.fillMaxWidth(),
                        label = "参数 JSON（留空即 {}），下面所有工具共用",
                        useLabelAsPlaceholder = true,
                    )
                    server.tools.forEach { tool ->
                        MnnListItem(
                            title = tool.name,
                            subtitle = tool.description.ifBlank { "（没有描述）" },
                            leading = Icons.Rounded.Build,
                            trailing = {
                                MnnButton(
                                    onClick = {
                                        mcpManager.callTool(
                                            server = server.config.name,
                                            tool = tool.name,
                                            argumentsJson = argsField.text.toString(),
                                        )
                                    },
                                    enabled = state.busyName == null,
                                    style = MnnButtonStyle.Primary,
                                    content = {
                                        Text("调用")
                                    },
                                )
                            },
                        )
                        tool.inputSchema?.let { schema ->
                            val props = schema.asObject?.get("properties")?.asObject
                            if (props != null && props.isNotEmpty()) {
                                Text(
                                    "参数：" + props.entries.joinToString("、") { (k, v) ->
                                        val type = v.asObject?.get("type")?.asString ?: "any"
                                        "$k($type)"
                                    },
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 调用结果 ──
        state.toolOutput?.let { output ->
            item {
                Box(Modifier.staggeredEntry(3)) {
                    MnnStatusBanner(
                        text = output,
                        icon = if (state.toolOutputIsError) Icons.Rounded.Warning else Icons.Rounded.Info,
                        color = if (state.toolOutputIsError) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: McpManager.ServerState,
    busy: Boolean,
    mcpManager: McpManager,
) {
    val cfg = server.config
    val problem = cfg.validationError()

    MnnCard {
        MnnListItem(
            title = cfg.name,
            subtitle = buildString {
                append(cfg.transport.label)
                if (cfg.url.isNotBlank()) append(" · ").append(cfg.url)
                if (server.connected && server.serverName.isNotBlank()) {
                    append(" · ").append(server.serverName)
                    if (server.protocolVersion.isNotBlank()) append(" (").append(server.protocolVersion).append(')')
                }
            },
            leading = Icons.Rounded.Hub,
            trailing = {
                Text(
                    when {
                        busy -> "连接中…"
                        server.connected -> "已连接"
                        else -> "未连接"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (server.connected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            },
        )

        if (problem != null) {
            MnnStatusBanner(
                text = problem,
                icon = Icons.Rounded.Warning,
                color = MiuixTheme.colorScheme.error,
            )
        }

        server.lastError?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }

        if (server.tools.isNotEmpty()) {
            Text(
                "工具：${server.tools.joinToString("、") { it.name }}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline)) {
            if (server.connected) {
                MnnButton(
                    onClick = { mcpManager.disconnect(cfg.name) },
                    style = MnnButtonStyle.Primary,
                    content = {
                        Text("断开")
                    },
                )
            } else {
                MnnButton(
                    onClick = { mcpManager.connect(cfg.name) },
                    enabled = !busy && problem == null,
                    style = MnnButtonStyle.Primary,
                    content = {
                        Text(if (busy) "连接中…" else "连接")
                    },
                )
            }
            MnnButton(
                onClick = { mcpManager.setEnabled(cfg.name, !cfg.enabled) },
                style = MnnButtonStyle.Primary,
                content = {
                    Text(if (cfg.enabled) "停用" else "启用")
                },
            )
            MnnButton(
                onClick = { mcpManager.remove(cfg.name) },
                style = MnnButtonStyle.Primary,
                content = {
                    Text("删除")
                },
            )
        }
    }
}
