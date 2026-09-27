package com.mnnkit.app.data.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.memory.MemoryEntry
import com.mnnkit.core.memory.MemoryHit
import com.mnnkit.core.memory.MemoryKind
import com.mnnkit.core.memory.MemoryQuery
import com.mnnkit.core.memory.VectorIndex

/**
 * 端侧长期记忆库。
 *
 * 双通道检索：
 *  1. **向量通道** —— 记忆写入时用 LLM 抽 embedding，存在 BLOB 里，
 *     检索时用 [VectorIndex] 做余弦相似度（内存中的归一化缓存，千级条目毫秒级）。
 *  2. **关键词通道** —— SQLite FTS4 全文检索，作为 LLM 不支持 embedding 时的
 *     降级路径，也用于混合排序。
 *
 * 中文分词：FTS4 默认分词器对中文按整串处理，效果差。这里的做法是把内容
 * 同时写入一个"字符级"检索列（对中文按单字切分、对英文按词切分），
 * 从而绕开分词器依赖。
 */
class MemoryStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private var index: VectorIndex = VectorIndex(0)
    private var indexDim: Int = 0
    private var indexLoaded = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '',
                importance REAL NOT NULL DEFAULT 0.5,
                created_at INTEGER NOT NULL,
                last_access INTEGER NOT NULL,
                access_count INTEGER NOT NULL DEFAULT 0,
                embedding BLOB
            )
            """.trimIndent()
        )
        // 字符级倒排：把内容切成"单字 + 词"用空格拼接，FTS4 即可命中中文
        db.execSQL(
            """
            CREATE VIRTUAL TABLE memories_fts USING fts4(
                content_tokens,
                tokenize=simple
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memories_kind ON memories(kind)")
        db.execSQL("CREATE INDEX idx_memories_created ON memories(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 记忆库是用户数据，升级不应丢数据；此处仅做增量迁移
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0") }
        }
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 新增一条记忆，返回自增 id。 */
    fun insert(
        kind: MemoryKind,
        content: String,
        source: String = "",
        tags: List<String> = emptyList(),
        importance: Float = 0.5f,
        embedding: FloatArray? = null,
    ): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("kind", kind.id)
            put("content", content)
            put("source", source)
            put("tags", tags.joinToString(","))
            put("importance", importance)
            put("created_at", now)
            put("last_access", now)
            put("access_count", 0)
            put("embedding", embedding?.let { floatsToBytes(it) })
        }
        val id = db.insert("memories", null, values)
        if (id > 0) {
            db.execSQL(
                "INSERT INTO memories_fts(rowid, content_tokens) VALUES (?, ?)",
                arrayOf<Any>(id, tokenize(content)),
            )
            if (embedding != null) {
                ensureIndex(embedding.size)
                index.add(id, embedding)
            }
        }
        return id
    }

    /** 若同内容已存在则跳过，避免重复写入刷屏。 */
    fun insertIfAbsent(
        kind: MemoryKind,
        content: String,
        source: String = "",
        tags: List<String> = emptyList(),
        importance: Float = 0.5f,
        embedding: FloatArray? = null,
    ): Long {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return -1L
        if (existsWithContent(trimmed)) return -1L
        return insert(kind, trimmed, source, tags, importance, embedding)
    }

    private fun existsWithContent(content: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM memories WHERE content = ? LIMIT 1",
            arrayOf(content),
        ).use { it.moveToFirst() }

    fun updateEmbedding(id: Long, embedding: FloatArray) {
        writableDatabase.execSQL(
            "UPDATE memories SET embedding = ? WHERE id = ?",
            arrayOf(floatsToBytes(embedding), id),
        )
        ensureIndex(embedding.size)
        index.add(id, embedding)
    }

    fun delete(id: Long) {
        writableDatabase.delete("memories", "id = ?", arrayOf(id.toString()))
        runCatching { writableDatabase.execSQL("DELETE FROM memories_fts WHERE rowid = ?", arrayOf(id)) }
        if (indexLoaded) index.remove(id)
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM memories")
        runCatching { writableDatabase.execSQL("DELETE FROM memories_fts") }
        index.clear()
        indexLoaded = false
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    /**
     * 混合检索：向量通道与关键词通道各取一批，按分数归并。
     * [queryEmbedding] 为 null 时只走关键词通道。
     */
    fun search(query: MemoryQuery, queryEmbedding: FloatArray?): List<MemoryHit> {
        val vectorHits = if (query.useVector && queryEmbedding != null) {
            searchByVector(queryEmbedding, query)
        } else {
            emptyList()
        }
        val keywordHits = searchByKeyword(query)

        val merged = LinkedHashMap<Long, MemoryHit>()
        // 向量结果优先（语义更准）
        vectorHits.forEach { merged[it.entry.id] = it }
        keywordHits.forEach { hit ->
            val existing = merged[hit.entry.id]
            merged[hit.entry.id] = if (existing == null) {
                hit
            } else {
                // 两路都命中：加权融合并标记为 HYBRID
                existing.copy(
                    score = existing.score * 0.7f + hit.score * 0.3f,
                    matchedBy = MemoryHit.MatchType.HYBRID,
                )
            }
        }

        return merged.values
            .filter { it.entry.importance >= 0f }
            .sortedByDescending { it.score + it.entry.importance * 0.1f }
            .take(query.topK)
    }

    private fun searchByVector(queryEmbedding: FloatArray, q: MemoryQuery): List<MemoryHit> {
        ensureIndex(queryEmbedding.size)
        val raw = index.search(queryEmbedding, topK = q.topK * 3, minScore = 0.0f)
        if (raw.isEmpty()) return emptyList()
        val byId = loadByIds(raw.map { it.first })
        return raw.mapNotNull { (id, score) ->
            val entry = byId[id] ?: return@mapNotNull null
            if (q.kinds.isNotEmpty() && entry.kind !in q.kinds) return@mapNotNull null
            if (score < q.minScore) return@mapNotNull null
            MemoryHit(entry, score, MemoryHit.MatchType.VECTOR)
        }
    }

    private fun searchByKeyword(q: MemoryQuery): List<MemoryHit> {
        val tokens = tokenize(q.text)
        if (tokens.isBlank()) return emptyList()
        val matchExpr = tokens.split(' ').filter { it.isNotBlank() }.joinToString(" OR ") { "$it*" }
        if (matchExpr.isBlank()) return emptyList()

        val sql = """
            SELECT m.id, m.kind, m.content, m.source, m.tags, m.importance,
                   m.created_at, m.last_access, m.access_count, m.embedding
            FROM memories_fts f
            JOIN memories m ON m.id = f.rowid
            WHERE memories_fts MATCH ?
            ORDER BY m.importance DESC, m.last_access DESC
            LIMIT ?
        """.trimIndent()

        return runCatching {
            readableDatabase.rawQuery(sql, arrayOf(matchExpr, (q.topK * 3).toString())).use { c ->
                val out = ArrayList<MemoryHit>()
                var rank = 0
                while (c.moveToNext()) {
                    val entry = readEntry(c)
                    if (q.kinds.isNotEmpty() && entry.kind !in q.kinds) continue
                    // FTS 没有相关性分值，用排名衰减近似
                    val score = (1.0f - rank * 0.08f).coerceAtLeast(0.2f)
                    out.add(MemoryHit(entry, score, MemoryHit.MatchType.KEYWORD))
                    rank++
                }
                out
            }
        }.getOrElse { emptyList() }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    fun listAll(kind: MemoryKind? = null, limit: Int = 500): List<MemoryEntry> {
        val sql = buildString {
            append("SELECT * FROM memories")
            if (kind != null) append(" WHERE kind = ?")
            append(" ORDER BY created_at DESC LIMIT ?")
        }
        val args = if (kind != null) arrayOf(kind.id, limit.toString()) else arrayOf(limit.toString())
        return readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<MemoryEntry>()
            while (c.moveToNext()) out.add(readEntry(c))
            out
        }
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memories", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun countByKind(): Map<MemoryKind, Int> {
        val out = LinkedHashMap<MemoryKind, Int>()
        readableDatabase.rawQuery("SELECT kind, COUNT(*) FROM memories GROUP BY kind", null).use { c ->
            while (c.moveToNext()) out[MemoryKind.fromId(c.getString(0))] = c.getInt(1)
        }
        return out
    }

    /** 标记被访问，用于"最近常用"排序与遗忘策略。 */
    fun touch(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach {
                db.execSQL(
                    "UPDATE memories SET last_access = ?, access_count = access_count + 1 WHERE id = ?",
                    arrayOf(System.currentTimeMillis(), it),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun loadByIds(ids: List<Long>): Map<Long, MemoryEntry> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT * FROM memories WHERE id IN ($placeholders)"
        return readableDatabase.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { c ->
            val out = HashMap<Long, MemoryEntry>()
            while (c.moveToNext()) {
                val e = readEntry(c)
                out[e.id] = e
            }
            out
        }
    }

    private fun readEntry(c: Cursor): MemoryEntry {
        val tagsRaw = c.getString(c.getColumnIndexOrThrow("tags")).orEmpty()
        val embBytes = c.getBlob(c.getColumnIndexOrThrow("embedding"))
        return MemoryEntry(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            kind = MemoryKind.fromId(c.getString(c.getColumnIndexOrThrow("kind"))),
            content = c.getString(c.getColumnIndexOrThrow("content")).orEmpty(),
            source = c.getString(c.getColumnIndexOrThrow("source")).orEmpty(),
            tags = if (tagsRaw.isBlank()) emptyList() else tagsRaw.split(","),
            importance = c.getFloat(c.getColumnIndexOrThrow("importance")),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at")),
            lastAccessMs = c.getLong(c.getColumnIndexOrThrow("last_access")),
            accessCount = c.getInt(c.getColumnIndexOrThrow("access_count")),
            embedding = embBytes?.let { bytesToFloats(it) },
        )
    }

    // ------------------------------------------------------------------
    // 向量索引生命周期
    // ------------------------------------------------------------------

    /** 从数据库重建内存索引（Engine 切换或进程重启后调用）。 */
    fun rebuildIndex() {
        val rows = readableDatabase.rawQuery(
            "SELECT id, embedding FROM memories WHERE embedding IS NOT NULL",
            null,
        ).use { c ->
            val out = ArrayList<Pair<Long, FloatArray>>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val b = c.getBlob(1) ?: continue
                out.add(id to bytesToFloats(b))
            }
            out
        }
        if (rows.isEmpty()) {
            index.clear()
            indexLoaded = false
            return
        }
        indexDim = rows.first().second.size
        index = VectorIndex(indexDim)
        index.restore(rows.filter { it.second.size == indexDim })
        indexLoaded = true
    }

    private fun ensureIndex(dim: Int) {
        if (!indexLoaded || indexDim != dim) {
            indexDim = dim
            index = VectorIndex(dim)
            // 只装载维度一致的条目
            val rows = readableDatabase.rawQuery(
                "SELECT id, embedding FROM memories WHERE embedding IS NOT NULL",
                null,
            ).use { c ->
                val out = ArrayList<Pair<Long, FloatArray>>()
                while (c.moveToNext()) {
                    val b = c.getBlob(1) ?: continue
                    val f = bytesToFloats(b)
                    if (f.size == dim) out.add(c.getLong(0) to f)
                }
                out
            }
            index.restore(rows)
            indexLoaded = true
        }
    }

    val isIndexReady: Boolean get() = indexLoaded

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 生成用于 FTS 的 token 串：
     *  - 中文/日文等 CJK 字符逐字拆分（FTS4 simple 分词器只按空白与标点切）
     *  - 拉丁字母与数字按词保留
     * 这样"我喜欢简洁的回答"会变成 "我 喜 欢 简 洁 的 回 答 我喜欢简洁的回答" 之类，
     * 单字查询即可命中。
     */
    internal fun tokenize(text: String): String {
        val sb = StringBuilder(text.length * 2)
        val word = StringBuilder()
        fun flushWord() {
            if (word.isNotEmpty()) {
                sb.append(word).append(' ')
                word.setLength(0)
            }
        }
        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushWord()
                    sb.append(ch).append(' ')
                }
                ch.isLetterOrDigit() -> word.append(ch.lowercaseChar())
                else -> flushWord()
            }
        }
        flushWord()
        // 同时加入原始整串（便于整句短语命中）与二元组，提高召回
        val cjkOnly = text.filter { isCjk(it) }
        if (cjkOnly.length >= 2) {
            for (i in 0 until cjkOnly.length - 1) {
                sb.append(cjkOnly, i, i + 2).append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||   // CJK 统一表意
            (code in 0x3400..0x4DBF) ||      // 扩展 A
            (code in 0x3040..0x30FF) ||      // 平假名/片假名
            (code in 0xAC00..0xD7AF)         // 韩文
    }

    private fun floatsToBytes(f: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(f.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        f.forEach { bb.putFloat(it) }
        return bb.array()
    }

    private fun bytesToFloats(b: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }

    /** 导出为 JSON，供"记忆库备份/导出"功能使用。 */
    fun exportJson(): String {
        val arr = listAll(limit = Int.MAX_VALUE).map { e ->
            Json.Obj(
                mapOf(
                    "kind" to Json.Str(e.kind.id),
                    "content" to Json.Str(e.content),
                    "source" to Json.Str(e.source),
                    "tags" to Json.Arr(e.tags.map { Json.Str(it) }),
                    "importance" to Json.Num(e.importance.toDouble()),
                    "createdAt" to Json.Num(e.createdAtMs.toDouble()),
                )
            )
        }
        return Json.Obj(mapOf("memories" to Json.Arr(arr))).stringify(pretty = true)
    }

    /** 从 JSON 恢复（embedding 需要重新计算，这里只恢复文本）。 */
    fun importJson(text: String): Int {
        val root = JsonParser.parseOrNull(text) ?: return 0
        val arr = root.arr("memories") ?: return 0
        var n = 0
        arr.forEach { node ->
            val content = node.str("content") ?: return@forEach
            val kind = MemoryKind.fromId(node.str("kind"))
            val id = insertIfAbsent(
                kind = kind,
                content = content,
                source = node.str("source") ?: "",
                tags = node.arr("tags")?.mapNotNull { it.asString } ?: emptyList(),
                importance = node.path("importance")?.asDouble?.toFloat() ?: 0.5f,
            )
            if (id > 0) n++
        }
        return n
    }

    companion object {
        private const val DB_NAME = "mnnkit_memory.db"
        private const val DB_VERSION = 1
    }
}
