package com.mnnkit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mnnkit.app.data.api.ApiProvider
import com.mnnkit.app.data.api.ApiProviderStore
import com.mnnkit.app.ui.MnnCard
import com.mnnkit.app.ui.MnnListItem
import com.mnnkit.app.ui.MnnStatusBanner
import com.mnnkit.app.ui.theme.MnnRadii
import com.mnnkit.app.ui.theme.MnnSpacing
import com.mnnkit.app.ui.theme.MnnTextColor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * API 接入配置卡。
 *
 * ## 支持的四种能力
 *
 * 一个提供商可以只填其中几项 —— 比如只填对话模型，那就只用它对话；
 * 四项都填，语音页与生图也走它。界面按能力分组，缺哪项一眼能看见。
 *
 * ## 为什么所有字段都是纯文本输入
 *
 * 不给「获取 API Key」的跳转、不做 OAuth —— 各家流程不同，而且都要浏览器登录。
 * 用户从服务商后台复制 key 粘进来是最直接可靠的路径。
 * 但**给了 baseUrl 预设**，那是最容易填错的一项。
 */
@Composable
fun ApiSettingsCard(
    providers: ApiProviderStore.Snapshot,
    notice: String?,
    testingId: String?,
    testResult: String?,
    onAdd: () -> Unit,
    onUpdate: (ApiProvider) -> Unit,
    onRemove: (String) -> Unit,
    onSetActive: (String?) -> Unit,
    onTest: (ApiProvider) -> Unit,
    onClearAll: () -> Unit,
    onClearNotice: () -> Unit,
    presets: List<Pair<String, String>>,
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    val editing = providers.providers.firstOrNull { it.id == editingId }

    MnnCard {
        Text("API 接入", style = MiuixTheme.textStyles.headline2)
        Text(
            "接入任意 OpenAI 兼容服务（OpenAI / DeepSeek / 百炼 / 硅基流动 / " +
                "本地 Ollama 等）。一个提供商可只启用部分能力。",
            style = MiuixTheme.textStyles.body2,
            color = MnnTextColor.secondary,
        )

        if (notice != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
            ) {
                Text(
                    notice,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "知道了",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MnnTextColor.secondary,
                    modifier = Modifier.clickable { onClearNotice() },
                )
            }
        }

        // ── 已配置列表 ──
        if (providers.providers.isEmpty()) {
            MnnStatusBanner(
                text = "还没有配置任何 API 提供商",
                icon = Icons.Rounded.Cloud,
                color = MnnTextColor.secondary,
            )
        } else {
            providers.providers.forEachIndexed { i, p ->
                val active = providers.activeId == p.id
                MnnListItem(
                    title = p.name.ifBlank { "未命名" } + if (active) " · 使用中" else "",
                    subtitle = buildString {
                        append(p.baseUrl)
                        val caps = buildList {
                            if (p.canChat) add("对话")
                            if (p.canAsr) add("ASR")
                            if (p.canTts) add("TTS")
                            if (p.canImage) add("生图")
                        }
                        append("  [")
                        append(if (caps.isEmpty()) "未启用任何能力" else caps.joinToString("/"))
                        append("]")
                    },
                    leading = Icons.Rounded.Cloud,
                    showDivider = i != providers.providers.lastIndex,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.tight)) {
                            MnnButton(
                                onClick = { editingId = if (editingId == p.id) null else p.id },
                                style = MnnButtonStyle.Primary,
                                content = {
                                    Text(if (editingId == p.id) "收起" else "编辑")
                                },
                            )
                            MnnButton(
                                onClick = { onSetActive(if (active) null else p.id) },
                                style = MnnButtonStyle.Tonal,
                                content = {
                                    Text(if (active) "取消启用" else "启用")
                                },
                            )
                        }
                    },
                )
            }
        }

        // ── 编辑区 ──
        editing?.let { p ->
            ProviderEditor(
                provider = p,
                presets = presets,
                testing = testingId == p.id,
                testResult = if (testingId == p.id) testResult else null,
                onChange = onUpdate,
                onTest = { onTest(p) },
                onDelete = {
                    onRemove(p.id)
                    editingId = null
                },
            )
        }

        // ── 底部操作 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
        ) {
            MnnButton(
                onClick = onAdd, modifier = Modifier.weight(1f),
                style = MnnButtonStyle.Primary,
                content = {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(" 添加提供商")
                },
            )
            if (providers.providers.isNotEmpty()) {
                MnnButton(
                    onClick = onClearAll,
                    style = MnnButtonStyle.Primary,
                    content = {
                        Text("全部清除")
                    },
                )
            }
        }

        MnnStatusBanner(
            text = "API Key 以明文保存在应用私有目录（files/api_providers.json），" +
                "仅本机可读。若要分享设备，请先用「全部清除」。",
            icon = Icons.Rounded.Warning,
            color = MnnTextColor.secondary,
        )
    }
}

