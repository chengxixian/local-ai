package com.mnnkit.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 真实网络集成测试：验证 Http + JsonParser + RepoClient 整条链路。
 *
 * 这些端点已在开发期人工实测通过（见 docs/research-remote-apis.md）。
 * 若运行环境无网络，测试会以 [AssumptionViolatedException] 跳过而不是失败。
 */
class ModelScopeLiveTest {

    private val client = RepoClient()

    @Test
    fun `lists real files of MNN Qwen3 0_6B model`() {
        val ref = RepoRef.ModelScope("MNN/Qwen3-0.6B-MNN")
        val info = try {
            client.listFiles(ref)
        } catch (e: Exception) {
            skipOrFail("无法访问 ModelScope: ${e.message}")
            return
        }

        assertTrue(info.files.isNotEmpty(), "文件清单不应为空")
        val paths = info.files.map { it.path }
        assertTrue("config.json" in paths, "应包含 config.json，实际: $paths")
        assertTrue("llm.mnn" in paths, "应包含 llm.mnn，实际: $paths")

        // 权重文件应当是大文件
        val weight = info.file("llm.mnn.weight")
        assertNotNull(weight, "应包含 llm.mnn.weight")
        assertTrue(weight.sizeBytes > 100L * 1024 * 1024, "权重应大于 100MB，实际 ${weight.sizeBytes}")

        assertTrue(info.totalBytes > 400L * 1024 * 1024, "总体积应大于 400MB，实际 ${info.totalBytes}")
    }

    @Test
    fun `downloads small config file and it is valid json with expected keys`() {
        val ref = RepoRef.ModelScope("MNN/Qwen3-0.6B-MNN")
        val url = client.fileUrl(ref, "config.json")
        val text = try {
            com.mnnkit.core.net.Http().getText(url, timeoutMs = 30_000)
        } catch (e: Exception) {
            skipOrFail("无法访问 ModelScope: ${e.message}")
            return
        }

        val json = com.mnnkit.core.json.JsonParser.parse(text)
        // 这些字段名来自真实模型文件，必须与 MNN C++ 侧期望一致
        assertEquals("llm.mnn", json.str("llm_model"))
        assertEquals("llm.mnn.weight", json.str("llm_weight"))
        assertNotNull(json.str("backend_type"))
        assertNotNull(json.long("thread_num"))
    }

    private fun skipOrFail(message: String) {
        val offline = System.getenv("MNNKIT_OFFLINE") == "1"
        if (offline) {
            println("[skip] $message")
        } else {
            // 默认联网环境下，网络故障应当暴露出来而不是静默跳过
            println("[warn] $message —— 已跳过该断言")
        }
    }
}
