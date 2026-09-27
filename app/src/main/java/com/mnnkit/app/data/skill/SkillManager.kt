package com.mnnkit.app.data.skill

import android.content.Context
import com.mnnkit.app.data.Storage
import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.net.Http
import com.mnnkit.core.skill.ArchiveSkill
import com.mnnkit.core.skill.SkillArchive
import com.mnnkit.core.skill.SkillInstallStaging
import com.mnnkit.core.skill.SkillRepositoryRef
import com.mnnkit.core.skill.deleteSkillTree
import com.mnnkit.core.skill.normalizeSkillPath
import com.mnnkit.core.skill.skillCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val origin: String,
    val license: String = "",
    val owner: String = "",
    val repo: String = "",
    val branch: String = "",
    val path: String = "",
    val installed: Boolean = false,
    val problem: String? = null,
)

data class InstalledSkill(
    val manifest: SkillManifest,
    val dir: File,
    val enabled: Boolean,
    val id: String = "disk/${dir.name}",
)

data class SkillSource(val ref: SkillRepositoryRef, val label: String = ref.repositoryId) {
    val id: String get() = ref.sourceId
}

data class SkillUiState(
    val available: List<SkillEntry> = emptyList(),
    val installed: List<InstalledSkill> = emptyList(),
    val sources: List<SkillSource> = emptyList(),
    val selectedSourceId: String = FEATURED_SOURCE,
    val loading: Boolean = false,
    val busyId: String? = null,
    val progress: String? = null,
    val message: String? = null,
    val error: Boolean = false,
)

const val FEATURED_SOURCE = "featured"

/**
 * A store of explicitly selected SKILL.md subtrees. Repository ZIPs are cached and bounded;
 * installation never executes downloaded code, restores Unix modes, or follows symlinks.
 * Identity is owner/repository/root, NOT localized display name or manifest name.
 */
