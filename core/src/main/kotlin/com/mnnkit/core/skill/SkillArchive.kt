package com.mnnkit.core.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ArchiveSkill(val root: String, val markdown: String)

/** All limits fail explicitly: a skill is never silently truncated. */
data class SkillArchiveLimits(
    val archiveBytes: Long = 64L * 1024 * 1024,
    val expandedBytes: Long = 256L * 1024 * 1024,
    val fileBytes: Long = 16L * 1024 * 1024,
    val manifestBytes: Long = 1024L * 1024,
    val entries: Int = 20_000,
    val compressionRatio: Long = 1000,
)

/** Repository archives are data only: permissions are not restored and scripts are never run. */
object SkillArchive {
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024

    fun discover(archive: File, limits: SkillArchiveLimits = SkillArchiveLimits()): List<ArchiveSkill> {
        validateCentralDirectory(archive, limits)
        return ZipFile(archive).use { zip ->
            entries(zip, limits).filter { !it.entry.isDirectory && it.path.substringAfterLast('/') == "SKILL.md" }
                .map { item ->
                    val bytes = ByteArrayOutputStream()
                    zip.getInputStream(item.entry).use { copyChecked(it, bytes, item.entry, limits.manifestBytes) }
                    ArchiveSkill(item.path.substringBeforeLast('/', ""), bytes.toString("UTF-8"))
                }.sortedBy { it.root }
        }
    }

    /** Exact paths win; legacy short hints resolve only when unique. Empty root never picks arbitrarily. */
    fun resolveRoot(roots: List<String>, requested: String): String {
        val want = normalizeSkillPath(requested)
        val unique = roots.distinct()
        if (want.isNotEmpty() && want in unique) return want
        if (want.isEmpty()) {
            require(unique.size == 1) { if (unique.isEmpty()) "No SKILL.md found" else "Multiple skills found; select a skill first" }
            return unique.single()
        }
        val matches = unique.filter { it.endsWith("/$want") }
        require(matches.size == 1) { if (matches.isEmpty()) "No SKILL.md at '$want'" else "Ambiguous skill path '$want'; select its full path" }
        return matches.single()
    }

    /** Destination must not exist. On failure only our new staging directory is removed. */
    fun extract(archive: File, root: String, destination: File, limits: SkillArchiveLimits = SkillArchiveLimits()): Int {
        val selected = normalizeSkillPath(root)
        require(!Files.exists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Staging already exists" }
        checkNoSymlinkAncestors(destination.parentFile)
        validateCentralDirectory(archive, limits)
        return ZipFile(archive).use { zip ->
            val all = entries(zip, limits)
            val manifest = if (selected.isEmpty()) "SKILL.md" else "$selected/SKILL.md"
            require(all.any { it.path == manifest && !it.entry.isDirectory }) { "No SKILL.md at selected root" }
            val prefix = if (selected.isEmpty()) "" else "$selected/"
            val files = all.filter { !it.entry.isDirectory && it.path.startsWith(prefix) }
            require(destination.mkdirs()) { "Cannot create staging directory" }
            try {
                var total = 0L
                for (item in files) {
                    val relative = item.path.removePrefix(prefix)
                    val target = File(destination, relative)
                    require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "Unsafe extraction path" }
                    checkNoSymlinkAncestors(target.parentFile)
                    require(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Cannot create skill directory" }
                    require(!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Duplicate extraction path" }
                    val limit = minOf(limits.fileBytes, limits.expandedBytes - total)
                    target.outputStream().use { output ->
                        total += zip.getInputStream(item.entry).use { input -> copyChecked(input, output, item.entry, limit) }
                    }
                }
                files.size
            } catch (error: Exception) {
                deleteSkillTree(destination)
                throw error
            }
        }
    }

    private data class Item(val entry: ZipEntry, val path: String)

    private fun entries(zip: ZipFile, limits: SkillArchiveLimits): List<Item> {
        val all = mutableListOf<ZipEntry>()
        val names = mutableSetOf<String>()
        val nodes = mutableMapOf<String, Boolean>()
        var expanded = 0L
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            require(all.size < limits.entries) { "Repository archive has too many entries" }
            val path = safeArchivePath(entry.name)
            val key = path.lowercase(Locale.ROOT)
            require(names.add(key)) { "Duplicate or case-colliding archive path: $path" }
            require(entry.size in 0..limits.fileBytes) { "Archive file exceeds size limit: $path" }
            if (!entry.isDirectory) {
                expanded += entry.size
                require(expanded <= limits.expandedBytes) { "Repository archive exceeds expanded size limit" }
                require(entry.size <= 1024 * 1024 || entry.size <= maxOf(1, entry.compressedSize) * limits.compressionRatio) { "Suspicious archive compression ratio" }
            }
            nodes[key] = entry.isDirectory
            all += entry
        }
        require(all.isNotEmpty()) { "Empty repository archive" }
        for (name in nodes.keys) {
            var parent = name.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(nodes[parent] != false) { "File conflicts with directory: $parent" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        // GitHub ZIP always has one generated top-level directory. Never strip a skill directory twice.
        val wrappers = all.map { it.name.substringBefore('/') }.distinct()
        require(wrappers.size == 1 && all.all { it.isDirectory || '/' in it.name }) { "Expected a wrapped GitHub repository ZIP" }
        val wrapper = wrappers.single() + "/"
        return all.mapNotNull { entry ->
            val path = entry.name.removePrefix(wrapper).trimEnd('/')
            if (path.isEmpty()) null else Item(entry, path)
        }
    }

    private fun safeArchivePath(name: String): String {
        require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && name.none { it.code < 32 }) { "Unsafe archive path" }
        val path = name.removeSuffix("/")
        require(path.length <= 1024) { "Archive path too long" }
        require(path.split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && !part.endsWith('.') && !part.endsWith(' ') &&
                !Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?").matches(part)
        }) { "Unsafe archive path: $path" }
        return path
    }

    private fun copyChecked(input: InputStream, output: OutputStream, entry: ZipEntry, max: Long): Long {
        val buffer = ByteArray(32 * 1024)
        val crc = CRC32()
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= max && copied <= entry.size) { "Archive entry exceeds declared/allowed size" }
            crc.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        require(copied == entry.size && crc.value == entry.crc) { "Corrupt archive entry" }
        return copied
    }

