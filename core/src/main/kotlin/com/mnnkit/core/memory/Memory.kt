package com.mnnkit.core.memory

/** 记忆的种类，对应分层记忆设计。 */
enum class MemoryKind(val id: String, val label: String) {
    /** 用户显式提供的事实/偏好，例如"我叫小明""我喜欢简洁回答" */
    FACT("fact", "事实"),

    /** 从对话中抽取的摘要 */
    SUMMARY("summary", "摘要"),

    /** 情景记忆：某次对话的关键片段 */
    EPISODE("episode", "情景"),

    /** 用户长期偏好/习惯 */
    PREFERENCE("preference", "偏好"),

    /** 知识条目：从文档/Skill 导入的参考内容 */
    KNOWLEDGE("knowledge", "知识"),
    ;

    companion object {
        fun fromId(id: String?): MemoryKind =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FACT
    }
}

/**
 * 一条记忆。
 *
 * [embedding] 为可空：当 LLM 引擎不支持 embedding 时，
 * 记忆库退化为 FTS 关键词检索，功能仍可用。
 */
data class MemoryEntry(
    val id: Long = 0L,
    val kind: MemoryKind,
    val content: String,
    val source: String = "",
    val tags: List<String> = emptyList(),
    val importance: Float = 0.5f,
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastAccessMs: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val embedding: FloatArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is MemoryEntry && id == other.id

    override fun hashCode(): Int = id.hashCode()
}

/** 检索命中项，附带打分，便于 UI 展示"为什么想起来这条"。 */
data class MemoryHit(
    val entry: MemoryEntry,
    val score: Float,
    val matchedBy: MatchType,
) {
    enum class MatchType { VECTOR, KEYWORD, HYBRID }
}

/** 记忆库检索选项。 */
data class MemoryQuery(
    val text: String,
    val topK: Int = 5,
    val kinds: Set<MemoryKind> = emptySet(),
    val minScore: Float = 0.25f,
    /** 是否使用向量检索；引擎无 embedding 能力时自动忽略 */
    val useVector: Boolean = true,
)
