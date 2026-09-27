package com.mnnkit.core.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class JsonParserTest {

    @Test
    fun `parses object with mixed value types`() {
        val j = JsonParser.parse(
            """{"a":1,"b":-2.5,"c":true,"d":false,"e":null,"f":"hi"}"""
        )
        assertEquals(1L, j.long("a"))
        assertEquals(-2.5, j.path("b")?.asDouble)
        assertEquals(true, j.bool("c"))
        assertEquals(false, j.bool("d"))
        assertTrue(j.path("e") is Json.Null)
        assertEquals("hi", j.str("f"))
    }

    @Test
    fun `parses nested arrays and objects`() {
        val j = JsonParser.parse(
            """{"Data":{"Files":[{"Path":"a.mnn","Size":10},{"Path":"b.txt","Size":20}]}}"""
        )
        val files = j.arr("Data", "Files")
        assertNotNull(files)
        assertEquals(2, files.size)
        assertEquals("a.mnn", files[0].str("Path"))
        assertEquals(20L, files[1].long("Size"))
    }

    @Test
    fun `handles string escapes including unicode and surrogate pairs`() {
        val j = JsonParser.parse("""{"s":"line\nbreak\ttab \"q\" \\ / \u4e2d\u6587 \ud83d\ude00"}""")
        val s = j.str("s")
        assertEquals("line\nbreak\ttab \"q\" \\ / 中文 😀", s)
    }

    @Test
    fun `handles empty containers and whitespace`() {
        assertEquals(0, JsonParser.parse("{}").asObject?.size)
        assertEquals(0, JsonParser.parse("[]").asArray?.size)
        assertEquals(1L, JsonParser.parse("  \n\t { \"x\" : 1 }  \r\n ").long("x"))
        assertEquals(0, JsonParser.parse("{\"a\":[],\"b\":{}}").path("a")?.asArray?.size)
    }

    @Test
    fun `parses exotic but legal numbers`() {
        val j = JsonParser.parse("""{"z":0,"neg":-0.5,"exp":1e3,"expNeg":2.5E-2,"big":9007199254740993}""")
        assertEquals(0L, j.long("z"))
        assertEquals(-0.5, j.path("neg")?.asDouble)
        assertEquals(1000.0, j.path("exp")?.asDouble)
        assertEquals(0.025, j.path("expNeg")?.asDouble)
        assertNotNull(j.long("big"))
    }

    @Test
    fun `duplicate keys keep last value`() {
        assertEquals(2L, JsonParser.parse("""{"k":1,"k":2}""").long("k"))
    }

    @Test
    fun `path navigation returns null instead of throwing`() {
        val j = JsonParser.parse("""{"a":{"b":1}}""")
        assertNull(j.path("a", "missing"))
        assertNull(j.path("nope", "b"))
        assertNull(j.path("a", 0))
        assertNull(j.str("a", "b", "c"))
    }

    @Test
    fun `rejects malformed input with offset`() {
        assertFailsWith<JsonParseException> { JsonParser.parse("{") }
        assertFailsWith<JsonParseException> { JsonParser.parse("""{"a":}""") }
        assertFailsWith<JsonParseException> { JsonParser.parse("[1,2") }
        assertFailsWith<JsonParseException> { JsonParser.parse("""{"a" 1}""") }
        assertFailsWith<JsonParseException> { JsonParser.parse("tru") }
        assertFailsWith<JsonParseException> { JsonParser.parse("") }
        assertFailsWith<JsonParseException> { JsonParser.parse("""{"a":"\q"}""") }
        assertFailsWith<JsonParseException> { JsonParser.parse("1 2") }
    }

    @Test
    fun `parseOrNull swallows errors for tolerant reads`() {
        assertNull(JsonParser.parseOrNull("not json"))
        assertNull(JsonParser.parseOrNull(null))
        assertNull(JsonParser.parseOrNull("   "))
        assertNotNull(JsonParser.parseOrNull("""{"ok":1}"""))
    }

    @Test
    fun `deep nesting is bounded`() {
        val deep = "[".repeat(500) + "]".repeat(500)
        assertFailsWith<JsonParseException> { JsonParser.parse(deep) }
    }

    @Test
    fun `stringify round trips`() {
        val original = """{"name":"模型","size":451,"tags":["a","b"],"flag":true,"none":null,"ratio":0.5}"""
        val parsed = JsonParser.parse(original)
        val again = JsonParser.parse(parsed.stringify())
        assertEquals("模型", again.str("name"))
        assertEquals(451L, again.long("size"))
        assertEquals(listOf("a", "b"), again.arr("tags")?.map { it.asString })
        assertEquals(true, again.bool("flag"))
        assertEquals(0.5, again.path("ratio")?.asDouble)
    }

    @Test
    fun `stringify escapes control characters`() {
        val j = Json.Obj(mapOf("s" to Json.Str("a\nb\tc\u0001d\"e\\f")))
        val text = j.stringify()
        assertTrue(text.contains("\\n"))
        assertTrue(text.contains("\\t"))
        assertTrue(text.contains("\\u0001"))
        assertTrue(text.contains("\\\""))
        assertTrue(text.contains("\\\\"))
        assertEquals("a\nb\tc\u0001d\"e\\f", JsonParser.parse(text).str("s"))
    }

    @Test
    fun `keeps integer values integral when serializing`() {
        assertTrue(JsonParser.parse("""{"n":9007199254740992}""").stringify().contains("9007199254740992"))
        assertTrue(JsonParser.parse("""{"n":1.0}""").stringify().let { it.contains("1") })
    }
}
