package com.mnnkit.app.data

import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.model.ModelSource
import com.mnnkit.core.model.ModelStatus
import com.mnnkit.core.net.CancelledException
import com.mnnkit.core.net.Http
import com.mnnkit.core.remote.RemoteFile
import com.mnnkit.core.remote.RepoClient
import com.mnnkit.core.remote.RepoLinkParser
import com.mnnkit.core.remote.RepoRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 单个模型的下载运行态。 */
data class DownloadState(
    val modelId: String,
    val status: ModelStatus = ModelStatus.REMOTE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedLabel: String = "",
    val currentFile: String = "",
    val message: String? = null,
)

/**
 * 模型安装与目录管理。
 *
 * 职责：
 *  - 拉取官方模型市场清单（[ModelCatalog]）并与本地已安装状态合并
 *  - 下载模型文件（断点续传、进度、取消）
 *  - 支持用户粘贴 HuggingFace / ModelScope 链接导入任意模型
 *  - 扫描本地已安装模型（含用户手动侧载的目录）
 */
class ModelManager(
    private val storage: Storage,
    private val repoClient: RepoClient,
    private val http: Http,
    private val catalog: ModelCatalog,
    private val scope: CoroutineScope,
) {

    private val downloads = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()

    private val _state = MutableStateFlow(ModelManagerState())
    val state: StateFlow<ModelManagerState> = _state.asStateFlow()

    data class ModelManagerState(
        val models: List<ModelItem> = emptyList(),
        val downloads: Map<String, DownloadState> = emptyMap(),
        val loading: Boolean = false,
        val installedBytes: Long = 0L,
        val error: String? = null,
        /** 当前商店来源 */
        val storeSource: ModelCatalog.StoreSource = ModelCatalog.StoreSource.OFFICIAL,
        /** 数据是否来自网络（false = 缓存或内置兜底），UI 据此提示 */
        val fromNetwork: Boolean = true,
        val note: String? = null,
    )

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    /** 切换商店来源并重新加载。 */
    suspend fun switchSource(source: ModelCatalog.StoreSource) {
        _state.value = _state.value.copy(storeSource = source)
        refresh(force = true)
    }

    /** 加载目录 = 商店清单 + 本地已安装状态。 */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val result = catalog.load(_state.value.storeSource, force)
            val merged = mergeWithLocal(result.items)
            _state.value = _state.value.copy(
                models = merged,
                loading = false,
                installedBytes = installedSize(),
                error = null,
                fromNetwork = result.fromNetwork,
                note = result.note,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                error = e.message ?: "模型清单加载失败",
            )
        }
    }

    /** 把本地磁盘上已有的模型目录标记为 READY，并补上不在市场清单里的本地模型。 */
    private fun mergeWithLocal(remote: List<ModelItem>): List<ModelItem> {
        val installed = scanInstalled()
        val installedByRepo = installed.associateBy { it.repoId }

        val merged = remote.map { item ->
            val local = installedByRepo[item.repoId]
            if (local != null) {
                item.copy(
                    status = ModelStatus.READY,
                    localPath = local.localPath,
                    sizeBytes = if (local.sizeBytes > 0) local.sizeBytes else item.sizeBytes,
                )
            } else {
                item.copy(status = ModelStatus.REMOTE, localPath = null)
            }
        }
        // 补上本地有、但市场清单里没有的（用户侧载或已下架）
        val remoteRepos = remote.map { it.repoId }.toSet()
        val extras = installed.filter { it.repoId !in remoteRepos }
        return merged + extras
    }

    /** 扫描磁盘上已安装的模型目录。 */
    fun scanInstalled(): List<ModelItem> {
        val out = ArrayList<ModelItem>()
        ModelKind.entries.forEach { kind ->
            val kindDir = File(storage.modelsDir, kind.id)
            if (!kindDir.isDirectory) return@forEach
            kindDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val marker = File(dir, MARKER_FILE)
                val repoId = if (marker.exists()) {
                    marker.readText().trim().ifBlank { dir.name }
                } else {
                    dir.name
                }
                out.add(
                    ModelItem(
                        id = "${ModelSource.LOCAL.id}/$repoId",
                        displayName = dir.name.replace("__", "/"),
                        kind = kind,
                        source = ModelSource.LOCAL,
                        repoId = repoId,
                        description = "已安装在本机",
                        sizeBytes = storage.sizeOf(dir),
                        status = ModelStatus.READY,
                        localPath = dir.absolutePath,
                    )
                )
            }
        }
        return out
    }

    private fun installedSize(): Long =
        ModelKind.entries.sumOf { kind -> storage.sizeOf(File(storage.modelsDir, kind.id)) }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /** 启动下载安装。重复调用同一模型不会重复启动。 */
    fun install(item: ModelItem, source: ModelSource? = null) {
        if (downloads.containsKey(item.id)) return
        val cancelFlag = AtomicBoolean(false)
        cancelled[item.id] = cancelFlag
        updateDownload(item.id) {
            it.copy(status = ModelStatus.INSTALLING, progress = 0f, message = null)
        }
        val job = scope.launch(Dispatchers.IO) {
            try {
                doInstall(item, source ?: item.source, cancelFlag)
                updateDownload(item.id) {
                    it.copy(status = ModelStatus.READY, progress = 1f, message = "安装完成")
                }
                refresh()
            } catch (e: CancelledException) {
                cleanupPartial(item)
                updateDownload(item.id) {
                    it.copy(status = ModelStatus.REMOTE, progress = 0f, message = "已取消")
                }
            } catch (e: Exception) {
                updateDownload(item.id) {
                    it.copy(
                        status = ModelStatus.FAILED,
                        message = e.message ?: "下载失败",
                    )
                }
            } finally {
                downloads.remove(item.id)
                cancelled.remove(item.id)
            }
        }
        downloads[item.id] = job
    }

    private suspend fun doInstall(item: ModelItem, source: ModelSource, cancelFlag: AtomicBoolean) {
        val targetDir = storage.modelDir(item.kind.id, item.repoId)
        targetDir.mkdirs()

        val ref = repoRefOf(item, source)
        val info = repoClient.listFilesWithFallback(
            ref,
            // 魔搭与 Modelers 直连即可；HF 源优先走镜像以规避连通性问题
            preferMirror = ref is RepoRef.HuggingFace,
        )

        // 校验：LLM 模型必须含权重文件，否则是选错了仓库
        validateRepoFiles(item, info.files)

        val total = info.files.sumOf { it.sizeBytes }.takeIf { it > 0 } ?: item.sizeBytes
        var doneBytes = 0L
        val startedAt = System.currentTimeMillis()

        info.files.forEach { rf ->
            if (cancelFlag.get()) throw CancelledException(ref.repoId)
            val dest = File(targetDir, rf.path)
            // 已完整下载则跳过（断点续传由 Http.download 处理 .part）
            if (dest.exists() && rf.sizeBytes > 0 && dest.length() == rf.sizeBytes) {
                doneBytes += rf.sizeBytes
                reportProgress(item.id, doneBytes, total, rf.path, startedAt)
                return@forEach
            }

            val url = repoClient.fileUrl(info.ref, rf.path)
            http.download(
                url = url,
                target = dest,
                onProgress = { dl, fileTotal ->
                    if (cancelFlag.get()) return@download false
                    val effective = if (fileTotal > 0) fileTotal else rf.sizeBytes
                    reportProgress(item.id, doneBytes + dl, total.coerceAtLeast(doneBytes + effective), rf.path, startedAt)
                    true
                },
            )
            doneBytes += (if (rf.sizeBytes > 0) rf.sizeBytes else dest.length())
            reportProgress(item.id, doneBytes, total, rf.path, startedAt)
        }

        // 写标记文件：记录 repoId 与来源，便于重装/更新时识别
        File(targetDir, MARKER_FILE).writeText(ref.repoId)
        File(targetDir, "SOURCE.txt").writeText(
            buildString {
                appendLine("repoId=${ref.repoId}")
                appendLine("revision=${ref.revision}")
                appendLine("source=${ref.source.id}")
                appendLine("installedAt=${System.currentTimeMillis()}")
                if (item.convertedFrom != null) appendLine("convertedFrom=${item.convertedFrom}")
            }
        )
    }

    private fun reportProgress(
        modelId: String,
        done: Long,
        total: Long,
        currentFile: String,
        startedAt: Long,
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        updateDownload(modelId) {
            it.copy(
                status = ModelStatus.INSTALLING,
                downloadedBytes = done,
                totalBytes = total,
                progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
                speedLabel = Http.humanSpeed(done, elapsed),
                currentFile = currentFile,
            )
        }
    }

    private fun validateRepoFiles(item: ModelItem, files: List<RemoteFile>) {
        val paths = files.map { it.path }
        when (item.kind) {
            ModelKind.LLM -> {
                val hasWeight = paths.any { it == "llm.mnn.weight" || it.endsWith(".mnn.weight") }
                val hasModel = paths.any { it == "llm.mnn" || it.endsWith(".mnn") }
                if (!hasModel) {
                    throw IllegalStateException("该仓库不是 MNN 格式：未找到 .mnn 模型文件")
                }
                if (!hasWeight) {
                    throw IllegalStateException("该仓库缺少权重文件（llm.mnn.weight）")
                }
            }
            ModelKind.DIFFUSION -> {
                // 官方文生图模型由多个子模型组成
                val needed = listOf("text_encoder", "unet", "vae_decoder")
                val missing = needed.filter { key -> paths.none { it.contains(key) } }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException("不是完整的 MNN 文生图模型，缺少：${missing.joinToString(", ")}")
                }
            }
            else -> {
                if (files.isEmpty()) throw IllegalStateException("仓库为空")
            }
        }
    }

    /** 取消下载。 */
    fun cancel(modelId: String) {
        cancelled[modelId]?.set(true)
        downloads[modelId]?.cancel()
        downloads.remove(modelId)
    }

    /** 删除已安装模型。 */
    fun uninstall(item: ModelItem) {
        val dir = item.localPath?.let { File(it) }
            ?: File(File(storage.modelsDir, item.kind.id), storage.sanitize(item.repoId))
        storage.deleteRecursively(dir)
        scope.launch { refresh() }
    }

    private fun cleanupPartial(item: ModelItem) {
        val dir = File(File(storage.modelsDir, item.kind.id), storage.sanitize(item.repoId))
        dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /**
     * 构造仓库引用。
     *
     * [ModelItem.altRepos] 记录同一模型在其它源上的仓库 id，
     * 因此用户可以在商店里自由选择"从 HuggingFace 下"还是"从魔搭下"。
     */
    private fun repoRefOf(item: ModelItem, source: ModelSource): RepoRef {
        val repoId = item.altRepos[source.id] ?: item.repoId
        return when (source) {
            ModelSource.MODELSCOPE -> RepoRef.ModelScope(repoId, "master")
            ModelSource.HF_MIRROR -> RepoRef.HuggingFace(repoId, "main", mirrored = true)
            ModelSource.HUGGINGFACE -> RepoRef.HuggingFace(repoId, "main", mirrored = false)
            ModelSource.LOCAL -> RepoRef.HuggingFace(repoId, "main", mirrored = false)
        }
    }

    /**
     * 补全体积信息。
     *
     * HuggingFace 的列表接口不返回仓库体积（[ModelItem.sizeIsExact] 为 false），
     * 因此在用户首次看到该条目时按需查询一次文件清单并回填。
     */
    suspend fun resolveSize(item: ModelItem, source: ModelSource? = null): Long =
        withContext(Dispatchers.IO) {
            if (item.sizeIsExact && item.sizeBytes > 0) return@withContext item.sizeBytes
            val ref = repoRefOf(item, source ?: item.source)
            val info = runCatching { repoClient.listFilesWithFallback(ref, preferMirror = true) }.getOrNull()
                ?: return@withContext 0L
            val total = info.totalBytes
            if (total > 0) {
                val cur = _state.value
                _state.value = cur.copy(
                    models = cur.models.map {
                        if (it.id == item.id) it.copy(sizeBytes = total, sizeIsExact = true) else it
                    },
                )
            }
            total
        }

    // ------------------------------------------------------------------
    // 链接导入
    // ------------------------------------------------------------------

    /**
     * 用户粘贴链接导入。
     * 例：`https://huggingface.co/Qwen/Qwen3-0.6B` / `https://www.modelscope.cn/models/MNN/Qwen3-0.6B-MNN`
     *
     * [kind] 决定安装到哪个目录；[mirrorHost] 用于把 HF 链接改写到镜像站。
     */
    suspend fun importFromLink(
        rawLink: String,
        kind: ModelKind,
        preferMirror: Boolean = true,
    ): Result<ModelItem> = withContext(Dispatchers.IO) {
        val ref = RepoLinkParser.parse(rawLink)
            ?: return@withContext Result.failure(
                IllegalArgumentException("无法识别的链接。请粘贴 HuggingFace 或 ModelScope 的模型仓库地址。")
            )
        try {
            val info = repoClient.listFilesWithFallback(ref, preferMirror)
            if (info.files.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("仓库为空或无法访问"))
            }
            val item = ModelItem(
                id = "${ref.source.id}/${ref.repoId}",
                displayName = ref.repoId,
                kind = kind,
                source = ref.source,
                repoId = ref.repoId,
                revision = ref.revision,
                description = "${ref.source.label} 导入",
                sizeBytes = info.totalBytes,
                tags = listOf("导入"),
            )
            validateRepoFiles(item, info.files)
            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 供 UI 显示：导入链接的候选镜像改写结果。 */
    fun describeMirror(rawLink: String): String? {
        val ref = RepoLinkParser.parse(rawLink) ?: return null
        return when (ref) {
            is RepoRef.ModelScope -> "将直接从 ModelScope 下载：${ref.repoId}"
            is RepoRef.HuggingFace -> {
                val mirror = RepoRef.HuggingFace(ref.repoId, ref.revision, mirrored = true)
                "HuggingFace 链接已识别。将优先经镜像站下载：${repoClient.fileUrl(mirror, "<文件>")}"
            }
        }
    }

    private fun updateDownload(modelId: String, transform: (DownloadState) -> DownloadState) {
        val cur = _state.value
        val existing = cur.downloads[modelId] ?: DownloadState(modelId)
        val next = transform(existing)
        _state.value = cur.copy(downloads = cur.downloads + (modelId to next))
    }

    companion object {
        const val MARKER_FILE = ".mnnkit_repo"
    }
}
