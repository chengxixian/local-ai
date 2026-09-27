package com.mnnkit.core.skill

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/** Empty branch means the repository's default branch, not necessarily main. */
data class SkillRepositoryRef(
    val owner: String,
    val repo: String,
    val branch: String = "",
    val path: String = "",
) {
    val repositoryId: String get() = "$owner/$repo".lowercase(Locale.ROOT)
    val sourceId: String get() = "$repositoryId@$branch/$path"
    fun skillId(root: String): String = "github/$repositoryId/${normalizeSkillPath(root)}"

    companion object {
        /** Slash-containing refs must be percent-encoded in tree/blob URLs. */
        fun parse(raw: String): SkillRepositoryRef? = runCatching {
            val text = raw.trim().trim('"', '\'', '<', '>')
            require(text.isNotBlank())
            val url = if (text.contains("://")) URI(text) else URI("https://github.com/$text")
            require(url.scheme in listOf("https", "http"))
            require(url.host?.lowercase(Locale.ROOT) in listOf("github.com", "www.github.com"))
            require(url.userInfo == null && url.port == -1)
            val pieces = url.rawPath.trim('/').split('/').map {
                URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
            }
            require(pieces.size >= 2)
            val owner = pieces[0]
            val repo = pieces[1].removeSuffix(".git")
            require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]*")))
            require(repo.matches(Regex("[A-Za-z0-9_.-]+")) && repo !in listOf(".", ".."))
            var branch = ""
            var path = ""
            if (pieces.size > 2) {
                if (pieces[2] in listOf("tree", "blob")) {
                    require(pieces.size >= 4)
                    branch = normalizeSkillPath(pieces[3])
                    require(branch.isNotEmpty())
                    path = pieces.drop(4).joinToString("/")
                    if (pieces[2] == "blob") require(path.substringAfterLast('/') == "SKILL.md")
                } else {
                    // A shorthand owner/repo/subdirectory is useful; unknown GitHub routes aren't.
                    require(!text.contains("://"))
                    path = pieces.drop(2).joinToString("/")
                }
            }
            SkillRepositoryRef(owner, repo, branch, normalizeSkillPath(path))
        }.getOrNull()
    }
}

/** Normalize user directory hints; reject traversal rather than silently stripping it. */
fun normalizeSkillPath(raw: String): String {
    val value = raw.trim().trim('/')
    require(!value.contains('\\') && !value.contains(':') && value.none { it.code < 32 }) { "Unsafe skill path" }
    if (value.isEmpty()) return ""
    val segments = value.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe skill path" }
    return if (segments.last() == "SKILL.md") segments.dropLast(1).joinToString("/") else value
}

fun skillCacheKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
