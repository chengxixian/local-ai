package com.mnnkit.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * [ConversationStore] 的测试。
 *
 * 这是「应用一退出上下文就没了」的回归测试：只要 [ConversationStore.save] /
 * [load] 的往返不再成立，这里就会红。
 *
 * 覆盖的都是实际会踩到的点：中文与 emoji、JSON 转义、空回复不落盘、
 * 文件损坏不抛异常、超上限裁剪、以及真正的**文件读写往返**。
 */
class ConversationStoreTest {

    private fun msg(
        role: String,
        text: String,
        reasoning: String? = null,
        imagePath: String? = null,
        audioPath: String? = null,
    ) = ConversationStore.StoredMessage(role, text, reasoning, imagePath, audioPath)

    // ───────────────────────── 序列化往返（纯逻辑） ─────────────────────────

    @Test
    fun `round trips plain messages`() {
        val src = listOf(
            msg("user", "你好"),
            msg("assistant", "你好，有什么可以帮你？"),
        )
        val back = ConversationStore.decode(ConversationStore.encode(src))
        assertEquals(2, back.messages.size)
        assertEquals("user", back.messages[0].role)
        assertEquals("你好", back.messages[0].text)
        assertEquals("你好，有什么可以帮你？", back.messages[1].text)
    }

    @Test
    fun `round trips reasoning and media paths`() {
        val src = listOf(
            msg(
                role = "assistant",
                text = "答案是 42。",
                reasoning = "先想想…… 42 是经典答案。",
                imagePath = "/storage/emulated/0/Android/data/com.mnnkit.app/files/images/a.png",
                audioPath = "/storage/emulated/0/Android/data/com.mnnkit.app/files/audio/b.mp3",
            ),
        )
        val back = ConversationStore.decode(ConversationStore.encode(src)).messages.single()
        assertEquals("答案是 42。", back.text)
        assertEquals("先想想…… 42 是经典答案。", back.reasoning)
        assertTrue(back.imagePath!!.endsWith("a.png"))
        assertTrue(back.audioPath!!.endsWith("b.mp3"))
    }

    @Test
    fun `escapes quotes newlines and backslashes`() {
        val tricky = "他说：\"换行\n在这里\"，还有反斜杠 \\ 和制表\t符 😀"
        val back = ConversationStore.decode(
            ConversationStore.encode(listOf(msg("user", tricky))),
        ).messages.single()
        assertEquals(tricky, back.text)
    }

    @Test
    fun `drops empty assistant bubbles`() {
        val src = listOf(
            msg("user", "在吗"),
            // 生成中途被杀留下的空气泡 —— 不该落盘，否则重启后看到空壳
            msg("assistant", ""),
            msg("assistant", "在的"),
        )
        val back = ConversationStore.decode(ConversationStore.encode(src))
        assertEquals(2, back.messages.size, "空气泡应被过滤掉")
        assertEquals("在的", back.messages[1].text)
    }

    @Test
    fun `keeps image only reply`() {
        // 文生图的回复可能只有图片没有文字，这种要保留
        val back = ConversationStore.decode(
            ConversationStore.encode(listOf(msg("assistant", "", imagePath = "/x/y.png"))),
        )
        assertEquals(1, back.messages.size)
        assertEquals("/x/y.png", back.messages[0].imagePath)
    }

    @Test
    fun `keeps reasoning only reply`() {
        // 推理吃满 max_tokens、正文为空的情况：思考内容仍要留下
        val back = ConversationStore.decode(
            ConversationStore.encode(listOf(msg("assistant", "", reasoning = "想了很多"))),
        )
        assertEquals(1, back.messages.size)
        assertEquals("想了很多", back.messages[0].reasoning)
    }

    @Test
    fun `truncates to max messages keeping the newest`() {
        val many = (1..ConversationStore.MAX_MESSAGES + 50).map { msg("user", "第 $it 条") }
        val back = ConversationStore.decode(ConversationStore.encode(many))
        assertEquals(ConversationStore.MAX_MESSAGES, back.messages.size)
        // 保留的应该是**最后** MAX_MESSAGES 条
        assertEquals("第 ${ConversationStore.MAX_MESSAGES + 50} 条", back.messages.last().text)
    }

    @Test
    fun `corrupt json yields empty snapshot with error`() {
        val snap = ConversationStore.decode("{ this is not json")
        assertTrue(snap.messages.isEmpty())
        assertNotNull(snap.error, "损坏时应给出 error 说明")
    }

    @Test
    fun `empty input yields clean empty snapshot`() {
        val snap = ConversationStore.decode("")
        assertTrue(snap.messages.isEmpty())
        assertNull(snap.error)
    }

    @Test
    fun `skips entries missing role`() {
        val snap = ConversationStore.decode(
            """{"version":1,"updatedAt":123,"messages":[{"text":"没有 role"},{"role":"user","text":"有 role"}]}""",
        )
        assertEquals(1, snap.messages.size)
        assertEquals("有 role", snap.messages[0].text)
        assertEquals(123L, snap.updatedAt)
    }

