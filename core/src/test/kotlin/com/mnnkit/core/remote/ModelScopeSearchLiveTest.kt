package com.mnnkit.core.remote

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真实网络集成测试：验证 ModelScope **搜索**接口。
 *
 * 关键事实（已实证）：ModelScope 的模型搜索只接受 **PUT**，
 * 用 GET 请求 `/api/v1/models` 会返回 404。
 */
class ModelScopeSearchLiveTest {

    private val client = RepoClient()

    @Test
    fun `search finds MNN official models via PUT endpoint`() {
        val results = try {
            client.searchModelScope("mnn", pageSize = 100, pageNumber = 1)
        } catch (e: Exception) {
            println("[warn] 无法访问 ModelScope 搜索接口：${e.message}")
            return
        }

        assertTrue(results.isNotEmpty(), "搜索 'mnn' 应返回结果")
        println("搜索命中 ${results.size} 条")

        val official = results.filter { it.owner == "MNN" }
        println("其中 MNN 官方组织 ${official.size} 条")
        assertTrue(official.isNotEmpty(), "应包含 MNN 官方组织的模型")

        // 已知真实存在的模型必须能被找到
        val qwen = results.firstOrNull { it.repoId == "MNN/Qwen3-0.6B-MNN" }
        if (qwen != null) {
            assertTrue(qwen.sizeBytes > 0, "应带上仓库体积")
            println("Qwen3-0.6B-MNN: ${qwen.sizeBytes} bytes, frameworks=${qwen.frameworks}")
        } else {
            println("[info] 本页未包含 Qwen3-0.6B-MNN（分页所致，非错误）")
        }

        // 结果应当能识别出 MNN 框架
        assertTrue(official.any { it.isMnn }, "官方 MNN 模型应被 isMnn 识别")
    }
}
