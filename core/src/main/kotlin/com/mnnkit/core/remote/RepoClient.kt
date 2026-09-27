package com.mnnkit.core.remote

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.net.Http
import java.io.IOException

/** 远程仓库中的一个文件。 */
data class RemoteFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/** 远程模型仓库的元数据。 */
data class RepoInfo(
    val ref: RepoRef,
    val files: List<RemoteFile>,
    val totalBytes: Long,
    val readme: String? = null,
) {
    fun file(name: String): RemoteFile? = files.firstOrNull { it.path == name }
}

/**
 * 统一访问 ModelScope 与 HuggingFace(镜像) 的仓库接口。
 *
 * 已实测的端点（见项目文档 docs/research-remote-apis.md）：
 *  - ModelScope 文件清单: GET /api/v1/models/{repo}/repo/files?Revision={rev}&Root=
 *  - ModelScope 文件下载: GET /api/v1/models/{repo}/repo?Revision={rev}&FilePath={path}
 *  - HuggingFace 文件清单: GET /api/models/{repo}/tree/{rev}?recursive=true
 *  - HuggingFace 文件下载: GET /{repo}/resolve/{rev}/{path}
 */
class RepoClient(
    private val http: Http = Http(),
    private val modelScopeBase: String = "https://www.modelscope.cn",
    private val hfBase: String = "https://huggingface.co",
    private val hfMirrorBase: String = "https://hf-mirror.com",
) {

    fun listFiles(ref: RepoRef): RepoInfo = when (ref) {
        is RepoRef.ModelScope -> listModelScope(ref)
        is RepoRef.HuggingFace -> listHuggingFace(ref)
    }

    /** 依次尝试候选站点，返回第一个成功的。 */
    fun listFilesWithFallback(ref: RepoRef, preferMirror: Boolean = true): RepoInfo {
        val cands = RepoLinkParser.candidates(ref, preferMirror)
        var last: Exception? = null
        for (c in cands) {
            try {
                return listFiles(c)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("无法读取仓库 ${ref.repoId}：${last?.message}", last)
    }

    private fun listModelScope(ref: RepoRef.ModelScope): RepoInfo {
        val url = "$modelScopeBase/api/v1/models/${ref.repoId}/repo/files" +
            "?Revision=${Http.encode(ref.revision)}&Root="
        val body = http.getText(url, timeoutMs = 30_000)
        val json = JsonParser.parse(body)
        if (json.bool("Success") == false) {
            throw IOException("ModelScope 返回失败：${json.str("Message") ?: "未知错误"}")
        }
        val raw = json.arr("Data", "Files") ?: emptyList()
        val files = raw.mapNotNull { node ->
            val path = node.str("Path") ?: return@mapNotNull null
            val type = node.str("Type") ?: "blob"
            if (type != "blob") return@mapNotNull null
            RemoteFile(
                path = path,
                sizeBytes = node.long("Size") ?: 0L,
                sha256 = node.str("Sha256")?.takeIf { it.isNotBlank() },
            )
        }
        return RepoInfo(ref, files, files.sumOf { it.sizeBytes })
    }

    private fun listHuggingFace(ref: RepoRef.HuggingFace): RepoInfo {
        val base = if (ref.mirrored) hfMirrorBase else hfBase
        val url = "$base/api/models/${ref.repoId}/tree/${ref.revision}?recursive=true"
        val body = http.getText(url, timeoutMs = 30_000)
        val json = JsonParser.parse(body)
        val arr = json.asArray ?: throw IOException("HuggingFace 返回格式异常")
        val files = arr.mapNotNull { node ->
            val path = node.str("path") ?: return@mapNotNull null
            val type = node.str("type") ?: "file"
            if (type != "file") return@mapNotNull null
            RemoteFile(
                path = path,
                sizeBytes = node.long("size") ?: (node.path("lfs")?.let { it.long("size") } ?: 0L),
            )
        }
        return RepoInfo(ref, files, files.sumOf { it.sizeBytes })
    }

    /** 构造单个文件的下载地址。 */
    fun fileUrl(ref: RepoRef, filePath: String): String = when (ref) {
        is RepoRef.ModelScope ->
            "$modelScopeBase/api/v1/models/${ref.repoId}/repo" +
                "?Revision=${Http.encode(ref.revision)}&FilePath=${Http.encode(filePath)}"
        is RepoRef.HuggingFace -> {
            val base = if (ref.mirrored) hfMirrorBase else hfBase
            "$base/${ref.repoId}/resolve/${ref.revision}/$filePath"
        }
    }

    /**
     * 读取 README（用于在详情页展示模型介绍）。
     * 找不到时返回 null，不抛异常。
     */
    fun readme(ref: RepoRef): String? {
        val candidates = listOf("README.md", "readme.md")
        for (name in candidates) {
            try {
                return http.getText(fileUrl(ref, name), timeoutMs = 20_000)
            } catch (_: Exception) {
                // 继续尝试下一个
            }
        }
        return null
    }

    /**
     * 在 ModelScope 上搜索模型。
     *
     * 注意：ModelScope **没有**公开的"按组织列模型"接口，
     * 列表接口只接受 **PUT**（GET 会 404）。这一端点经实测验证。
     */
    fun searchModelScope(
        keyword: String,
        pageSize: Int = 50,
        pageNumber: Int = 1,
    ): List<ModelSearchResult> {
        val body = Json.Obj(
            mapOf(
                "PageSize" to Json.Num(pageSize.toDouble()),
                "PageNumber" to Json.Num(pageNumber.toDouble()),
                "Name" to Json.Str(keyword),
                "SortBy" to Json.Str("Default"),
                "Target" to Json.Str(""),
                "SingleCriterion" to Json.Arr(emptyList()),
            )
        ).stringify()

        val text = http.putJson(
            url = "$modelScopeBase/api/v1/models",
            body = body,
            timeoutMs = 30_000,
        )
        val json = JsonParser.parse(text)
        val models = json.arr("Data", "Models")
            ?: json.arr("Data", "Model")
            ?: json.arr("Data")
            ?: return emptyList()

        return models.mapNotNull { node ->
            val path = node.str("Path") ?: return@mapNotNull null
            val name = node.str("Name") ?: return@mapNotNull null
            ModelSearchResult(
                repoId = "$path/$name",
                name = name,
                owner = path,
                chineseName = node.str("ChineseName").orEmpty(),
                description = node.str("Description").orEmpty(),
                downloads = node.long("Downloads") ?: 0L,
                stars = node.long("Stars") ?: 0L,
                sizeBytes = node.long("StorageSize") ?: 0L,
                tags = node.arr("Tags")?.mapNotNull { it.asString } ?: emptyList(),
                frameworks = node.arr("Frameworks")?.mapNotNull { it.asString } ?: emptyList(),
                lastUpdated = node.long("LastUpdatedTime") ?: 0L,
            )
        }
    }
}

/** ModelScope 搜索结果条目。 */
data class ModelSearchResult(
    val repoId: String,
    val name: String,
    val owner: String,
    val chineseName: String,
    val description: String,
    val downloads: Long,
    val stars: Long,
    val sizeBytes: Long,
    val tags: List<String>,
    val frameworks: List<String>,
    val lastUpdated: Long,
) {
    /** 是否声明为 MNN 框架（用于在搜索结果里优先展示）。 */
    val isMnn: Boolean get() = frameworks.any { it.equals("MNN", ignoreCase = true) } ||
        name.contains("MNN", ignoreCase = true) ||
        tags.any { it.equals("mnn", ignoreCase = true) }
}
