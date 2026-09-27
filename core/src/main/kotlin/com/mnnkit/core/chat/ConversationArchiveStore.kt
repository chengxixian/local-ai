package com.mnnkit.core.chat

import java.io.File
import java.util.UUID

/** Durable collection of complete conversations, separate from the active conversation. */
class ConversationArchiveStore(private val directory: File) {
    data class Entry(val id: String, val title: String, val updatedAt: Long, val messageCount: Int)

    private fun file(id: String): File? =
        if (id.matches(Regex("[0-9a-fA-F-]{36}\\.json"))) File(directory, id) else null

    @Synchronized fun archive(messages: List<ConversationStore.StoredMessage>, conversationId: String? = null): Entry? {
        val persistable = messages.filter { it.isPersistable }
        if (persistable.isEmpty()) return null
        if (!directory.exists() && !directory.mkdirs()) return null
        val id = conversationId ?: UUID.randomUUID().toString() + ".json"
        val destination = file(id) ?: return null
        if (!ConversationStore(destination).save(persistable, id)) return null
        return entry(id, ConversationStore(destination).load())
    }

    /** Only inactive entries may be removed; the caller confirms before invoking this. */
    @Synchronized fun delete(id: String): Boolean {
        val target = file(id) ?: return false
        if (!target.isFile) return false
        return ConversationStore(target).clear()
    }

    @Synchronized fun list(): List<Entry> = directory.listFiles()
        ?.asSequence()?.filter { it.isFile && file(it.name) != null }
        ?.mapNotNull { f -> entry(f.name, ConversationStore(f).load()) }
        ?.sortedWith(compareByDescending<Entry> { it.updatedAt }.thenByDescending { it.id })
        ?.toList().orEmpty()

    @Synchronized fun load(id: String): ConversationStore.Snapshot? {
        val f = file(id) ?: return null
        if (!f.isFile) return null
        return ConversationStore(f).load().takeIf { it.error == null && it.messages.isNotEmpty() }
    }

    private fun entry(id: String, snapshot: ConversationStore.Snapshot): Entry? {
        if (snapshot.error != null || snapshot.messages.isEmpty()) return null
        val title = snapshot.messages.firstOrNull { it.role == "user" }?.text
            ?.replace(Regex("\\s+"), " ")?.take(48)?.ifBlank { null } ?: "未命名对话"
        return Entry(id, title, snapshot.updatedAt, snapshot.messages.size)
    }
}
