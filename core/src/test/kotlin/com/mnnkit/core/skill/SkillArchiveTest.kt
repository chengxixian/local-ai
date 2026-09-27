package com.mnnkit.core.skill

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillArchiveTest {
    private val markdown = "---\nname: example\ndescription: Example skill\nlicense: MIT\n---\nInstructions"

    private fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("skill-archive-test").toFile()
        try { block(dir) } finally { deleteSkillTree(dir) }
    }

    private fun zip(dir: File, vararg entries: Pair<String, String>): File {
        val file = File(dir, "repository.zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (path, body) ->
                output.putNextEntry(ZipEntry(path))
                output.write(body.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }

    @Test fun `root links use default branch instead of hardcoded main`() {
        val ref = assertNotNull(SkillRepositoryRef.parse("https://github.com/Owner/Repo.git/?tab=readme#skills"))
        assertEquals("owner/repo", ref.repositoryId)
        assertEquals("", ref.branch)
        assertEquals("", ref.path)
    }

    @Test fun `shorthand and tree links normalize paths`() {
        assertEquals("skills/pdf", SkillRepositoryRef.parse("owner/repo/skills/pdf/")?.path)
        val ref = assertNotNull(SkillRepositoryRef.parse(" <https://github.com/owner/repo/tree/main/skills/pdf/> "))
        assertEquals("main", ref.branch)
        assertEquals("skills/pdf", ref.path)
    }

    @Test fun `blob manifest links normalize to directory`() {
        assertEquals("skills/pdf", SkillRepositoryRef.parse("https://github.com/a/b/blob/main/skills/pdf/SKILL.md?plain=1")?.path)
        assertEquals("", SkillRepositoryRef.parse("https://github.com/a/b/blob/main/SKILL.md")?.path)
        assertNull(SkillRepositoryRef.parse("https://github.com/a/b/blob/main/README.md"))
    }

    @Test fun `encoded ref slash and space path decode without losing plus`() {
        val ref = assertNotNull(SkillRepositoryRef.parse("https://github.com/a/b/tree/feature%2Fskills/some%20dir/a+b"))
        assertEquals("feature/skills", ref.branch)
        assertEquals("some dir/a+b", ref.path)
    }

    @Test fun `rejects invalid hosts routes credentials and traversal`() {
        listOf("", "one", "https://example.com/a/b", "https://github.com.evil/a/b",
            "https://me@github.com/a/b", "https://github.com/a/b/issues", "a/b/../private",
            "https://github.com/a/b/tree/main/%2e%2e/private", "a/b/x%5Cy", "a/b/x:y").forEach {
            assertNull(SkillRepositoryRef.parse(it), it)
        }
    }

    @Test fun `stable identity ignores display name and repository casing`() {
        assertEquals(SkillRepositoryRef("A", "B").skillId("skills/pdf"), SkillRepositoryRef("a", "b", "other").skillId("skills/pdf"))
        assertTrue(SkillRepositoryRef("a", "b").skillId("pdf") != SkillRepositoryRef("a", "other").skillId("pdf"))
    }

    @Test fun `discovers exact root and nested manifests recursively`() = temp { dir ->
        val file = zip(dir, "repo-main/SKILL.md" to markdown, "repo-main/skills/pdf/SKILL.md" to markdown,
            "repo-main/deep/a/b/SKILL.md" to markdown, "repo-main/notSKILL.md" to markdown, "repo-main/lower/skill.md" to markdown)
        assertEquals(listOf("", "deep/a/b", "skills/pdf"), SkillArchive.discover(file).map { it.root })
    }

    @Test fun `single nested skill resolves root link`() {
        assertEquals("skills/pdf", SkillArchive.resolveRoot(listOf("skills/pdf"), ""))
        assertEquals("", SkillArchive.resolveRoot(listOf(""), ""))
    }

    @Test fun `exact path and unique suffix resolve but unrelated path does not`() {
        val roots = listOf("skills/pdf", "skills/docx")
        assertEquals("skills/pdf", SkillArchive.resolveRoot(roots, "skills/pdf/SKILL.md"))
        assertEquals("skills/pdf", SkillArchive.resolveRoot(roots, "pdf"))
        assertFailsWith<IllegalArgumentException> { SkillArchive.resolveRoot(listOf("skills/pdf"), "missing") }
    }

    @Test fun `multiple root and ambiguous paths require selection`() {
        assertFailsWith<IllegalArgumentException> { SkillArchive.resolveRoot(listOf("", "skills/pdf"), "") }
        assertFailsWith<IllegalArgumentException> { SkillArchive.resolveRoot(listOf("a/pdf", "b/pdf"), "pdf") }
        assertFailsWith<IllegalArgumentException> { SkillArchive.resolveRoot(emptyList(), "") }
    }

    @Test fun `extracts selected subtree only with root prefix stripped`() = temp { dir ->
        val file = zip(dir, "repo-main/skills/pdf/SKILL.md" to markdown,
            "repo-main/skills/pdf/references/guide.md" to "guide", "repo-main/skills/pdf/scripts/a.py" to "print('data only')",
            "repo-main/skills/pdf/assets/image.bin" to "binary", "repo-main/skills/docx/SKILL.md" to markdown)
        val stage = File(dir, "stage")
        assertEquals(4, SkillArchive.extract(file, "skills/pdf", stage))
        assertEquals(markdown, File(stage, "SKILL.md").readText())
        assertEquals("guide", File(stage, "references/guide.md").readText())
        assertTrue(File(stage, "scripts/a.py").isFile)
        assertTrue(File(stage, "assets/image.bin").isFile)
        assertFalse(File(stage, "skills").exists())
        assertFalse(File(stage, "docx").exists())
    }

    @Test fun `extracts root skill without dropping its children`() = temp { dir ->
        val file = zip(dir, "repo-main/SKILL.md" to markdown, "repo-main/assets/a.txt" to "asset")
        val stage = File(dir, "stage")
        assertEquals(2, SkillArchive.extract(file, "", stage))
        assertTrue(File(stage, "assets/a.txt").isFile)
    }

    @Test fun `does not silently truncate beyond eighty files`() = temp { dir ->
        val entries = listOf("repo-main/pdf/SKILL.md" to markdown) + (1..100).map { "repo-main/pdf/references/$it.txt" to "content $it" }
        val file = zip(dir, *entries.toTypedArray())
        assertEquals(101, SkillArchive.extract(file, "pdf", File(dir, "stage")))
    }

    @Test fun `archive traversal and unsafe paths are rejected not sanitized`() = temp { dir ->
        listOf("repo-main/../escape", "/absolute", "repo-main/a\\b", "repo-main/C:/a", "repo-main/./a",
            "repo-main/a//b", "repo-main/trailing.", "repo-main/NUL").forEach { bad ->
            val file = zip(dir, "repo-main/SKILL.md" to markdown, bad to "bad")
            assertFailsWith<IllegalArgumentException>(bad) { SkillArchive.discover(file) }
        }
    }

    @Test fun `case collision and file directory conflicts are rejected`() = temp { dir ->
        var file = zip(dir, "repo-main/SKILL.md" to markdown, "repo-main/a.txt" to "a", "repo-main/A.txt" to "b")
        assertFailsWith<IllegalArgumentException> { SkillArchive.discover(file) }
        file = zip(dir, "repo-main/SKILL.md" to markdown, "repo-main/a" to "a", "repo-main/a/b" to "b")
        assertFailsWith<IllegalArgumentException> { SkillArchive.discover(file) }
    }

    @Test fun `rejects multiple wrappers and unwrapped archives`() = temp { dir ->
        var file = zip(dir, "a/SKILL.md" to markdown, "b/file" to "b")
        assertFailsWith<IllegalArgumentException> { SkillArchive.discover(file) }
        file = zip(dir, "SKILL.md" to markdown)
        assertFailsWith<IllegalArgumentException> { SkillArchive.discover(file) }
    }

    @Test fun `enforces archive entry expanded per file and manifest size limits`() = temp { dir ->
        val file = zip(dir, "repo-main/SKILL.md" to markdown, "repo-main/a.txt" to "body")
        listOf(SkillArchiveLimits(archiveBytes = 22), SkillArchiveLimits(entries = 1),
            SkillArchiveLimits(expandedBytes = 5), SkillArchiveLimits(fileBytes = 5), SkillArchiveLimits(manifestBytes = 5)).forEach { limits ->
            assertFailsWith<IllegalArgumentException> { SkillArchive.discover(file, limits) }
        }
    }

    @Test fun `compression bombs fail before extraction`() = temp { dir ->
        val file = zip(dir, "repo-main/SKILL.md" to markdown, "repo-main/huge.txt" to "0".repeat(2 * 1024 * 1024))
        assertFailsWith<IllegalArgumentException> { SkillArchive.discover(file, SkillArchiveLimits(compressionRatio = 10)) }
    }

    @Test fun `unix symlink central attributes are rejected`() = temp { dir ->
        val file = zip(dir, "repo-main/SKILL.md" to markdown)
        val bytes = file.readBytes()
        val index = (0..bytes.size - 46).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[index + 5] = 3 // Unix
        bytes[index + 40] = 0xff.toByte()
        bytes[index + 41] = 0xa1.toByte() // S_IFLNK | 0777
        file.writeBytes(bytes)
        assertFailsWith<IllegalArgumentException> { SkillArchive.discover(file) }
    }

    @Test fun `missing selection or occupied staging never overwrites existing files`() = temp { dir ->
        val file = zip(dir, "repo-main/pdf/SKILL.md" to markdown)
        val stage = File(dir, "stage").apply { mkdirs() }
        File(stage, "keep").writeText("prior")
        assertFailsWith<IllegalArgumentException> { SkillArchive.extract(file, "pdf", stage) }
        assertEquals("prior", File(stage, "keep").readText())
        assertFailsWith<IllegalArgumentException> { SkillArchive.extract(file, "missing", File(dir, "new")) }
        assertFalse(File(dir, "new").exists())
    }

    @Test fun `invalid staging preserves prior install`() = temp { dir ->
        val target = File(dir, "installed").apply { mkdirs() }
        File(target, "SKILL.md").writeText("old")
        val stage = File(dir, "stage").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> { SkillInstallStaging.replace(stage, target) }
        assertEquals("old", File(target, "SKILL.md").readText())
    }

    @Test fun `successful update switches complete subtree and removes old files`() = temp { dir ->
        val target = File(dir, "installed").apply { mkdirs() }
        File(target, "SKILL.md").writeText("old")
        File(target, "old.txt").writeText("old")
        val stage = File(dir, "stage").apply { mkdirs() }
        File(stage, "SKILL.md").writeText(markdown)
        SkillInstallStaging.replace(stage, target)
        assertEquals(markdown, File(target, "SKILL.md").readText())
        assertFalse(stage.exists())
        assertFalse(File(target, "old.txt").exists())
        assertFalse(dir.listFiles()!!.any { it.name.startsWith(".backup-") })
    }

    @Test fun `failed activation restores old installation`() = temp { dir ->
        val target = File(dir, "installed").apply { mkdirs() }
        File(target, "SKILL.md").writeText("old")
        val stage = File(dir, "stage").apply { mkdirs() }
        File(stage, "SKILL.md").writeText(markdown)
        assertFailsWith<IllegalStateException> {
            SkillInstallStaging.replace(stage, target) { from, to -> if (from == stage) false else from.renameTo(to) }
        }
        assertEquals("old", File(target, "SKILL.md").readText())
        assertTrue(stage.exists())
    }

    @Test fun `failed backup leaves prior install untouched`() = temp { dir ->
        val target = File(dir, "installed").apply { mkdirs() }
        File(target, "SKILL.md").writeText("old")
        val stage = File(dir, "stage").apply { mkdirs() }
        File(stage, "SKILL.md").writeText(markdown)
        assertFailsWith<IllegalStateException> { SkillInstallStaging.replace(stage, target) { _, _ -> false } }
        assertEquals("old", File(target, "SKILL.md").readText())
    }
}
