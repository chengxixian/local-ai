package com.mnnkit.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mnnkit.app.ui.screens.MnnCapsuleButton
import com.mnnkit.app.ui.theme.MnnSpacing
import com.mnnkit.app.ui.theme.MnnTextColor
import com.mnnkit.core.chat.ConversationArchiveStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConversationHistoryDialog(
    entries: List<ConversationArchiveStore.Entry>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ConversationArchiveStore.Entry?>(null) }
    if (pendingDelete != null) {
        Dialog(onDismissRequest = { pendingDelete = null }) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MnnSpacing.card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("删除这条对话？", color = MnnTextColor.primary)
                    Text("此操作不可撤销：${pendingDelete?.title}", color = MnnTextColor.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MnnCapsuleButton(text = "取消", onClick = { pendingDelete = null })
                        MnnCapsuleButton(text = "确认删除", emphasized = true, onClick = {
                            pendingDelete?.id?.let(onDelete)
                            pendingDelete = null
                        })
                    }
                }
            }
        }
    } else Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(MnnSpacing.card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("对话历史", style = MiuixTheme.textStyles.headline2, color = MnnTextColor.primary)
                if (entries.isEmpty()) {
                    Text("暂无历史对话。开始新对话时，当前会话会自动归档。", color = MnnTextColor.secondary)
                } else {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        entries.forEach { entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(entry.title, color = MnnTextColor.primary, maxLines = 2)
                                    val whenSaved = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(entry.updatedAt))
                                    Text("$whenSaved · ${entry.messageCount} 条消息", color = MnnTextColor.secondary)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MnnCapsuleButton(text = "打开", onClick = { onOpen(entry.id) })
                                    MnnCapsuleButton(text = "删除", onClick = { pendingDelete = entry })
                                }
                            }
                        }
                    }
                }
                MnnCapsuleButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}
