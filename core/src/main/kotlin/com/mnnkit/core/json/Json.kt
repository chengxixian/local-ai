package com.mnnkit.core.json

/**
 * 极简 JSON 值树。
 *
 * 刻意不引入 kotlinx-serialization / Gson / org.json：
 *  - core 模块保持零第三方依赖，纯 JVM 可测；
 *  - 不依赖 Kotlin 序列化编译器插件，避免与 Kotlin 版本绑定的构建风险。
 */
sealed class Json {

    data object Null : Json()

    data class Bool(val value: Boolean) : Json()

    data class Num(val value: Double) : Json() {
        val isIntegral: Boolean get() = value.isFinite() && value == Math.floor(value)
        fun toLongOrNull(): Long? =
            if (isIntegral && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
                value.toLong()
            } else {
                null
            }
    }

    data class Str(val value: String) : Json()

    data class Arr(val items: List<Json>) : Json()

    data class Obj(val fields: Map<String, Json>) : Json()

    // ---- 便捷访问器 ----

    operator fun get(key: String): Json? = (this as? Obj)?.fields?.get(key)

    operator fun get(index: Int): Json? = (this as? Arr)?.items?.getOrNull(index)

    val asString: String?
        get() = when (this) {
            is Str -> value
            is Num -> if (isIntegral) toLongOrNull()?.toString() ?: value.toString() else value.toString()
            is Bool -> value.toString()
            else -> null
        }

    val asLong: Long?
        get() = when (this) {
            is Num -> toLongOrNull()
            is Str -> value.toLongOrNull()
            else -> null
        }

    val asInt: Int? get() = asLong?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    val asDouble: Double?
        get() = when (this) {
            is Num -> value
            is Str -> value.toDoubleOrNull()
            else -> null
        }

    val asBool: Boolean?
        get() = when (this) {
            is Bool -> value
            is Str -> value.toBooleanStrictOrNull()
            else -> null
        }

    val asArray: List<Json>?
        get() = (this as? Arr)?.items

    val asObject: Map<String, Json>?
        get() = (this as? Obj)?.fields

    /** 沿路径取值，例如 `json.path("Data", "Files", 0, "Path")`。 */
    fun path(vararg keys: Any): Json? {
        var cur: Json? = this
        for (k in keys) {
            cur = when (k) {
                is String -> cur?.get(k)
                is Int -> cur?.get(k)
                else -> null
            } ?: return null
        }
        return cur
    }

    fun str(vararg keys: Any): String? = path(*keys)?.asString
    fun long(vararg keys: Any): Long? = path(*keys)?.asLong
    fun int(vararg keys: Any): Int? = path(*keys)?.asInt
    fun bool(vararg keys: Any): Boolean? = path(*keys)?.asBool
    fun arr(vararg keys: Any): List<Json>? = path(*keys)?.asArray

    companion object {
        fun of(value: String): Json = Str(value)
        fun of(value: Long): Json = Num(value.toDouble())
        fun of(value: Int): Json = Num(value.toDouble())
        fun of(value: Double): Json = Num(value)
        fun of(value: Boolean): Json = Bool(value)

        /** 从 Map/List/String 等 Kotlin 原生结构构造。 */
        fun from(value: Any?): Json = when (value) {
            null -> Null
            is Json -> value
            is String -> Str(value)
            is Boolean -> Bool(value)
            is Int -> Num(value.toDouble())
            is Long -> Num(value.toDouble())
            is Double -> Num(value)
            is Float -> Num(value.toDouble())
            is Map<*, *> -> Obj(value.entries.associate { (k, v) -> k.toString() to from(v) })
            is Iterable<*> -> Arr(value.map { from(it) })
            is Array<*> -> Arr(value.map { from(it) })
            else -> Str(value.toString())
        }
    }
}

class JsonParseException(message: String, val offset: Int) : Exception("$message (at offset $offset)")

/**
 * 递归下降 JSON 解析器。支持标准 JSON 全部语法：
 * 对象、数组、字符串（含 \\uXXXX 转义与代理对）、数字、true/false/null。
 */
object JsonParser {

    fun parse(text: String): Json {
        val p = Cursor(text)
        p.skipWs()
        val v = p.parseValue(0)
        p.skipWs()
        if (!p.eof()) throw JsonParseException("尾随内容", p.pos)
        return v
    }

