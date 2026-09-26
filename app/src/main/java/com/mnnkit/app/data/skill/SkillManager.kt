package com.mnnkit.app.data.skill

import android.content.Context
import com.mnnkit.app.data.Storage
import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/** 技能库里的一条可安装项。 */
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    /** 来源：bundled（内置精选）/ github / disk（已安装） */
    val origin: String,
    val license: String = "",
    /** GitHub 仓库坐标，用于安装 */
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    /** 仓库内 SKILL.md 所在目录 */
    val path: String = "",
    val installed: Boolean = false,
)

data class InstalledSkill(
    val manifest: SkillManifest,
    val dir: File,
    val enabled: Boolean,
)

data class SkillUiState(
    val available: List<SkillEntry> = emptyList(),
    val installed: List<InstalledSkill> = emptyList(),
    val loading: Boolean = false,
    val busyId: String? = null,
    val message: String? = null,
    val error: Boolean = false,
)

/**
 * Skill 管理器：应用内浏览、下载、安装、启用。
 *
 * 数据源：
 *  1. **内置精选索引** `assets/skills_index.json` —— 离线可用，装机即有内容
 *  2. **用户粘贴的 GitHub 仓库链接** —— 通过 api.github.com 拉取目录树（已实测本机可达）
 *
 * 安装 = 把该技能的文件写到 `storage.skillsDir/<name>/`，并写标记文件。
 * 应用**不执行** skill 里的 scripts/，只把 SKILL.md 作为指令文本注入 prompt ——
 * 这既是安全考虑（不执行未审计脚本），也符合端侧无 shell 的现实。
 */
