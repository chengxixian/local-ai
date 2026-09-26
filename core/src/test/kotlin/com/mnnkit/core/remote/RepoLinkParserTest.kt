package com.mnnkit.core.remote

import com.mnnkit.core.model.ModelSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepoLinkParserTest {

    @Test
    fun `parses huggingface repo page`() {
        val r = RepoLinkParser.parse("https://huggingface.co/Qwen/Qwen3-0.6B")
        assertTrue(r is RepoRef.HuggingFace)
        assertEquals("Qwen/Qwen3-0.6B", r.repoId)
        assertEquals("main", r.revision)
        assertEquals(ModelSource.HUGGINGFACE, r.source)
    }

    @Test
    fun `parses huggingface tree url with revision`() {
        val r = RepoLinkParser.parse("https://huggingface.co/taobao-mnn/Qwen3-0.6B-MNN/tree/main")
        assertTrue(r is RepoRef.HuggingFace)
        assertEquals("taobao-mnn/Qwen3-0.6B-MNN", r.repoId)
        assertEquals("main", r.revision)
    }

    @Test
    fun `parses huggingface resolve url and drops file path`() {
        val r = RepoLinkParser.parse("https://huggingface.co/taobao-mnn/Qwen3-0.6B-MNN/resolve/main/llm.mnn")
        assertTrue(r is RepoRef.HuggingFace)
        assertEquals("taobao-mnn/Qwen3-0.6B-MNN", r.repoId)
        assertEquals("main", r.revision)
    }

    @Test
    fun `parses huggingface blob url with branch revision`() {
        val r = RepoLinkParser.parse("https://huggingface.co/owner/model/blob/v1.2/config.json")
        assertTrue(r is RepoRef.HuggingFace)
        assertEquals("v1.2", r.revision)
    }

    @Test
    fun `parses hf-mirror url as mirrored`() {
        val r = RepoLinkParser.parse("https://hf-mirror.com/Qwen/Qwen3-0.6B")
        assertTrue(r is RepoRef.HuggingFace)
        assertTrue(r.mirrored)
        assertEquals(ModelSource.HF_MIRROR, r.source)
    }

    @Test
    fun `parses modelscope model page`() {
        val r = RepoLinkParser.parse("https://www.modelscope.cn/models/MNN/Qwen3-0.6B-MNN")
        assertTrue(r is RepoRef.ModelScope)
        assertEquals("MNN/Qwen3-0.6B-MNN", r.repoId)
        assertEquals("master", r.revision)
        assertEquals(ModelSource.MODELSCOPE, r.source)
    }

    @Test
    fun `parses modelscope url with revision`() {
        val r = RepoLinkParser.parse("https://modelscope.cn/models/MNN/Qwen3-0.6B-MNN/files/master/llm.mnn")
        assertTrue(r is RepoRef.ModelScope)
        assertEquals("MNN/Qwen3-0.6B-MNN", r.repoId)
        assertEquals("master", r.revision)
    }

    @Test
    fun `bare repo id defaults to modelscope because hf is unreachable domestically`() {
        val r = RepoLinkParser.parse("MNN/Qwen3-0.6B-MNN")
        assertTrue(r is RepoRef.ModelScope)
        assertEquals("MNN/Qwen3-0.6B-MNN", r.repoId)
    }

    @Test
    fun `tolerates surrounding whitespace quotes and trailing path`() {
        assertNotNull(RepoLinkParser.parse("  \"https://huggingface.co/a/b\"  "))
        assertNotNull(RepoLinkParser.parse("https://huggingface.co/a/b?foo=1"))
        assertNotNull(RepoLinkParser.parse("<https://hf-mirror.com/a/b>"))
    }

    @Test
    fun `rejects unusable input`() {
        assertNull(RepoLinkParser.parse(""))
        assertNull(RepoLinkParser.parse("   "))
        assertNull(RepoLinkParser.parse("not-a-link"))
        assertNull(RepoLinkParser.parse("https://huggingface.co/onlyowner"))
        assertNull(RepoLinkParser.parse("https://example.com/a/b"))
        assertNull(RepoLinkParser.parse("https://huggingface.co/datasets/a/b"))
    }

    @Test
    fun `candidates prefer mirror first for huggingface links`() {
        val ref = RepoLinkParser.parse("https://huggingface.co/a/b") as RepoRef.HuggingFace
        val cands = RepoLinkParser.candidates(ref, preferMirror = true)
        assertEquals(2, cands.size)
        assertTrue((cands[0] as RepoRef.HuggingFace).mirrored)
        assertTrue(!(cands[1] as RepoRef.HuggingFace).mirrored)
    }

    @Test
    fun `candidates for modelscope are a single entry`() {
        val ref = RepoLinkParser.parse("MNN/Qwen3-0.6B-MNN")!!
        assertEquals(1, RepoLinkParser.candidates(ref).size)
    }

    @Test
    fun `file url templates match verified endpoints`() {
        val client = RepoClient()
        val ms = RepoRef.ModelScope("MNN/Qwen3-0.6B-MNN", "master")
        assertEquals(
            "https://www.modelscope.cn/api/v1/models/MNN/Qwen3-0.6B-MNN/repo?Revision=master&FilePath=llm.mnn",
            client.fileUrl(ms, "llm.mnn"),
        )
        val hf = RepoRef.HuggingFace("taobao-mnn/Qwen3-0.6B-MNN", "main", mirrored = false)
        assertEquals(
            "https://huggingface.co/taobao-mnn/Qwen3-0.6B-MNN/resolve/main/config.json",
            client.fileUrl(hf, "config.json"),
        )
        val mirror = hf.copy(mirrored = true)
        assertEquals(
            "https://hf-mirror.com/taobao-mnn/Qwen3-0.6B-MNN/resolve/main/config.json",
            client.fileUrl(mirror, "config.json"),
        )
    }

    @Test
    fun `file url encodes revision and path`() {
        val client = RepoClient()
        val ms = RepoRef.ModelScope("MNN/Qwen3-0.6B-MNN", "master")
        val url = client.fileUrl(ms, "sub dir/a.mnn")
        assertTrue(url.contains("FilePath=sub+dir%2Fa.mnn"))
    }
}
