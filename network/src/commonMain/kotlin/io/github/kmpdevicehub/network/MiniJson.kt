package io.github.kmpdevicehub.network

/** Just enough JSON for the inspector protocol, so the library adds no serialization dependency to apps. */
internal object MiniJson {
    fun obj(fields: List<Pair<String, Any?>>): String = buildString { writeObject(this, fields) }

    private fun writeObject(out: StringBuilder, fields: List<Pair<String, Any?>>) {
        out.append('{')
        fields.filter { it.second != null }.forEachIndexed { i, (k, v) ->
            if (i > 0) out.append(',')
            quote(out, k)
            out.append(':')
            write(out, v)
        }
        out.append('}')
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Number, is Boolean -> out.append(value.toString())
            is Pair<*, *> -> { out.append('['); write(out, value.first); out.append(','); write(out, value.second); out.append(']') }
            is List<*> -> { out.append('['); value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }; out.append(']') }
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
        out.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        return p.value().also { p.ws(); require(p.pos == text.length) { "trailing content" } }
    }

    private class Parser(val s: String) {
        var pos = 0
        fun ws() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun value(): Any? {
            ws()
            return when (val c = s.getOrNull(pos) ?: error("end of input")) {
                '{' -> {
                    pos++
                    val map = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[pos] == '}') { pos++; return map }
                    while (true) {
                        ws(); val k = string(); ws(); expect(':'); map[k] = value(); ws()
                        if (s[pos++] == '}') return map
                    }
                    @Suppress("UNREACHABLE_CODE") map
                }
                '[' -> {
                    pos++
                    val list = ArrayList<Any?>()
                    ws()
                    if (s[pos] == ']') { pos++; return list }
                    while (true) {
                        list += value(); ws()
                        if (s[pos++] == ']') return list
                    }
                    @Suppress("UNREACHABLE_CODE") list
                }
                '"' -> string()
                't' -> { pos += 4; true }
                'f' -> { pos += 5; false }
                'n' -> { pos += 4; null }
                else -> {
                    require(c == '-' || c.isDigit()) { "unexpected '$c'" }
                    val start = pos
                    while (pos < s.length && (s[pos].isDigit() || s[pos] in "-+.eE")) pos++
                    s.substring(start, pos).let { it.toLongOrNull() ?: it.toDouble() }
                }
            }
        }
        fun expect(c: Char) { require(s[pos] == c) { "expected '$c'" }; pos++ }
        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[pos++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