class SkillManager(
    private val context: Context,
    private val storage: Storage,
    private val http: Http,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(SkillUiState())
    val state: StateFlow<SkillUiState> = _state.asStateFlow()

    private val enabledFile: File get() = File(storage.root, "skills_enabled.json")

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(loading = true)
        val installed = scanInstalled()
        val index = loadIndex()
        val installedNames = installed.map { it.manifest.name }.toSet()
        val merged = index.map { it.copy(installed = it.name in installedNames) }
        // 已安装但不在索引里的（用户自己装的）也列出来
        val extra = installed
            .filter { s -> index.none { it.name == s.manifest.name } }
            .map {
                SkillEntry(
                    id = "disk/${it.manifest.name}",
                    name = it.manifest.name,
                    description = it.manifest.description,
                    origin = "disk",
                    license = it.manifest.license,
                    installed = true,
                )
            }
        _state.value = _state.value.copy(
            available = merged + extra,
            installed = installed,
            loading = false,
        )
    }

    /** 读取内置精选索引。 */
    private fun loadIndex(): List<SkillEntry> {
        val fromAssets = runCatching {
            context.assets.open(INDEX_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyList()

        val json = JsonParser.parseOrNull(fromAssets) ?: return emptyList()
        val arr = json.arr("skills") ?: return emptyList()
        return arr.mapNotNull { node ->
            val name = node.str("name") ?: return@mapNotNull null
            SkillEntry(
                id = node.str("id") ?: "bundled/$name",
                name = name,
                description = node.str("description").orEmpty(),
                origin = "bundled",
                license = node.str("license").orEmpty(),
                owner = node.str("owner").orEmpty(),
                repo = node.str("repo").orEmpty(),
                branch = node.str("branch") ?: "main",
                path = node.str("path").orEmpty(),
            )
        }
    }

    /** 扫描本地已安装技能。 */
    fun scanInstalled(): List<InstalledSkill> {
        val enabled = readEnabled()
        val dirs = storage.skillsDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.exists()) return@mapNotNull null
            val manifest = SkillManifestParser.parse(skillFile.readText()).getOrNull()
                ?: return@mapNotNull null
            InstalledSkill(manifest, dir, enabled.contains(manifest.name))
        }.sortedBy { it.manifest.name }
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /** 从 GitHub 仓库安装一个技能包。[path] 是仓库内包含 SKILL.md 的目录。 */
    fun installFromGitHub(
        owner: String,
        repo: String,
        branch: String = "main",
        path: String = "",
        displayName: String = repo,
    ) {
        val id = "github/$owner/$repo/$path"
        if (_state.value.busyId != null) return
        _state.value = _state.value.copy(busyId = id, message = null, error = false)

        scope.launch(Dispatchers.IO) {
            try {
                val files = listRepoFiles(owner, repo, branch, path)
                if (files.none { it.first.endsWith("SKILL.md") }) {
                    throw IllegalStateException("该仓库目录下没有 SKILL.md，可能不是 Agent Skill 包")
                }
                val n = downloadFiles(owner, repo, branch, files, displayName)
                _state.value = _state.value.copy(
                    busyId = null,
                    message = "已安装 $n 个文件",
                    error = false,
                )
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busyId = null,
                    message = e.message ?: "安装失败",
                    error = true,
                )
            }
        }
    }

    /** 解析并安装用户粘贴的 GitHub 链接。 */
    fun installFromLink(rawLink: String) {
        val parsed = parseGitHubLink(rawLink)
        if (parsed == null) {
            _state.value = _state.value.copy(
                message = "无法识别链接。支持形如 https://github.com/owner/repo 或 " +
                    "https://github.com/owner/repo/tree/main/skills/pdf",
                error = true,
            )
            return
        }
        installFromGitHub(parsed.owner, parsed.repo, parsed.branch, parsed.path)
    }

    data class GitHubRef(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    /** 解析 GitHub 链接；也接受 `owner/repo` 简写。 */
    fun parseGitHubLink(raw: String): GitHubRef? {
        val s = raw.trim().trim('"', '\'', '<', '>')
        if (s.isEmpty()) return null

        if (!s.startsWith("http")) {
            val parts = s.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return GitHubRef(parts[0], parts[1], "main", parts.drop(2).joinToString("/"))
        }

        val noScheme = s.removePrefix("https://").removePrefix("http://")
        val segs = noScheme.split('/').filter { it.isNotBlank() }
        if (segs.size < 3) return null
        val host = segs[0].lowercase()
        if (host != "github.com" && host != "www.github.com") return null

        val owner = segs[1]
        val repo = segs[2].removeSuffix(".git")
        var branch = "main"
        var path = ""
        if (segs.size >= 5 && (segs[3] == "tree" || segs[3] == "blob")) {
            branch = segs[4]
            path = segs.drop(5).joinToString("/")
        } else if (segs.size > 3) {
            path = segs.drop(3).joinToString("/")
        }
        return GitHubRef(owner, repo, branch, path)
    }

    private val apiBase = "https://api.github.com"

    /**
     * 列出仓库某个目录下的文件（递归）。
     *
     * 用 api.github.com 而不是 raw.githubusercontent.com：后者在本机不可达，
     * 而 API 同时能列目录并直接返回 base64 内容，一次调用两用。
     */
    private fun listRepoFiles(
        owner: String,
        repo: String,
        branch: String,
        path: String,
    ): List<Pair<String, Long>> {
        val url = buildString {
            append("$apiBase/repos/$owner/$repo/git/trees/")
            append(if (branch.isBlank()) "main" else branch)
            append("?recursive=1")
        }
        val text = http.getText(url, headers = GITHUB_HEADERS, timeoutMs = 30_000)
        val json = JsonParser.parse(text)
        val tree = json.arr("tree") ?: throw IllegalStateException("GitHub 返回格式异常")

        val prefix = if (path.isBlank()) "" else path.trimEnd('/') + "/"
        return tree.mapNotNull { node ->
            val p = node.str("path") ?: return@mapNotNull null
            val type = node.str("type") ?: "blob"
            if (type != "blob") return@mapNotNull null
            if (prefix.isNotEmpty() && !p.startsWith(prefix)) return@mapNotNull null
            if (prefix.isEmpty() && p.count { it == '/' } > 1) return@mapNotNull null // 只装顶层，避免拉整个仓库
            val size = node.long("size") ?: 0L
            p to size
        }.take(MAX_FILES)
    }

    /** 下载文件到技能目录。返回写入的文件数。 */
    private fun downloadFiles(
        owner: String,
        repo: String,
        branch: String,
        files: List<Pair<String, Long>>,
        displayName: String,
    ): Int {
        val staging = File(storage.skillsStagingDir, storage.sanitize("$owner-$repo"))
        storage.deleteRecursively(staging)
        staging.mkdirs()

        var count = 0
        for ((filePath, _) in files) {
            // 跳过明显的二进制/大文件，技能包核心是 markdown 与轻量脚本
            val ext = filePath.substringAfterLast('.', "").lowercase()
            if (ext in BINARY_EXT) continue

            val url = "$apiBase/repos/$owner/$repo/contents/$filePath?ref=$branch"
            val text = http.getText(url, headers = GITHUB_HEADERS, timeoutMs = 30_000)
            val json = JsonParser.parseOrNull(text) ?: continue
            val b64 = json.str("content") ?: continue
            val bytes = Base64.getMimeDecoder().decode(b64.replace("\n", "").replace("\r", ""))

            // 保留**相对路径**，不能只取文件名：
            // Skill 包常带 references/ scripts/ assets/ 等子目录，
            // 拍平成一层会让 scripts/a.py 与 assets/a.py 互相覆盖，静默丢文件。
            // 同时防目录穿越：'..' 与绝对路径都要剥掉。
            val rel = filePath
                .split('/')
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
            if (rel.isEmpty()) continue
            val dest = File(staging, rel)
            // 双保险：拼出来的路径必须仍在 staging 之内
            if (!dest.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            count++
        }

        // 校验并落位
        val skillFile = File(staging, "SKILL.md")
        if (!skillFile.exists()) {
            storage.deleteRecursively(staging)
            throw IllegalStateException("下载结果里没有 SKILL.md")
        }
        val manifest = SkillManifestParser.parse(skillFile.readText()).getOrElse {
            storage.deleteRecursively(staging)
            throw IllegalStateException("SKILL.md 解析失败：${it.message}")
        }
        manifest.validationError()?.let {
            storage.deleteRecursively(staging)
            throw IllegalStateException("SKILL.md 不符合规范：$it")
        }

        val target = File(storage.skillsDir, storage.sanitize(manifest.name))
        storage.deleteRecursively(target)
        if (!staging.renameTo(target)) {
            staging.copyRecursiveTo(target)
            storage.deleteRecursively(staging)
        }
        File(target, ORIGIN_FILE).writeText(
            buildString {
                appendLine("owner=$owner")
                appendLine("repo=$repo")
                appendLine("branch=$branch")
                appendLine("displayName=$displayName")
                appendLine("installedAt=${System.currentTimeMillis()}")
            }
        )
        // 新装的技能默认启用
        setEnabled(manifest.name, true)
        return count
    }

    private fun File.copyRecursiveTo(dest: File) {
        if (isDirectory) {
            dest.mkdirs()
            listFiles()?.forEach { it.copyRecursiveTo(File(dest, it.name)) }
        } else {
            dest.parentFile?.mkdirs()
            copyTo(dest, overwrite = true)
        }
    }

    fun uninstall(name: String) {
        val dir = File(storage.skillsDir, storage.sanitize(name))
        storage.deleteRecursively(dir)
        setEnabled(name, false)
        scope.launch { refresh() }
    }

    // ------------------------------------------------------------------
    // 启用状态
    // ------------------------------------------------------------------

    private fun readEnabled(): Set<String> {
        val f = enabledFile
        if (!f.exists()) return emptySet()
        val json = JsonParser.parseOrNull(f.readText()) ?: return emptySet()
        return json.arr("enabled")?.mapNotNull { it.asString }?.toSet() ?: emptySet()
    }

    private fun writeEnabled(names: Set<String>) {
        runCatching {
            enabledFile.writeText(
                Json.Obj(mapOf("enabled" to Json.Arr(names.map { Json.Str(it) }))).stringify()
            )
        }
    }

    fun setEnabled(name: String, enabled: Boolean) {
        val cur = readEnabled().toMutableSet()
        if (enabled) cur.add(name) else cur.remove(name)
        writeEnabled(cur)
        _state.value = _state.value.copy(
            installed = _state.value.installed.map {
                if (it.manifest.name == name) it.copy(enabled = enabled) else it
            },
        )
    }

    /** 当前启用的技能（供 prompt 注入）。 */
    fun enabledSkills(): List<InstalledSkill> =
        scanInstalled().filter { it.enabled && it.manifest.isUsable }

    private companion object {
        const val INDEX_ASSET = "skills_index.json"
        const val ORIGIN_FILE = ".mnnkit_skill_origin"
        const val MAX_FILES = 80

        val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "gz", "tar",
            "so", "jar", "aar", "apk", "mp3", "wav", "mp4", "ttf", "otf", "woff", "woff2",
            "bin", "onnx", "mnn", "pt", "pth", "safetensors",
        )

        val GITHUB_HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
    }
}

