package com.mnnkit.app.data.memory

import com.mnnkit.app.data.AppSettings
import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.LlmEngine
import com.mnnkit.core.memory.MemoryEntry
import com.mnnkit.core.memory.MemoryHit
import com.mnnkit.core.memory.MemoryKind
import com.mnnkit.core.memory.MemoryQuery

/**
 * 记忆库的业务层：把 [MemoryStore] 的存储能力与 LLM 的 embedding 能力组合起来，
 * 提供"记住 / 回忆 / 注入上下文 / 自动抽取"四件事。
 *
 * 设计上刻意让 embedding 是可选的：
 *  - 引擎支持 embedding → 向量 + 关键词混合检索（语义召回）
 *  - 引擎不支持 → 纯关键词检索（FTS），功能依然可用
 * 这一点很重要，因为不是所有 MNN 模型都能抽 embedding。
 */
class MemoryManager(
    private val store: MemoryStore,
    private val settings: AppSettings,
) {

    private var lastInjectedIds: List<Long> = emptyList()

    /** 记忆总数。 */
    fun count(): Int = store.count()

    fun countByKind(): Map<MemoryKind, Int> = store.countByKind()

    fun list(kind: MemoryKind? = null, limit: Int = 500): List<MemoryEntry> = store.listAll(kind, limit)

    /**
     * 记住一条内容。若 [engine] 支持 embedding 则同时写入向量。
     */
    suspend fun remember(
        content: String,
        kind: MemoryKind = MemoryKind.FACT,
        source: String = "manual",
        tags: List<String> = emptyList(),
        importance: Float = 0.6f,
        engine: LlmEngine? = null,
    ): Long {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return -1L
        val emb = embed(engine, trimmed)
        return store.insertIfAbsent(kind, trimmed, source, tags, importance, emb)
    }

    /**
     * 从一轮对话里自动抽取值得记忆的内容。
     *
     * 这里用的是**轻量启发式规则**而不是额外跑一次 LLM 归纳：
     * 端侧跑一次归纳要几秒且要额外显存，而规则命中率对"我叫X""记住X""我喜欢X"
     * 这类明确指令已经足够。用户也可以手动添加记忆。
     */
    suspend fun autoExtractFromTurn(
        userText: String,
        assistantText: String,
        engine: LlmEngine?,
    ): Int {
        if (!settings.state.value.memoryAutoWrite) return 0
        var n = 0

        // 1) 显式记忆指令
        val explicit = EXPLICIT_PATTERNS.mapNotNull { it.find(userText)?.groupValues?.getOrNull(1) }
            .map { it.trim() }
            .filter { it.length in 2..200 }
        explicit.forEach { fact ->
            if (remember(fact, MemoryKind.FACT, "explicit", listOf("用户指定"), 0.9f, engine) > 0) n++
        }

        // 2) 自我介绍类陈述
        if (SELF_INTRO.containsMatchIn(userText) && userText.length <= 200) {
            if (remember(userText.trim(), MemoryKind.PREFERENCE, "self-intro", listOf("自我介绍"), 0.8f, engine) > 0) {
                n++
            }
        }

        // 3) 长回复的摘要（只记首句，避免记忆库被冗长文本淹没）
        if (assistantText.length > 400) {
            val summary = assistantText.substringBefore('。').substringBefore('\n').take(200).trim()
            if (summary.length > 20) {
                if (remember(summary, MemoryKind.SUMMARY, "auto-summary", emptyList(), 0.4f, engine) > 0) n++
            }
        }
        return n
    }

    /**
     * 为当前用户输入检索相关记忆，并渲染成可注入 system prompt 的文本。
     * 返回 null 表示没有可用记忆。
     */
    suspend fun buildMemoryContext(userText: String, engine: LlmEngine?): String? {
        if (!settings.state.value.memoryEnabled) return null
        val topK = settings.state.value.memoryTopK
        if (topK <= 0) return null

        val queryEmb = embed(engine, userText)
        val query = MemoryQuery(
            text = userText,
            topK = topK,
            useVector = queryEmb != null,
            minScore = 0.2f,
        )
        val hits = store.search(query, queryEmb)
        if (hits.isEmpty()) {
            lastInjectedIds = emptyList()
            return null
        }
        lastInjectedIds = hits.map { it.entry.id }
        store.touch(lastInjectedIds)
        return render(hits)
    }

    /** 把检索结果渲染成 prompt 片段。 */
    private fun render(hits: List<MemoryHit>): String = buildString {
        appendLine("以下是关于用户的已知信息，请在回答时自然地参考（不要机械复述）：")
        hits.forEach { hit ->
            append("- [").append(hit.entry.kind.label).append("] ").appendLine(hit.entry.content)
        }
    }

    /** 构造带记忆的完整消息序列。 */
    fun withMemoryContext(
        history: List<ChatMessage>,
        memoryContext: String?,
        systemPrompt: String,
    ): List<ChatMessage> {
        val sys = if (memoryContext.isNullOrBlank()) {
            systemPrompt
        } else {
            systemPrompt + "\n\n" + memoryContext
        }
        return buildList {
            add(ChatMessage(ChatMessage.Role.SYSTEM, sys))
            addAll(history.filter { it.role != ChatMessage.Role.SYSTEM })
        }
    }

    /** 最近一次注入的记忆 id，供 UI 展示"本轮参考了哪些记忆"。 */
    fun lastInjected(): List<Long> = lastInjectedIds

    fun delete(id: Long) = store.delete(id)

    fun clear() = store.clear()

    fun exportJson(): String = store.exportJson()

    fun importJson(text: String): Int = store.importJson(text)

    /** 为没有 embedding 的旧记忆补齐向量（后台批量执行）。 */
    suspend fun backfillEmbeddings(engine: LlmEngine?, limit: Int = 50): Int {
        if (engine == null || !engine.isAvailable) return 0
        var n = 0
        for (entry in store.listAll(limit = 200)) {
            if (n >= limit) break
            if (entry.embedding != null) continue
            val emb = embed(engine, entry.content) ?: break
            store.updateEmbedding(entry.id, emb)
            n++
        }
        return n
    }

    private suspend fun embed(engine: LlmEngine?, text: String): FloatArray? {
        if (engine == null || !engine.isAvailable) return null
        return runCatching { engine.embed(text.take(2048)) }.getOrNull()
    }

    private companion object {
        val EXPLICIT_PATTERNS = listOf(
            Regex("记住[：:，,]?\\s*(.+)"),
            Regex("请记住[：:，,]?\\s*(.+)"),
            Regex("remember that\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("我的名字是\\s*(.+)"),
            Regex("我叫\\s*(.+)"),
        )
        val SELF_INTRO = Regex("我(是|叫|喜欢|不喜欢|习惯|正在|住在|从事|想要)")
    }
}
