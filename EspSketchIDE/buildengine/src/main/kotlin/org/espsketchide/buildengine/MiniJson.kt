package org.espsketchide.buildengine

/**
 * A small JSON reader (objects, arrays, strings, numbers, booleans, null) so the engine has no
 * dependencies; used for pack manifests. Objects become Map<String, Any?>, arrays List<Any?>,
 * numbers Long or Double.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipWhitespace()
        if (p.i != text.length) p.fail("trailing data")
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw BuildException("Invalid JSON at $i: $msg")

        fun skipWhitespace() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            skipWhitespace()
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWhitespace()
                val k = str()
                skipWhitespace()
                if (s[i++] != ':') fail("expected ':'")
                m[k] = value()
                skipWhitespace()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> fail("expected ',' or '}'") }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                skipWhitespace()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> fail("expected ',' or ']'") }
            }
        }

        private fun str(): String {
            if (s[i] != '"') fail("expected string")
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> fail("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            if (t.isEmpty()) fail("unexpected '${s[start]}'")
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad number $t")
        }
    }
}