    @Test
    fun `round trips metrics for both sources without affecting old messages`() {
        val native = assertNotNull(GenerationMetrics.fromNativeDecode(100, 2_000_000))
        val api = assertNotNull(GenerationMetrics.fromApiUsage(30, 3_000_000_000))
        val src = listOf(
            msg("user", "hi"),
            msg("assistant", "local").copy(generationMetrics = native),
            msg("assistant", "cloud").copy(generationMetrics = api),
        )
        assertEquals(src, ConversationStore.decode(ConversationStore.encode(src)).messages)
        val legacy = ConversationStore.decode("""{"version":1,"messages":[{"role":"assistant","text":"old"}]}""")
        assertNull(legacy.messages.single().generationMetrics)
    }

    @Test
    fun `malformed optional metrics do not discard the message`() {
        val invalid = listOf(
            "null", "{}",
            """{"completionTokens":-1,"durationSeconds":2,"source":"NATIVE_DECODE"}""",
            """{"completionTokens":1.5,"durationSeconds":2,"source":"NATIVE_DECODE"}""",
            """{"completionTokens":4,"durationSeconds":0,"source":"NATIVE_DECODE"}""",
            """{"completionTokens":4,"durationSeconds":-1,"source":"NATIVE_DECODE"}""",
            """{"completionTokens":4,"durationSeconds":"NaN","source":"NATIVE_DECODE"}""",
            """{"completionTokens":4,"durationSeconds":1e309,"source":"NATIVE_DECODE"}""",
            """{"completionTokens":4,"durationSeconds":2,"source":"future"}""",
            """{"completionTokens":"4","durationSeconds":2,"source":"API_WALL_CLOCK"}""",
            """{"completionTokens":9223372036854775807,"durationSeconds":2,"source":"NATIVE_DECODE"}""",
        )
        for (metrics in invalid) {
            val snapshot = ConversationStore.decode(
                """{"version":1,"messages":[{"role":"assistant","text":"kept","generationMetrics":$metrics}]}""",
            )
            assertNull(snapshot.error, metrics)
            assertEquals("kept", snapshot.messages.single().text)
            assertNull(snapshot.messages.single().generationMetrics, metrics)
        }
    }

    // ───────────────────────── 真实文件读写（「退出后还在吗」） ─────────────────────────

    @Test
    fun `survives a real file round trip`() {
        val dir = Files.createTempDirectory("mnnkit-conv").toFile()
        try {
            val store = ConversationStore(File(dir, "chat/conversation.json"))
            val src = listOf(
                msg("user", "记住我叫小明"),
                msg("assistant", "好的，小明。"),
                msg("user", "我叫什么？"),
                msg("assistant", "你叫小明。", reasoning = "上文说过"),
            )
            assertTrue(store.save(src), "写入应成功")

            // 用**新实例**读，模拟进程重启
            val reopened = ConversationStore(File(dir, "chat/conversation.json"))
            val back = reopened.load()
            assertNull(back.error)
            assertEquals(4, back.messages.size)
            assertEquals("记住我叫小明", back.messages[0].text)
            assertEquals("你叫小明。", back.messages[3].text)
            assertEquals("上文说过", back.messages[3].reasoning)
            assertTrue(back.updatedAt > 0, "updatedAt 应被写入")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing file yields empty snapshot not error`() {
        val dir = Files.createTempDirectory("mnnkit-conv-none").toFile()
        try {
            val store = ConversationStore(File(dir, "nope/conversation.json"))
            val snap = store.load()
            assertTrue(snap.messages.isEmpty())
            assertNull(snap.error, "文件不存在是正常情况，不该报错")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clear removes the file`() {
        val dir = Files.createTempDirectory("mnnkit-conv-clear").toFile()
        try {
            val file = File(dir, "conversation.json")
            val store = ConversationStore(file)
            store.save(listOf(msg("user", "hi")))
            assertTrue(file.isFile)
            assertTrue(store.clear())
            assertTrue(!file.exists(), "「新话题」后文件应被删除")
            assertTrue(store.load().messages.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `save creates parent directories`() {
        val dir = Files.createTempDirectory("mnnkit-conv-mkdir").toFile()
        try {
            // 目标目录还不存在 —— save 必须自己建出来，否则首次对话永远存不上
            val nested = File(dir, "a/b/c/conversation.json")
            val store = ConversationStore(nested)
            assertTrue(store.save(listOf(msg("user", "首次对话"))))
            assertTrue(nested.isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt file is kept on disk for manual recovery`() {
        val dir = Files.createTempDirectory("mnnkit-conv-corrupt").toFile()
        try {
            val file = File(dir, "conversation.json")
            file.writeText("{ broken")
            val snap = ConversationStore(file).load()
            assertNotNull(snap.error)
            assertTrue(file.isFile, "损坏的历史文件不应被自动删除，留给用户/开发者救回")
        } finally {
            dir.deleteRecursively()
        }
    }
}
