package com.mnnkit.core.remote

import com.mnnkit.core.model.ModelSource

/**
 * 用户粘贴的模型链接的解析结果。
 *
 * 需求要求"支持用户自己复制 huggingface 链接并转到镜像站下载"，
 * 因此这里把任意形态的链接归一化成 [RepoRef]，再由 [RepoDownloadPlan]
 * 决定走哪个站点、用什么 URL 模板取文件。
 */
sealed interface RepoRef {
    val repoId: String
    val revision: String
    val source: ModelSource

    /** ModelScope：MNN/Qwen3-0.6B-MNN */
    data class ModelScope(
        override val repoId: String,
        override val revision: String = "master",
    ) : RepoRef {
        override val source: ModelSource get() = ModelSource.MODELSCOPE
    }

    /** HuggingFace 及其镜像。 */
    data class HuggingFace(
        override val repoId: String,
        override val revision: String = "main",
        val mirrored: Boolean = false,
    ) : RepoRef {
        override val source: ModelSource
            get() = if (mirrored) ModelSource.HF_MIRROR else ModelSource.HUGGINGFACE
    }
}

object RepoLinkParser {

    private val HF_HOSTS = setOf("huggingface.co", "www.huggingface.co")
    private val HF_MIRRORS = setOf("hf-mirror.com", "www.hf-mirror.com", "hf-mirror.net", "modelscope.cn")
    private val MS_HOSTS = setOf("modelscope.cn", "www.modelscope.cn")

    private val REVISION_KEYWORDS = setOf(
        "resolve", "blob", "tree", "raw", "commit", "refs",
    )

    /**
     * 解析用户输入。接受：
     *  - `https://huggingface.co/Qwen/Qwen3-0.6B`
     *  - `https://huggingface.co/Qwen/Qwen3-0.6B/tree/main`
     *  - `https://huggingface.co/Qwen/Qwen3-0.6B/resolve/main/config.json`
     *  - `https://hf-mirror.com/Qwen/Qwen3-0.6B`
     *  - `https://www.modelscope.cn/models/MNN/Qwen3-0.6B-MNN`
     *  - `MNN/Qwen3-0.6B-MNN`（裸 repo id，默认按 ModelScope 处理）
     */
    fun parse(rawInput: String): RepoRef? {
        val input = rawInput.trim().trim('"', '\'', '<', '>')
        if (input.isEmpty()) return null

        val noScheme = input
            .removePrefix("https://")
            .removePrefix("http://")
        val slash = noScheme.indexOf('/')
        val host = (if (slash >= 0) noScheme.substring(0, slash) else noScheme).lowercase()

        val isUrl = input.startsWith("http://") || input.startsWith("https://") ||
            host in HF_HOSTS || host in HF_MIRRORS || host in MS_HOSTS ||
            host.endsWith(".huggingface.co")

        if (!isUrl) {
            // 裸 repo id：owner/name 形态
            val parts = input.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return RepoRef.ModelScope(parts.take(2).joinToString("/"))
        }

        if (slash < 0) return null
        val pathAndQuery = noScheme.substring(slash + 1)
        val path = pathAndQuery.substringBefore('?').substringBefore('#')
        val segs = path.split('/').filter { it.isNotBlank() }
        if (segs.isEmpty()) return null

        return when {
            host in MS_HOSTS -> parseModelScope(segs)
            host in HF_HOSTS || host in HF_MIRRORS || host.endsWith(".huggingface.co") ->
                parseHuggingFace(segs, mirrored = host in HF_MIRRORS)
            else -> null
        }
    }

    private fun parseModelScope(segs: List<String>): RepoRef? {
        // /models/{owner}/{name} 或 /{owner}/{name}
        val s = if (segs.firstOrNull()?.equals("models", true) == true) segs.drop(1) else segs
        if (s.size < 2) return null
        var revision = "master"
        val revIdx = s.indexOfFirst { it.equals("files", true) || it.equals("resolve", true) }
        if (revIdx >= 0 && revIdx < s.size - 1) revision = s[revIdx + 1]
        return RepoRef.ModelScope(s.take(2).joinToString("/"), revision)
    }

    private fun parseHuggingFace(segs: List<String>, mirrored: Boolean): RepoRef? {
        // /{owner}/{name}[/tree|blob|resolve/{rev}[/{path...}]]
        // 也可能带 datasets/ 或 spaces/ 前缀，这里只支持 models
        val s = when {
            segs.size >= 3 && segs[0].equals("models", true) -> segs.drop(1)
            segs.size >= 3 && (segs[0].equals("datasets", true) || segs[0].equals("spaces", true)) -> return null
            else -> segs
        }
        if (s.size < 2) return null
        val repoId = "${s[0]}/${s[1]}"
        var revision = "main"
        if (s.size >= 4) {
            val kw = s[2].lowercase()
            if (kw == "tree" || kw == "blob" || kw == "resolve" || kw == "raw") {
                revision = s[3]
            }
        } else if (s.size == 3 && s[2].lowercase() in REVISION_KEYWORDS) {
            // 形如 /owner/name/tree 但没有 revision，忽略
            revision = "main"
        }
        return RepoRef.HuggingFace(repoId, revision, mirrored)
    }

    /** 把 HF 链接改写为镜像站链接（保留 repo 与 revision）。 */
    fun toMirror(ref: RepoRef.HuggingFace, mirrorHost: String = "hf-mirror.com"): RepoRef.HuggingFace =
        ref.copy(mirrored = true).let { RepoRef.HuggingFace(it.repoId, it.revision, true) }

    /**
     * 依次给出可尝试的候选。用于"下载失败自动换镜像"：
     * 用户贴 HF 链接 → 先试 hf-mirror，再试 HF 官方。
     */
    fun candidates(ref: RepoRef, preferMirror: Boolean = true): List<RepoRef> = when (ref) {
        is RepoRef.ModelScope -> listOf(ref)
        is RepoRef.HuggingFace -> if (preferMirror) {
            listOf(RepoRef.HuggingFace(ref.repoId, ref.revision, true), RepoRef.HuggingFace(ref.repoId, ref.revision, false))
        } else {
            listOf(ref, RepoRef.HuggingFace(ref.repoId, ref.revision, true))
        }
    }
}