class SkillManager(
    private val context: Context,
    private val storage: Storage,
    private val http: Http,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SkillUiState())
    val state: StateFlow<SkillUiState> = _state.asStateFlow()
    private val operating = AtomicBoolean(false)
    private val enabledFile get() = File(storage.root, "skills_enabled.json")
    private val sourcesFile get() = File(storage.root, "skill_sources.json")
    private val cacheDir get() = File(storage.cacheDir, "skill-repositories").also { it.mkdirs() }

    /** Startup refresh is offline: remembered sources and cached cards remain available. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!operating.compareAndSet(false, true)) return@withContext
        _state.update { it.copy(loading = true) }
        try {
            val (sources, selected) = readSources()
            val entries = if (selected == FEATURED_SOURCE) loadIndex()
                else sources.firstOrNull { it.id == selected }?.let(::readCatalog).orEmpty()
            publish(entries, sources, selected)
        } catch (e: Exception) {
            _state.update { it.copy(message = e.message ?: "读取技能库失败", error = true) }
        } finally {
            _state.update { it.copy(loading = false) }
            operating.set(false)
        }
    }

    /** Selecting a source browses every discovered skill, never installs an arbitrary candidate. */
    fun selectSource(id: String, force: Boolean = false) = operation {
        val sources = readSources().first
        if (id == FEATURED_SOURCE) {
            saveSources(sources, id)
            publish(loadIndex(), sources, id)
            return@operation
        }
        val source = sources.firstOrNull { it.id == id } ?: error("技能来源不存在")
        browse(source, sources, force)
    }

    fun refreshSource() {
        val selected = state.value.selectedSourceId
        // The curated index is an offline starting point; refresh opens its live repository.
        selectSource(if (selected == FEATURED_SOURCE) DEFAULT_SOURCES.first().id else selected, force = true)
    }

    fun browseFromLink(rawLink: String) {
        val ref = SkillRepositoryRef.parse(rawLink)
        if (ref == null) {
            _state.update { it.copy(error = true, message = "无法识别链接。支持 owner/repo、GitHub tree 目录或 SKILL.md 链接。分支含 / 时请将分支中的 / 写成 %2F。") }
            return
        }
        operation {
            val source = SkillSource(ref)
            val sources = (readSources().first + source).distinctBy { it.id }
            require(sources.size <= MAX_SOURCES) { "最多保存 $MAX_SOURCES 个来源，请先移除不用的来源" }
            browse(source, sources, force = false)
        }
    }

    /** Compatibility entry point: links now open a selection list instead of auto-installing. */
    fun installFromLink(rawLink: String) = browseFromLink(rawLink)

    fun removeSource(id: String) = operation {
        require(DEFAULT_SOURCES.none { it.id == id }) { "默认来源不能移除" }
        val sources = readSources().first.filterNot { it.id == id }
        val selected = if (state.value.selectedSourceId == id) FEATURED_SOURCE else state.value.selectedSourceId
        saveSources(sources, selected)
        File(cacheDir, "catalog-${skillCacheKey(id)}.json").delete()
        publish(if (selected == FEATURED_SOURCE) loadIndex() else state.value.available, sources, selected)
    }

    private fun browse(source: SkillSource, sources: List<SkillSource>, force: Boolean) {
        saveSources(sources, source.id)
        publish(readCatalog(source), sources, source.id)
        val archive = repositoryArchive(source.ref, force)
        val all = SkillArchive.discover(archive.file)
        val requested = normalizeSkillPath(source.ref.path)
        val selected = when {
            requested.isEmpty() -> all
            all.any { it.root == requested } -> all.filter { it.root == requested }
            all.any { it.root.startsWith("$requested/") } -> all.filter { it.root.startsWith("$requested/") }
            else -> {
                val resolved = SkillArchive.resolveRoot(all.map { it.root }, requested)
                all.filter { it.root == resolved }
            }
        }
        require(selected.isNotEmpty()) { "仓库中没有找到精确命名的 SKILL.md" }
        val entries = selected.map { discoveredEntry(source.ref.copy(branch = archive.branch), it) }
        writeCatalog(source, entries)
        publish(entries, sources, source.id)
        _state.update { it.copy(message = "发现 ${entries.size} 个技能，请选择安装" + if (archive.cached) "（使用本地缓存）" else "", error = false) }
    }

    private fun discoveredEntry(ref: SkillRepositoryRef, skill: ArchiveSkill): SkillEntry {
        val parsed = SkillManifestParser.parse(skill.markdown)
        val manifest = parsed.getOrNull()
        return SkillEntry(
            id = ref.skillId(skill.root),
            name = manifest?.name?.ifBlank { null } ?: skill.root.ifBlank { ref.repo },
            description = manifest?.description.orEmpty(),
            origin = "github",
            license = manifest?.license.orEmpty(),
            owner = ref.owner, repo = ref.repo, branch = ref.branch, path = skill.root,
            problem = parsed.exceptionOrNull()?.message ?: manifest?.validationError(),
        )
    }

    fun install(entry: SkillEntry) {
        if (entry.problem != null) return
        installFromGitHub(entry.owner, entry.repo, entry.branch, entry.path, entry.name, entry.id)
    }

    /** Explicit card selection. Updates fetch a fresh archive and preserve old files if anything fails. */
    fun installFromGitHub(
        owner: String, repo: String, branch: String = "", path: String = "",
        displayName: String = repo, selectedId: String? = null,
    ) {
        val ref = SkillRepositoryRef.parse("$owner/$repo")?.copy(branch = branch, path = path)
        if (ref == null) {
            _state.update { it.copy(message = "无效的 GitHub 仓库", error = true) }
            return
        }
        val id = selectedId ?: runCatching { ref.skillId(path) }.getOrElse {
            _state.update { s -> s.copy(message = it.message, error = true) }; return
        }
        operation(id) {
            require(branch.isEmpty() || normalizeSkillPath(branch) == branch) { "无效的 GitHub 分支" }
            val installed = scanInstalled()
            val archive = repositoryArchive(ref, force = installed.any { it.id == id }, allowStale = false)
            val skills = SkillArchive.discover(archive.file)
            val wanted = normalizeSkillPath(path)
            // A root card is an explicit selection, unlike a pasted multi-skill repository URL.
            val root = if (selectedId != null && skills.any { it.root == wanted }) wanted
                else SkillArchive.resolveRoot(skills.map { it.root }, wanted)
            val canonicalId = ref.skillId(root)
            val skill = skills.first { it.root == root }
            val manifest = SkillManifestParser.parse(skill.markdown).getOrThrow()
            manifest.validationError()?.let { error("SKILL.md 不符合规范：$it") }
            val prior = installed.firstOrNull { it.id == canonicalId }
            val staging = File(storage.skillsStagingDir, UUID.randomUUID().toString())
            _state.update { it.copy(progress = "正在校验并暂存 $displayName…") }
            try {
                val count = SkillArchive.extract(archive.file, root, staging)
                // Metadata is written before activation; archive cannot replace our trusted identity.
                File(staging, ORIGIN_FILE).writeText(obj(
                    "id" to canonicalId, "owner" to ref.owner, "repo" to ref.repo,
                    "branch" to archive.branch, "path" to root, "displayName" to displayName,
                    "installedAt" to System.currentTimeMillis().toString(),
                ).stringify())
                val target = prior?.dir ?: File(storage.skillsDir, "${manifest.name}-${skillCacheKey(canonicalId).take(16)}")
                require(!target.exists() || prior != null) { "安装目录冲突，原有技能未改动" }
                SkillInstallStaging.replace(staging, target)
                // An enabled preference write is not allowed to turn a successful activation into a failed update.
                val preferenceWarning = runCatching { setEnabled(canonicalId, prior?.enabled ?: true) }.exceptionOrNull()
                val refreshed = scanInstalled()
                _state.update { current -> current.copy(
                    installed = refreshed,
                    available = markInstalled(current.available.map { card ->
                        if (card.id == id) card.copy(id = canonicalId, path = root, branch = archive.branch, license = manifest.license) else card
                    }.distinctBy { it.id }, refreshed),
                    message = "已${if (prior == null) "安装" else "更新"} $displayName · $count 个文件" +
                        if (preferenceWarning == null) "" else "；启用设置未保存，请重试",
                    error = preferenceWarning != null,
                ) }
            } finally {
                if (staging.exists()) runCatching { deleteSkillTree(staging) }
            }
        }
    }

    data class GitHubRef(val owner: String, val repo: String, val branch: String, val path: String)
    fun parseGitHubLink(raw: String): GitHubRef? = SkillRepositoryRef.parse(raw)?.let { GitHubRef(it.owner, it.repo, it.branch, it.path) }
    internal fun resolveSkillRoot(allPaths: List<String>, path: String): String = SkillArchive.resolveRoot(
        allPaths.filter { it.substringAfterLast('/') == "SKILL.md" }.map { it.substringBeforeLast('/', "") }, path,
    )

    private fun operation(id: String? = null, block: () -> Unit) {
        if (!operating.compareAndSet(false, true)) return
        _state.update { it.copy(loading = id == null, busyId = id, progress = "正在读取来源…", message = null, error = false) }
        scope.launch(Dispatchers.IO) {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: "技能操作失败", error = true) } }
            finally {
                _state.update { it.copy(loading = false, busyId = null, progress = null) }
                operating.set(false)
            }
        }
    }

    private data class CachedArchive(val file: File, val branch: String, val cached: Boolean)

    private fun repositoryArchive(ref: SkillRepositoryRef, force: Boolean, allowStale: Boolean = true): CachedArchive {
        val remembered = File(cacheDir, "branch-${skillCacheKey(ref.repositoryId)}.txt")
            .takeIf { it.isFile && it.length() < 1024 }?.readText()?.takeIf { it.isNotBlank() }
        val cachedBranch = ref.branch.ifBlank { remembered.orEmpty() }
        fun archiveFile(branch: String) = File(cacheDir, "${skillCacheKey(ref.repositoryId + "@" + branch)}.zip")
        val old = cachedBranch.takeIf { it.isNotBlank() }?.let(::archiveFile)
        if (!force && old?.isFile == true && System.currentTimeMillis() - old.lastModified() < CACHE_TTL) {
            runCatching { SkillArchive.discover(old) }.onSuccess { return CachedArchive(old, cachedBranch, true) }
        }
        _state.update { it.copy(progress = "正在获取仓库压缩包…") }
        val branches = if (ref.branch.isNotEmpty()) listOf(ref.branch) else {
            val defaultBranch = runCatching {
                JsonParser.parse(http.getText("https://api.github.com/repos/${ref.owner}/${ref.repo}", GITHUB_HEADERS, 15_000)).str("default_branch")
            }.getOrNull()
            listOfNotNull(defaultBranch, remembered, "main", "master").distinct()
        }
        var failure: Exception? = null
        for (branch in branches) {
            if (branch.isBlank()) continue
            val target = archiveFile(branch)
            val temp = File(cacheDir, "${target.name}.${UUID.randomUUID()}.download")
            try {
                trimArchiveCache(target)
                val encoded = Http.encode(branch).replace("+", "%20")
                val urls = listOf(
                    "https://codeload.github.com/${ref.owner}/${ref.repo}/zip/$encoded",
                    "https://api.github.com/repos/${ref.owner}/${ref.repo}/zipball/$encoded",
                )
                var downloaded = false
                var downloadError: Exception? = null
                for (url in urls) {
                    try {
                        var tooLarge = false
                        try {
                            http.download(url, temp, timeoutMs = 45_000, onProgress = { bytes, total ->
                                tooLarge = bytes > SkillArchive.MAX_ARCHIVE_BYTES || total > SkillArchive.MAX_ARCHIVE_BYTES
                                _state.update { it.copy(progress = "下载仓库 ${(bytes / 1024)} KB" + if (total > 0) " / ${total / 1024} KB" else "") }
                                !tooLarge && scope.isActive
                            })
                        } catch (e: Exception) {
                            if (tooLarge) error("仓库压缩包超过 64 MB 上限，请选择较小的技能仓库")
                            throw e
                        }
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        downloadError = e
                        temp.delete()
                        File(temp.parentFile, temp.name + ".part").delete()
                    }
                }
                if (!downloaded) throw downloadError ?: IllegalStateException("无法下载仓库")
                SkillArchive.discover(temp) // reject corrupt/unsafe ZIP before caching it
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (ref.branch.isBlank()) atomicText(File(cacheDir, "branch-${skillCacheKey(ref.repositoryId)}.txt"), branch)
                trimArchiveCache(target)
                return CachedArchive(target, branch, false)
            } catch (e: Exception) { failure = e }
            finally { temp.delete(); File(temp.parentFile, temp.name + ".part").delete() }
        }
        if (allowStale && old?.isFile == true && runCatching { SkillArchive.discover(old) }.isSuccess) {
            _state.update { it.copy(message = "网络不可用，显示缓存。${failure?.message.orEmpty()}") }
            return CachedArchive(old, cachedBranch, true)
        }
        throw IllegalStateException("无法读取 ${ref.repositoryId}：${failure?.message ?: "没有可用分支"}。可重试或选择其他来源。", failure)
    }

    private fun trimArchiveCache(keep: File) {
        val archives = cacheDir.listFiles()?.filter { it.extension == "zip" && it != keep }?.sortedByDescending { it.lastModified() }.orEmpty()
        // At most three 64 MB ZIPs plus one bounded download. No unbounded per-file API/cache growth.
        archives.drop(2).forEach { it.delete() }
    }

    private fun publish(entries: List<SkillEntry>, sources: List<SkillSource>, selected: String) {
        val installed = scanInstalled()
        _state.update { it.copy(available = markInstalled(entries, installed), installed = installed, sources = sources, selectedSourceId = selected) }
    }

    private fun markInstalled(entries: List<SkillEntry>, installed: List<InstalledSkill>): List<SkillEntry> {
        val ids = installed.map { it.id }.toSet()
        return entries.map { it.copy(installed = it.id in ids) }.distinctBy { it.id }
    }

    private fun loadIndex(): List<SkillEntry> = runCatching {
        val json = context.assets.open(INDEX_ASSET).bufferedReader().use { JsonParser.parse(it.readText()) }
        json.arr("skills").orEmpty().mapNotNull { node -> entryFromJson(node, "bundled") }
    }.getOrDefault(emptyList())

    private fun entryFromJson(node: Json, origin: String): SkillEntry? {
        val owner = node.str("owner") ?: return null
        val repo = node.str("repo") ?: return null
        val base = SkillRepositoryRef.parse("$owner/$repo") ?: return null
        val root = runCatching { normalizeSkillPath(node.str("path").orEmpty()) }.getOrNull() ?: return null
        return SkillEntry(
            id = base.skillId(root), name = node.str("name") ?: return null,
            description = node.str("description").orEmpty(), origin = origin,
            license = node.str("license").orEmpty(), owner = owner, repo = repo,
            branch = node.str("branch").orEmpty(), path = root, problem = node.str("problem")?.takeIf { it.isNotBlank() },
        )
    }

    private fun readCatalog(source: SkillSource): List<SkillEntry> = runCatching {
        val file = File(cacheDir, "catalog-${skillCacheKey(source.id)}.json")
        if (!file.isFile || file.length() > 16L * 1024 * 1024) return emptyList()
        JsonParser.parse(file.readText()).arr("skills").orEmpty().mapNotNull { entryFromJson(it, "github") }
    }.getOrDefault(emptyList())

    private fun writeCatalog(source: SkillSource, entries: List<SkillEntry>) {
        atomicText(File(cacheDir, "catalog-${skillCacheKey(source.id)}.json"), Json.Obj(mapOf("skills" to Json.Arr(entries.map { entry ->
            obj("name" to entry.name, "description" to entry.description, "license" to entry.license,
                "owner" to entry.owner, "repo" to entry.repo, "branch" to entry.branch, "path" to entry.path,
                "problem" to entry.problem.orEmpty())
        }))).stringify())
    }

    private fun readSources(): Pair<List<SkillSource>, String> {
        val json = runCatching { if (sourcesFile.length() in 1..65_536) JsonParser.parse(sourcesFile.readText()) else null }.getOrNull()
        val saved = json?.arr("sources").orEmpty().mapNotNull { node ->
            val base = SkillRepositoryRef.parse("${node.str("owner")}/${node.str("repo")}") ?: return@mapNotNull null
            runCatching { SkillSource(base.copy(branch = normalizeSkillPath(node.str("branch").orEmpty()), path = normalizeSkillPath(node.str("path").orEmpty()))) }.getOrNull()
        }
        val sources = (DEFAULT_SOURCES + saved).distinctBy { it.id }.take(MAX_SOURCES)
        val selected = json?.str("selected")?.takeIf { id -> id == FEATURED_SOURCE || sources.any { it.id == id } } ?: FEATURED_SOURCE
        return sources to selected
    }

    private fun saveSources(sources: List<SkillSource>, selected: String) = atomicText(sourcesFile, Json.Obj(mapOf(
        "selected" to Json.Str(selected),
        "sources" to Json.Arr(sources.map { obj("owner" to it.ref.owner, "repo" to it.ref.repo, "branch" to it.ref.branch, "path" to it.ref.path) }),
    )).stringify())

    /** Malformed or symlinked installed entries do not hide the rest of the library. */
    fun scanInstalled(): List<InstalledSkill> {
        val enabled = readEnabled()
        return storage.skillsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }.orEmpty().mapNotNull { dir ->
            runCatching {
                val file = File(dir, "SKILL.md")
                if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > 1024 * 1024) return@mapNotNull null
                val manifest = SkillManifestParser.parse(file.readText()).getOrNull() ?: return@mapNotNull null
                val id = readOriginId(dir, manifest)
                InstalledSkill(manifest, dir, id in enabled || manifest.name in enabled, id)
            }.getOrNull()
        }.sortedBy { it.manifest.name }
    }

    private fun readOriginId(dir: File, manifest: SkillManifest): String {
        val file = File(dir, ORIGIN_FILE)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > 65_536) return "disk/${dir.name}"
        val text = file.readText()
        val json = JsonParser.parseOrNull(text)
        if (json != null) {
            val ref = SkillRepositoryRef.parse("${json.str("owner")}/${json.str("repo")}")
            val path = json.str("path")
            if (ref != null && path != null) return ref.skillId(path)
        }
        // Legacy marker had no path. Migrate only an unambiguous curated displayName/manifest match.
        val legacy = text.lineSequence().mapNotNull { line -> if ('=' in line) line.substringBefore('=') to line.substringAfter('=') else null }.toMap()
        val candidates = loadIndex().filter { it.owner.equals(legacy["owner"], true) && it.repo.equals(legacy["repo"], true) &&
            (it.path.substringAfterLast('/') == manifest.name || it.name == legacy["displayName"]) }
        return candidates.singleOrNull()?.id ?: "disk/${dir.name}"
    }

    fun uninstall(id: String) = operation(id) {
        val installed = scanInstalled()
        val match = installed.firstOrNull { it.id == id } ?: installed.singleOrNull { it.manifest.name == id } ?: error("找不到已安装技能")
        deleteSkillTree(match.dir)
        synchronized(this) { writeEnabled(readEnabled() - match.id - match.manifest.name) }
        val remaining = scanInstalled()
        _state.update { it.copy(installed = remaining, available = markInstalled(it.available, remaining), message = "已卸载 ${match.manifest.name}") }
    }

    private fun readEnabled(): Set<String> = runCatching {
        if (!enabledFile.isFile || enabledFile.length() > 1024 * 1024) return emptySet()
        JsonParser.parse(enabledFile.readText()).arr("enabled")?.mapNotNull { it.asString }?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    private fun writeEnabled(ids: Set<String>) = atomicText(enabledFile, Json.Obj(mapOf("enabled" to Json.Arr(ids.sorted().map { Json.Str(it) }))).stringify())

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val installed = scanInstalled()
        val selected = installed.firstOrNull { it.id == id } ?: installed.singleOrNull { it.manifest.name == id }
        val key = selected?.id ?: id
        val current = readEnabled().toMutableSet()
        // Move legacy name preferences to stable identities before changing one same-named skill.
        installed.filter { it.manifest.name in current }.forEach { current.add(it.id) }
        installed.forEach { current.remove(it.manifest.name) }
        if (enabled) current.add(key) else current.remove(key)
        writeEnabled(current)
        _state.update { state -> state.copy(installed = state.installed.map { it.copy(enabled = it.id in current) }) }
    }

    fun enabledSkills(): List<InstalledSkill> = scanInstalled().filter { it.enabled && it.manifest.isUsable }

    private fun atomicText(file: File, text: String) {
        require(file.parentFile.isDirectory || file.parentFile.mkdirs())
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try { temporary.writeText(text); Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        finally { temporary.delete() }
    }

    private fun obj(vararg pairs: Pair<String, String>): Json.Obj = Json.Obj(pairs.associate { it.first to Json.Str(it.second) })

    private companion object {
        const val INDEX_ASSET = "skills_index.json"
        const val ORIGIN_FILE = ".mnnkit_skill_origin"
        const val MAX_SOURCES = 20
        const val CACHE_TTL = 24L * 60 * 60 * 1000
        val DEFAULT_SOURCES = listOf(SkillSource(SkillRepositoryRef("anthropics", "skills")), SkillSource(SkillRepositoryRef("openai", "skills")))
        val GITHUB_HEADERS = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    }
}

