package com.mnnkit.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class VectorIndexTest {

    @Test
    fun `cosine of identical vectors is 1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, VectorIndex.cosine(a, a.copyOf()), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is 0`() {
        assertEquals(0f, VectorIndex.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
    }

    @Test
    fun `cosine of opposite vectors is -1`() {
        assertEquals(-1f, VectorIndex.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-5f)
    }

    @Test
    fun `cosine is scale invariant`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f)
        assertEquals(1f, VectorIndex.cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine handles zero vector without NaN`() {
        val z = VectorIndex.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
        assertTrue(!z.isNaN(), "零向量不应产生 NaN")
        assertEquals(0f, z, 1e-6f)
    }

    @Test
    fun `cosine rejects mismatched dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            VectorIndex.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f))
        }
    }

    @Test
    fun `search ranks the closest vector first`() {
        val idx = VectorIndex(dim = 3)
        idx.add(1L, floatArrayOf(1f, 0f, 0f))
        idx.add(2L, floatArrayOf(0f, 1f, 0f))
        idx.add(3L, floatArrayOf(0.9f, 0.1f, 0f))

        val hits = idx.search(floatArrayOf(1f, 0f, 0f), topK = 3)
        assertEquals(3, hits.size)
        assertEquals(1L, hits[0].first, "完全一致的向量应排第一")
        assertEquals(3L, hits[1].first, "次相近的应排第二")
        assertEquals(2L, hits[2].first, "正交的应排最后")
        assertTrue(hits[0].second > hits[1].second)
        assertTrue(hits[1].second > hits[2].second)
    }

    @Test
    fun `search respects topK`() {
        val idx = VectorIndex(dim = 2)
        for (i in 1..20) idx.add(i.toLong(), floatArrayOf(i.toFloat(), 1f))
        assertEquals(5, idx.search(floatArrayOf(1f, 1f), topK = 5).size)
    }

    @Test
    fun `search respects minScore filter`() {
        val idx = VectorIndex(dim = 2)
        idx.add(1L, floatArrayOf(1f, 0f))
        idx.add(2L, floatArrayOf(0f, 1f))
        val hits = idx.search(floatArrayOf(1f, 0f), topK = 10, minScore = 0.5f)
        assertEquals(1, hits.size)
        assertEquals(1L, hits[0].first)
    }

    @Test
    fun `search on empty index returns empty`() {
        assertTrue(VectorIndex(dim = 4).search(floatArrayOf(1f, 2f, 3f, 4f)).isEmpty())
    }

    @Test
    fun `add rejects wrong dimension`() {
        val idx = VectorIndex(dim = 3)
        assertFailsWith<IllegalArgumentException> { idx.add(1L, floatArrayOf(1f, 2f)) }
    }

    @Test
    fun `remove drops the entry from results`() {
        val idx = VectorIndex(dim = 2)
        idx.add(1L, floatArrayOf(1f, 0f))
        idx.add(2L, floatArrayOf(0.5f, 0.5f))
        assertEquals(2, idx.size)
        idx.remove(1L)
        assertEquals(1, idx.size)
        val hits = idx.search(floatArrayOf(1f, 0f), topK = 5)
        assertEquals(1, hits.size)
        assertEquals(2L, hits[0].first)
    }

    @Test
    fun `snapshot and restore round trip preserves ranking`() {
        val idx = VectorIndex(dim = 3)
        idx.add(10L, floatArrayOf(1f, 2f, 3f))
        idx.add(20L, floatArrayOf(3f, 2f, 1f))
        val snap = idx.snapshot()

        val restored = VectorIndex(dim = 3)
        restored.restore(snap)
        assertEquals(2, restored.size)

        val q = floatArrayOf(1f, 2f, 3f)
        assertEquals(idx.search(q, 2).map { it.first }, restored.search(q, 2).map { it.first })
    }

    @Test
    fun `topK results are ordered by descending score even with heap`() {
        val idx = VectorIndex(dim = 4)
        val rnd = java.util.Random(42)
        for (i in 1..500) {
            idx.add(i.toLong(), FloatArray(4) { rnd.nextFloat() })
        }
        val q = FloatArray(4) { rnd.nextFloat() }
        val hits = idx.search(q, topK = 12)
        assertEquals(12, hits.size)
        for (i in 0 until hits.size - 1) {
            assertTrue(hits[i].second >= hits[i + 1].second, "结果必须按分数降序")
        }
        // 与朴素全排序对比，确认堆实现没有丢结果
        val all = idx.snapshot().map { (id, v) -> id to VectorIndex.cosine(q, v) }
            .sortedByDescending { it.second }.take(12).map { it.first }.toSet()
        assertEquals(all, hits.map { it.first }.toSet())
    }
}