/**
 * 把已启用的技能渲染进 system prompt。
 *
 * 采用 Skills 规范推荐的**渐进式披露**：
 *  - 默认只注入 name + description（省上下文）
 *  - 正文只在技能数量很少或正文很短时注入
 */
object SkillPromptBuilder {

    /** 只注入元数据清单，成本最低。 */
    fun buildIndexPrompt(skills: List<InstalledSkill>): String? {
        val usable = skills.filter { it.manifest.isUsable }
        if (usable.isEmpty()) return null
        return buildString {
            appendLine("你可以使用以下技能（Skills）。当用户请求匹配某个技能的描述时，")
            appendLine("按该技能的说明完成任务；不要声称你调用了不存在的工具。")
            for (s in usable) {
                append("- ").append(s.manifest.name).append("：").appendLine(s.manifest.description)
            }
        }.trim()
    }

    /**
     * 完整注入（元数据 + 正文），用于技能少或用户显式要求时。
     * [maxChars] 限制总量，避免撑爆端侧上下文。
     */
    fun buildFullPrompt(skills: List<InstalledSkill>, maxChars: Int = 6000): String? {
        val usable = skills.filter { it.manifest.isUsable }
        if (usable.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("你可以使用以下技能（Skills）：")
        for (s in usable) {
            val header = "\n## 技能：${s.manifest.name}\n${s.manifest.description}\n"
            if (sb.length + header.length > maxChars) break
            sb.append(header)
            val body = s.manifest.body
            if (body.isNotBlank()) {
                val room = maxChars - sb.length
                if (room <= 0) break
                sb.appendLine(if (body.length <= room) body else body.take(room) + "\n…（已截断）")
            }
        }
        return sb.toString().trim()
    }
}
