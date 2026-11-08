package com.example.gleo_type.event.json

/**
 * 이벤트 전용 최소 JSON 작성기.
 *
 * 허용 타입: null, String, Map<String, *>, List<*>.
 * 숫자·불리언을 넣으면 예외를 던진다 → "모든 스칼라는 문자열" 규칙을 강제한다.
 *
 * android org.json은 로컬 단위 테스트에서 stub이라 동작하지 않으므로
 * 새 라이브러리를 추가하지 않고 직접 작성한다.
 */
object JsonWriter {

    fun write(value: Any?, pretty: Boolean = false): String {
        val out = StringBuilder()
        writeValue(out, value, pretty, 0)
        return out.toString()
    }

    private fun writeValue(out: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Map<*, *> -> writeObject(out, value, pretty, depth)
            is List<*> -> writeArray(out, value, pretty, depth)
            else -> throw IllegalArgumentException(
                "Event JSON only allows String/null scalars: ${value::class.java.name}"
            )
        }
    }

    private fun writeObject(out: StringBuilder, map: Map<*, *>, pretty: Boolean, depth: Int) {
        if (map.isEmpty()) {
            out.append("{}")
            return
        }
        out.append('{')
        var first = true
        for ((key, value) in map) {
            require(key is String) { "JSON object key must be String: $key" }
            if (!first) out.append(',')
            first = false
            newline(out, pretty, depth + 1)
            writeString(out, key)
            out.append(if (pretty) ": " else ":")
            writeValue(out, value, pretty, depth + 1)
        }
        newline(out, pretty, depth)
        out.append('}')
    }

    private fun writeArray(out: StringBuilder, list: List<*>, pretty: Boolean, depth: Int) {
        if (list.isEmpty()) {
            out.append("[]")
            return
        }
        out.append('[')
        list.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            newline(out, pretty, depth + 1)
            writeValue(out, value, pretty, depth + 1)
        }
        newline(out, pretty, depth)
        out.append(']')
    }

    private fun newline(out: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append(String.format("\\u%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }
}