    /** 解析失败时返回 null，用于容错读取外部数据。 */
    fun parseOrNull(text: String?): Json? {
        if (text.isNullOrBlank()) return null
        return try {
            parse(text)
        } catch (_: JsonParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_DEPTH = 200

    private class Cursor(val s: String) {
        var pos = 0
        fun eof() = pos >= s.length
        fun peek(): Char = if (eof()) '\u0000' else s[pos]
        fun next(): Char {
            if (eof()) throw JsonParseException("意外结束", pos)
            return s[pos++]
        }

        fun skipWs() {
            while (!eof()) {
                when (s[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun expect(c: Char) {
            if (eof() || s[pos] != c) throw JsonParseException("期望 '$c'", pos)
            pos++
        }

        fun parseValue(depth: Int): Json {
            if (depth > MAX_DEPTH) throw JsonParseException("嵌套过深", pos)
            skipWs()
            if (eof()) throw JsonParseException("空输入", pos)
            return when (val c = peek()) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> Json.Str(parseString())
                't' -> {
                    lit("true"); Json.Bool(true)
                }
                'f' -> {
                    lit("false"); Json.Bool(false)
                }
                'n' -> {
                    lit("null"); Json.Null
                }
                else -> if (c == '-' || c in '0'..'9') parseNumber() else
                    throw JsonParseException("非法字符 '$c'", pos)
            }
        }

        fun lit(word: String) {
            if (pos + word.length > s.length || s.regionMatches(pos, word, 0, word.length).not()) {
                throw JsonParseException("非法字面量，期望 '$word'", pos)
            }
            pos += word.length
        }

        fun parseObject(depth: Int): Json.Obj {
            expect('{')
            val map = LinkedHashMap<String, Json>()
            skipWs()
            if (peek() == '}') {
                pos++
                return Json.Obj(map)
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue(depth + 1)
                map[key] = value
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return Json.Obj(map)
                    else -> throw JsonParseException("对象中期望 ',' 或 '}'，得到 '$c'", pos - 1)
                }
            }
        }

        fun parseArray(depth: Int): Json.Arr {
            expect('[')
            val list = ArrayList<Json>()
            skipWs()
            if (peek() == ']') {
                pos++
                return Json.Arr(list)
            }
            while (true) {
                list.add(parseValue(depth + 1))
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return Json.Arr(list)
                    else -> throw JsonParseException("数组中期望 ',' 或 ']'，得到 '$c'", pos - 1)
                }
            }
        }

        fun parseString(): String {
            skipWs()
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val e = next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(readHex4())
                            else -> throw JsonParseException("非法转义 '\\$e'", pos - 1)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun readHex4(): Char {
            if (pos + 4 > s.length) throw JsonParseException("\\u 转义不完整", pos)
            var v = 0
            for (i in 0 until 4) {
                val d = Character.digit(s[pos + i], 16)
                if (d < 0) throw JsonParseException("非法十六进制位 '${s[pos + i]}'", pos + i)
                v = v * 16 + d
            }
            pos += 4
            return v.toChar()
        }

        fun parseNumber(): Json.Num {
            val start = pos
            if (peek() == '-') pos++
            while (!eof() && s[pos] in '0'..'9') pos++
            if (!eof() && s[pos] == '.') {
                pos++
                while (!eof() && s[pos] in '0'..'9') pos++
            }
            if (!eof() && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (!eof() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!eof() && s[pos] in '0'..'9') pos++
            }
            val raw = s.substring(start, pos)
            val d = raw.toDoubleOrNull() ?: throw JsonParseException("非法数字 '$raw'", start)
            return Json.Num(d)
        }
    }
}

/** 序列化回字符串（用于把配置写到磁盘）。 */
fun Json.stringify(pretty: Boolean = false): String {
    val sb = StringBuilder()
    writeTo(sb, pretty, 0)
    return sb.toString()
}

private fun Json.writeTo(sb: StringBuilder, pretty: Boolean, indent: Int) {
    when (this) {
        is Json.Null -> sb.append("null")
        is Json.Bool -> sb.append(if (value) "true" else "false")
        is Json.Num -> {
            if (isIntegral) {
                toLongOrNull()?.let { sb.append(it) } ?: sb.append(value)
            } else {
                sb.append(value)
            }
        }
        is Json.Str -> writeJsonString(sb, value)
        is Json.Arr -> {
            if (items.isEmpty()) {
                sb.append("[]")
                return
            }
            sb.append('[')
            items.forEachIndexed { i, item ->
                if (i > 0) sb.append(',')
                newline(sb, pretty, indent + 1)
                item.writeTo(sb, pretty, indent + 1)
            }
            newline(sb, pretty, indent)
            sb.append(']')
        }
        is Json.Obj -> {
            if (fields.isEmpty()) {
                sb.append("{}")
                return
            }
            sb.append('{')
            var first = true
            for ((k, v) in fields) {
                if (!first) sb.append(',')
                first = false
                newline(sb, pretty, indent + 1)
                writeJsonString(sb, k)
                sb.append(':')
                if (pretty) sb.append(' ')
                v.writeTo(sb, pretty, indent + 1)
            }
            newline(sb, pretty, indent)
            sb.append('}')
        }
    }
}

private fun newline(sb: StringBuilder, pretty: Boolean, indent: Int) {
    if (!pretty) return
    sb.append('\n')
    repeat(indent) { sb.append("  ") }
}

private fun writeJsonString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
}
