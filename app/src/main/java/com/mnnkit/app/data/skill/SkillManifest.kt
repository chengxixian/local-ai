package com.mnnkit.app.data.skill

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser

/**
 * 一个 Agent Skill 的清单。
 *
 * 字段严格对齐 Agent Skills 规范（https://agentskills.io/specification）。
 * 规范里 frontmatter 只有 6 个字段：
 *   name(必填, <=64, 小写字母/数字/连字符)
 *   description(必填, <=1024)
 *   license
 *   compatibility(<=500)
 *   metadata(string -> string 映射)
 *   allowed-tools(空格分隔, 实验性)
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val license: String = "",
    val compatibility: String = "",
    val allowedTools: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    /** SKILL.md 中 frontmatter 之后的正文 */
    val body: String = "",
    /** frontmatter 里出现的未知字段，保留以便前向兼容 */
    val extra: Map<String, String> = emptyMap(),
) {
    val isUsable: Boolean get() = name.isNotBlank()

    /** 缺少必填字段时的说明，供 UI 提示。 */
    fun validationError(): String? = when {
        name.isBlank() -> "缺少必填字段 name"
        name.length > 64 -> "name 超过 64 字符"
        !NAME_PATTERN.matches(name) -> "name 只能包含小写字母、数字与连字符"
        description.isBlank() -> "缺少必填字段 description"
        description.length > 1024 -> "description 超过 1024 字符"
        else -> null
    }

    companion object {
        private val NAME_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }
}

/**
 * SKILL.md 解析器。
 *
 * 为什么不引 YAML 库：规范限定的 frontmatter 是"扁平标量 + 少量列表 + metadata 映射"，
 * 一个 ~150 行的解析器即可覆盖，且能给出精确到行的错误信息。
 * 解析失败不会抛异常 —— 技能库里的单个坏文件不应该影响整个列表。
 */
object SkillManifestParser {

    private const val FENCE = "---"

    /** 解析 SKILL.md 全文。 */
    fun parse(markdown: String): Result<SkillManifest> {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')

        // 定位 frontmatter 围栏
        var start = -1
        for (i in lines.indices) {
            if (lines[i].isBlank()) continue
            if (lines[i].trim() == FENCE) start = i
            break
        }
        if (start < 0) {
            return Result.failure(SkillParseException("文件未以 $FENCE 开头，缺少 YAML frontmatter"))
        }
        var end = -1
        for (i in start + 1 until lines.size) {
            if (lines[i].trim() == FENCE) {
                end = i
                break
            }
        }
        if (end < 0) {
            return Result.failure(SkillParseException("frontmatter 没有闭合的 $FENCE"))
        }

        val fmLines = lines.subList(start + 1, end)
        val body = lines.drop(end + 1).joinToString("\n").trim()

        return try {
            val fields = parseFields(fmLines)
            val metadata = parseMetadata(fields["metadata"])

            val manifest = SkillManifest(
                name = fields["name"].orEmpty().trim(),
                description = cleanScalar(fields["description"].orEmpty()),
                license = fields["license"].orEmpty().trim(),
                compatibility = fields["compatibility"].orEmpty().trim(),
                allowedTools = fields["allowed-tools"].orEmpty()
                    .split(' ', '\n', '\t')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                metadata = metadata,
                body = body,
                extra = fields.filterKeys { it !in KNOWN_KEYS },
            )
            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(SkillParseException("frontmatter 解析失败：${e.message}", e))
        }
    }

    private val KNOWN_KEYS = setOf(
        "name", "description", "license", "compatibility", "metadata", "allowed-tools",
    )

    /**
     * 解析顶层 `key: value`。
     * 支持：
     *   - 单行标量：`name: pdf-tools`
     *   - 带引号：`description: "含: 冒号的值"`
     *   - 块标量：`description: |` 后跟缩进多行
     *   - 嵌套映射（仅一层，用于 metadata）
     *   - 行内列表：`allowed-tools: [a, b]`
     * 值统一以"未去缩进的原始文本"保存，由 [cleanScalar] 收尾。
     */
    internal fun parseFields(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) {
                i++
                continue
            }
            val indent = raw.takeWhile { it == ' ' }.length
            if (indent > 0) {
                // 顶层字段之外的缩进行，交由块标量/嵌套处理
                i++
                continue
            }
            val colon = raw.indexOf(':')
            if (colon <= 0) {
                i++
                continue
            }
            val key = raw.substring(0, colon).trim()
            val rest = raw.substring(colon + 1).trim()

            when {
                // 块标量：| 或 >
                rest == "|" || rest == ">" || rest == "|-" || rest == ">-" -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    var blockIndent = -1
                    val fold = rest.startsWith(">")
                    while (j < lines.size) {
                        val l = lines[j]
                        if (l.isBlank()) {
                            sb.append('\n')
                            j++
                            continue
                        }
                        val li = l.takeWhile { it == ' ' }.length
                        if (li == 0) break
                        if (blockIndent < 0) blockIndent = li
                        if (li < blockIndent) break
                        val content = l.substring(blockIndent)
                        if (fold && sb.isNotEmpty() && !sb.endsWith("\n")) sb.append(' ')
                        sb.append(content)
                        if (!fold) sb.append('\n')
                        j++
                    }
                    out[key] = sb.toString().trimEnd('\n')
                    i = j
                    continue
                }

                // 嵌套映射（metadata: 后跟缩进的 key: value）
                rest.isEmpty() -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < lines.size) {
                        val l = lines[j]
                        if (l.isBlank()) {
                            j++
                            continue
                        }
                        val li = l.takeWhile { it == ' ' }.length
                        if (li == 0) break
                        sb.append(l.trim()).append('\n')
                        j++
                    }
                    out[key] = sb.toString().trimEnd('\n')
                    i = j
                    continue
                }

                else -> {
                    out[key] = rest
                    i++
                }
            }
        }
        return out
    }

    /** 把 `metadata` 的嵌套文本解析成映射。 */
    internal fun parseMetadata(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('\n').forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val c = t.indexOf(':')
            if (c <= 0) return@forEach
            val k = t.substring(0, c).trim()
            val v = cleanScalar(t.substring(c + 1))
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** 去掉包裹引号并处理常见转义。 */
    internal fun cleanScalar(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                s = s.substring(1, s.length - 1)
                if (first == '"') {
                    s = s.replace("\\n", "\n").replace("\\t", "\t")
                        .replace("\\\"", "\"").replace("\\\\", "\\")
                } else {
                    s = s.replace("''", "'")
                }
            }
        }
        return s.trim()
    }

    /**
     * 用 Skills 的渐进式披露规则渲染成可注入 prompt 的文本。
     *
     * 规范建议只把 name + description 常驻在上下文中，正文按需加载。
     * 端侧上下文有限，因此这里默认只给元数据，正文由 [SkillPromptBuilder] 按需附加。
     */
    fun toSummaryJson(manifests: List<SkillManifest>): Json =
        Json.Arr(
            manifests.map { m ->
                Json.Obj(
                    mapOf(
                        "name" to Json.Str(m.name),
                        "description" to Json.Str(m.description),
                        "allowed-tools" to Json.Arr(m.allowedTools.map { Json.Str(it) }),
                    )
                )
            }
        )

    /** 从任意文本里尽力提取 name（用于目录名与清单不一致时兜底）。 */
    fun guessName(text: String): String? =
        JsonParser.parseOrNull(text)?.str("name")
}

class SkillParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