    /** Java ZipEntry hides Unix external attributes. Inspect bounded central records to reject links/devices. */
    private fun validateCentralDirectory(archive: File, limits: SkillArchiveLimits) {
        require(archive.isFile && !Files.isSymbolicLink(archive.toPath()) && archive.length() in 22..limits.archiveBytes) { "Invalid or oversized repository archive" }
        RandomAccessFile(archive, "r").use { file ->
            val length = file.length()
            val tail = ByteArray(minOf(length, 65_557).toInt())
            file.seek(length - tail.size)
            file.readFully(tail)
            fun u16(a: ByteArray, n: Int): Int = (a[n].toInt() and 255) or ((a[n + 1].toInt() and 255) shl 8)
            fun u32(a: ByteArray, n: Int): Long = u16(a, n).toLong() or (u16(a, n + 2).toLong() shl 16)
            val end = (tail.size - 22 downTo 0).firstOrNull {
                u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
            } ?: error("Missing ZIP directory")
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Multipart ZIP not supported" }
            val count = u16(tail, end + 10)
            require(count in 1..limits.entries && count != 65535 && u16(tail, end + 8) == count) { "ZIP entry limit/ZIP64 not supported" }
            val size = u32(tail, end + 12)
            val start = u32(tail, end + 16)
            require(start + size == length - tail.size + end) { "Invalid ZIP directory bounds" }
            file.seek(start)
            repeat(count) {
                val header = ByteArray(46)
                require(file.filePointer + header.size <= start + size) { "Truncated ZIP directory" }
                file.readFully(header)
                require(u32(header, 0) == 0x02014b50L) { "Invalid ZIP directory" }
                require(u16(header, 8) and 1 == 0) { "Encrypted ZIP not supported" }
                val mode = (u32(header, 38) shr 16).toInt() and 0xf000
                require(mode == 0 || mode == 0x8000 || mode == 0x4000) { "Archive symlinks/devices are not allowed" }
                require(u32(header, 20) != 0xffffffffL && u32(header, 24) != 0xffffffffL && u32(header, 42) != 0xffffffffL) { "ZIP64 not supported" }
                val skip = u16(header, 28) + u16(header, 30) + u16(header, 32)
                require(file.filePointer + skip <= start + size) { "Invalid ZIP directory lengths" }
                file.seek(file.filePointer + skip)
            }
            require(file.filePointer == start + size) { "ZIP directory count mismatch" }
        }
    }
}

internal fun checkNoSymlinkAncestors(file: File?) {
    var current = file?.absoluteFile
    while (current != null) {
        require(!Files.isSymbolicLink(current.toPath())) { "Symlink directories are not allowed" }
        current = current.parentFile
    }
}

/** Unlike File.walk/copy helpers, this never traverses a directory symlink. */
fun deleteSkillTree(file: File) {
    if (Files.isSymbolicLink(file.toPath())) {
        Files.deleteIfExists(file.toPath())
        return
    }
    if (file.isDirectory) file.listFiles()?.forEach(::deleteSkillTree)
    if (file.exists()) check(file.delete()) { "Cannot remove ${file.name}" }
}

object SkillInstallStaging {
    /** Same-filesystem rename transaction: never copy a partial update over the working install. */
    fun replace(staging: File, target: File) = replace(staging, target) { from, to -> from.renameTo(to) }

    internal fun replace(staging: File, target: File, move: (File, File) -> Boolean) {
        checkNoSymlinkAncestors(staging)
        checkNoSymlinkAncestors(target)
        require(staging.isDirectory && File(staging, "SKILL.md").isFile) { "Invalid staged skill" }
        require(!Files.isSymbolicLink(File(staging, "SKILL.md").toPath())) { "Symlink manifest not allowed" }
        require(staging.canonicalFile != target.canonicalFile && !staging.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "Invalid staging location" }
        require(target.parentFile.isDirectory || target.parentFile.mkdirs())
        val backup = File(target.parentFile, ".backup-${target.name}-${UUID.randomUUID()}")
        val existed = target.exists()
        if (existed) check(move(target, backup)) { "Cannot preserve prior install" }
        if (!move(staging, target)) {
            if (existed) check(move(backup, target)) { "Update failed; prior install retained at ${backup.absolutePath}" }
            error("Cannot activate staged skill; prior install preserved")
        }
        // Activation succeeded. Cleanup failure must not misreport a successful update as failure.
        if (existed) runCatching { deleteSkillTree(backup) }
    }
}
