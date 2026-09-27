package com.mnnkit.core.memory

import kotlin.math.sqrt

/**
 * 轻量向量索引：归一化后暴力余弦相似度检索。
 *
 * 为什么不引第三方向量库：端侧记忆库的量级是几千到几万条，
 * 暴力检索在手机上只需几毫秒，而引入 HNSW/faiss 的 native 依赖
 * 会显著增加包体与构建复杂度，收益不成比例。
 *
 * 所有向量在写入时即做 L2 归一化，检索时点积即余弦相似度。
 */
class VectorIndex(private val dim: Int) {

    private val ids = ArrayList<Long>()
    private val vectors = ArrayList<FloatArray>()

    val size: Int get() = ids.size

    val dimension: Int get() = dim

    fun clear() {
        ids.clear()
        vectors.clear()
    }

    /** 加入一条向量；维度不匹配会抛异常（尽早暴露 bug）。 */
    fun add(id: Long, vector: FloatArray) {
        require(vector.size == dim) { "向量维度不匹配：期望 $dim，实际 ${vector.size}" }
        ids.add(id)
        vectors.add(normalize(vector))
    }

    fun remove(id: Long) {
        val idx = ids.indexOf(id)
        if (idx >= 0) {
            ids.removeAt(idx)
            vectors.removeAt(idx)
        }
    }

    /**
     * 检索最相似的 [topK] 条。
     * @return (id, similarity) 列表，按相似度降序；相似度范围 [-1, 1]。
     */
    fun search(query: FloatArray, topK: Int = 5, minScore: Float = 0.0f): List<Pair<Long, Float>> {
        if (size == 0) return emptyList()
        require(query.size == dim) { "查询向量维度不匹配：期望 $dim，实际 ${query.size}" }
        val q = normalize(query)

        // 小顶堆维护 topK，避免全量排序
        val heap = java.util.PriorityQueue<Pair<Long, Float>>(compareBy { it.second })
        for (i in ids.indices) {
            val score = dot(q, vectors[i])
            if (score < minScore) continue
            if (heap.size < topK) {
                heap.add(ids[i] to score)
            } else if (score > heap.peek().second) {
                heap.poll()
                heap.add(ids[i] to score)
            }
        }
        return heap.sortedByDescending { it.second }
    }

    /** 导出用于持久化：(id, 归一化向量) */
    fun snapshot(): List<Pair<Long, FloatArray>> = ids.indices.map { ids[it] to vectors[it] }

    /** 从持久化数据恢复。 */
    fun restore(entries: List<Pair<Long, FloatArray>>) {
        clear()
        entries.forEach { (id, v) -> add(id, v) }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val norm = sqrt(sum)
        if (norm < 1e-9) return v.copyOf()
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = (v[i] / norm).toFloat()
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        /** 直接计算两个向量的余弦相似度（不要求已归一化）。 */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "维度不一致：${a.size} vs ${b.size}" }
            var d = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                d += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom < 1e-9) 0f else (d / denom).toFloat()
        }
    }
}
