package com.mnnkit.core.chat

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationArchiveStoreTest {
    @Test fun `new chat archives previous messages and restores exact turn`() {
        val root = Files.createTempDirectory("chat-archive-").toFile()
        try {
            val active = ConversationStore(root.resolve("conversation.json"))
            val archive = ConversationArchiveStore(root.resolve("history"))
            val turn = listOf(
                ConversationStore.StoredMessage("user", "What's next?"),
                ConversationStore.StoredMessage("assistant", "Keep this."),
            )
            assertTrue(active.save(turn))
            val entry = assertNotNull(archive.archive(active.load().messages))
            assertTrue(active.clear())
            assertTrue(active.load().messages.isEmpty())
            assertEquals("What's next?", archive.list().single().title)
            assertEquals(turn, archive.load(entry.id)?.messages)
            assertTrue(active.save(assertNotNull(archive.load(entry.id)).messages))
            assertEquals(turn, active.load().messages)
        } finally { root.deleteRecursively() }
    }

    @Test fun `reopening retains history entry and preserves identity`() {
        val root = Files.createTempDirectory("chat-reopen-").toFile()
        try {
            val active = ConversationStore(root.resolve("conversation.json"))
            val history = ConversationArchiveStore(root.resolve("history"))
            val first = listOf(ConversationStore.StoredMessage("user", "First session"))
            val second = listOf(ConversationStore.StoredMessage("user", "Second session"))
            val firstId = "00000000-0000-0000-0000-000000000001.json"
            val secondId = "00000000-0000-0000-0000-000000000002.json"
            assertTrue(active.save(first, firstId))
            assertNotNull(history.archive(active.load().messages, firstId))
            assertTrue(active.clear())
            assertTrue(active.save(second, secondId))
            val reopened = assertNotNull(history.load(firstId))
            assertNotNull(history.archive(active.load().messages, secondId))
            assertTrue(active.save(reopened.messages, firstId))
            assertEquals(firstId, active.load().conversationId)
            assertEquals(first, active.load().messages)
            assertEquals(setOf(firstId, secondId), history.list().map { it.id }.toSet())
            assertTrue(history.delete(secondId))
            assertNull(history.load(secondId))
            assertEquals(listOf(firstId), history.list().map { it.id })
            assertEquals(first, active.load().messages)
        } finally { root.deleteRecursively() }
    }

    @Test fun `invalid path and empty turns never create history`() {
        val root = Files.createTempDirectory("chat-archive-").toFile()
        try {
            val archive = ConversationArchiveStore(root.resolve("history"))
            assertNull(archive.archive(emptyList()))
            assertNull(archive.archive(listOf(ConversationStore.StoredMessage("assistant", ""))))
            assertNull(archive.load("../conversation.json"))
            assertTrue(archive.list().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
