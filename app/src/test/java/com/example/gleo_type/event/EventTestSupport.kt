package com.example.gleo_type.event

import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.core.EventPayload
import com.example.gleo_type.event.json.EventJsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 테스트 전용 JSON 파서.
 * 숫자/불리언을 String과 구분해서 돌려주므로 "문자열 타입" 검증에 쓸 수 있다.
 * (android org.json은 로컬 단위 테스트에서 stub이라 사용 불가)
 */
object TestJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWs()
        check(parser.pos == text.length) { "Trailing characters at ${parser.pos}" }
        return value
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $pos")
            }
        }

        fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = str()
                skipWs(); expect(':')
                check(!map.containsKey(key)) { "Duplicate key $key" }
                map[key] = value()
                skipWs()
                if (s[pos] == ',') { pos++; continue }
                expect('}')
                return map
            }
        }

        fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipWs()
            if (s[pos] == ']') { pos++; return list }
            while (true) {
                list += value()
                skipWs()
                if (s[pos] == ',') { pos++; continue }
                expect(']')
                return list
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> error("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Number {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val raw = s.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        fun literal(word: String, value: Any?): Any? {
            check(s.startsWith(word, pos)) { "Expected $word at $pos" }
            pos += word.length
            return value
        }

        fun expect(c: Char) {
            check(s[pos] == c) { "Expected '$c' at $pos but was '${s[pos]}'" }
            pos++
        }
    }
}

object Fixtures {

    @Suppress("UNCHECKED_CAST")
    fun load(name: String): List<Map<String, Any?>> {
        val stream = Fixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: $name")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return TestJson.parse(text) as List<Map<String, Any?>>
    }

    fun byLabel(fixtures: List<Map<String, Any?>>, label: String): Map<String, Any?> =
        fixtures.singleOrNull { it["label"] == label } ?: error("no fixture for $label")
}

/** 픽스처의 eventId / timestamp / driveSessionId를 그대로 재현하는 팩토리 */
fun factoryFor(fixture: Map<String, Any?>): EventFactory {
    val instant = OffsetDateTime.parse(fixture["timestamp"] as String).toInstant()
    val session = fixture["driveSessionId"] as String?
    return EventFactory(
        clock = Clock.fixed(instant, ZoneOffset.UTC),
        idGenerator = { fixture["eventId"] as String },
        sessionProvider = { session }
    )
}

fun createEvent(fixture: Map<String, Any?>, payload: EventPayload): EventDto =
    factoryFor(fixture).create(payload)

/**
 * 직렬화 결과가 픽스처와 구조·필드명·값까지 같은지,
 * 그리고 모든 스칼라가 String 또는 JSON null인지 검증한다.
 */
fun assertMatchesFixture(event: EventDto, fixture: Map<String, Any?>) {
    val serializer = EventJsonSerializer()
    val json = serializer.serialize(event, pretty = true)
    val actual = TestJson.parse(json)

    assertAllScalarsAreStringOrNull(actual, "$")
    assertEquals("label=${fixture["label"]}\n$json", fixture, actual)

    // compact 출력도 같은 구조여야 한다
    assertEquals(actual, TestJson.parse(serializer.serialize(event, pretty = false)))
}

fun assertAllScalarsAreStringOrNull(node: Any?, path: String) {
    when (node) {
        null, is String -> Unit
        is Map<*, *> -> node.forEach { (k, v) -> assertAllScalarsAreStringOrNull(v, "$path.$k") }
        is List<*> -> node.forEachIndexed { i, v -> assertAllScalarsAreStringOrNull(v, "$path[$i]") }
        else -> fail("$path must be String or null but was ${node::class.java.simpleName}: $node")
    }
}

/** 특정 키가 생략되지 않고 JSON null로 존재하는지 */
fun assertExplicitNull(json: String, vararg path: String) {
    var node: Any? = TestJson.parse(json)
    for ((index, key) in path.withIndex()) {
        val map = node as? Map<*, *> ?: error("${path.take(index)} is not an object")
        assertTrue("missing key ${path.joinToString(".")}", map.containsKey(key))
        node = map[key]
    }
    assertEquals(null, node)
}