@Composable
private fun ProviderEditor(
    provider: ApiProvider,
    presets: List<Pair<String, String>>,
    testing: Boolean,
    testResult: String?,
    onChange: (ApiProvider) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val nameState = rememberProviderField(provider.id, "name", provider.name)
    val urlState = rememberProviderField(provider.id, "url", provider.baseUrl)
    val keyState = rememberProviderField(provider.id, "key", provider.apiKey)
    val llmState = rememberProviderField(provider.id, "llm", provider.llmModel)
    val asrState = rememberProviderField(provider.id, "asr", provider.asrModel)
    val ttsState = rememberProviderField(provider.id, "tts", provider.ttsModel)
    val voiceState = rememberProviderField(provider.id, "voice", provider.ttsVoice)
    val imgState = rememberProviderField(provider.id, "img", provider.imageModel)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MnnRadii.medium))
            .padding(vertical = MnnSpacing.inline),
        verticalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
    ) {
        Text("编辑：${provider.name.ifBlank { "未命名" }}", style = MiuixTheme.textStyles.title2)

        EditField("名称", nameState) { onChange(provider.copy(name = it)) }
        EditField("Base URL（含 /v1）", urlState) { onChange(provider.copy(baseUrl = it)) }

        // baseUrl 预设：这是最容易填错的一项
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MnnSpacing.tight)) {
            items(presets, key = { it.first }) { (label, url) ->
                Card(
                    modifier = Modifier.clickable {
                        urlState.setTextAndPlaceCursorAtEnd(url)
                        onChange(provider.copy(baseUrl = url, name = provider.name.ifBlank { label }))
                    },
                ) {
                    Text(
                        label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        EditField("API Key", keyState) { onChange(provider.copy(apiKey = it)) }

        Text("能力（留空表示不用这项）", style = MiuixTheme.textStyles.footnote1)
        EditField("对话模型名，如 gpt-4o-mini", llmState) { onChange(provider.copy(llmModel = it)) }
        EditField("语音识别模型名，如 whisper-1", asrState) { onChange(provider.copy(asrModel = it)) }
        EditField("语音合成模型名，如 tts-1", ttsState) { onChange(provider.copy(ttsModel = it)) }
        EditField("语音音色，如 alloy / nova", voiceState) { onChange(provider.copy(ttsVoice = it)) }
        EditField("生图模型名，如 dall-e-3", imgState) { onChange(provider.copy(imageModel = it)) }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
        ) {
            MnnButton(
                onClick = onTest,
                enabled = !testing,
                modifier = Modifier.weight(1f),
                style = MnnButtonStyle.Primary,
                content = {
                    Text(if (testing) "测试中…" else "测试连接")
                },
            )
            MnnButton(
                onClick = onDelete,
                style = MnnButtonStyle.Primary,
                content = {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Text(" 删除")
                },
            )
        }

        testResult?.let {
            Text(
                it,
                style = MiuixTheme.textStyles.footnote1,
                color = if (it.startsWith("连接成功")) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.error
                },
            )
        }

        provider.validationError()?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
    }
}

/**
 * `TextFieldState` 不能预填（它没有 value 参数），所以要按 provider + 字段建一次。
 *
 * key 里带 [providerId]，切换编辑对象时会重建，避免把 A 的 key 显示到 B 上。
 */
@Composable
private fun rememberProviderField(
    providerId: String,
    field: String,
    initial: String,
): TextFieldState {
    val state = androidx.compose.foundation.text.input.rememberTextFieldState()
    androidx.compose.runtime.LaunchedEffect(providerId, field) {
        state.setTextAndPlaceCursorAtEnd(initial)
    }
    return state
}

/**
 * 一个带标签的输入框。
 *
 * 用 `LaunchedEffect(text)` 把输入回写到上层 —— `TextField` 没有 `onValueChange`
 * （它是 state-driven 的新 API），只能这样同步。
 */
@Composable
private fun EditField(
    label: String,
    state: TextFieldState,
    onCommit: (String) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(state.text.toString()) {
        onCommit(state.text.toString())
    }
    TextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        useLabelAsPlaceholder = true,
    )
}