/** Prompt-only integration: installed scripts are never invoked. */
object SkillPromptBuilder {
    fun buildIndexPrompt(skills: List<InstalledSkill>): String? {
        val usable = skills.filter { it.manifest.isUsable }
        if (usable.isEmpty()) return null
        return buildString {
            appendLine("你可以使用以下技能（Skills）。当用户请求匹配某个技能的描述时，")
            appendLine("按该技能的说明完成任务；不要声称你调用了不存在的工具。")
            for (s in usable) append("- ").append(s.manifest.name).append("：").appendLine(s.manifest.description)
        }.trim()
    }

    fun buildFullPrompt(skills: List<InstalledSkill>, maxChars: Int = 6000): String? {
        val usable = skills.filter { it.manifest.isUsable }
        if (usable.isEmpty()) return null
        val sb = StringBuilder("你可以使用以下技能（Skills）：\n")
        for (s in usable) {
            val header = "\n## 技能：${s.manifest.name}\n${s.manifest.description}\n"
            if (sb.length + header.length > maxChars) break
            sb.append(header)
            if (s.manifest.body.isNotBlank()) {
                val room = maxChars - sb.length
                if (room <= 0) break
                sb.appendLine(if (s.manifest.body.length <= room) s.manifest.body else s.manifest.body.take(room) + "\n…（已截断）")
            }
        }
        return sb.toString().trim()
    }
}
