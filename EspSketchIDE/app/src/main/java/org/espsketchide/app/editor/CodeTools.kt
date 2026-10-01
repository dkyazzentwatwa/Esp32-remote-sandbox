package org.espsketchide.app.editor

/** Pure text transforms behind the editor's Edit menu (comment toggle and Auto Format). */
object CodeTools {

    private const val INDENT = "  "

    /**
     * Comments [lines] with `// ` at their common indentation, or uncomments them if every
     * non-blank line already starts with `//`. Blank lines are left alone.
     */
    fun toggleComment(lines: List<String>): List<String> {
        val code = lines.filter { it.isNotBlank() }
        if (code.isEmpty()) return lines
        val uncomment = code.all { it.trimStart().startsWith("//") }
        if (uncomment) {
            return lines.map { line ->
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                val rest = line.substring(indent.length)
                if (!rest.startsWith("//")) line else indent + rest.removePrefix("//").removePrefix(" ")
            }
        }
        val common = code.minOf { line -> line.takeWhile { it == ' ' || it == '\t' }.length }
        return lines.map { line -> if (line.isBlank()) line else line.substring(0, common) + "// " + line.substring(common) }
    }

    /**
     * Re-indents [code] by brace depth with two spaces, like the Arduino IDE's Auto Format.
     * Braces in strings, character literals and comments don't count; preprocessor lines and
     * the inside of block comments keep their own indentation; trailing spaces are removed.
     */
    fun format(code: String): String {
        var depth = 0
        var inBlockComment = false
        return code.lines().joinToString("\n") { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val wasInComment = inBlockComment
            val scan = scanBraces(trimmed, inBlockComment)
            inBlockComment = scan.inBlockComment
            val out = when {
                trimmed.isEmpty() -> ""
                wasInComment -> line // inside /* … */: keep as written
                trimmed.startsWith("#") -> trimmed
                else -> {
                    // A line that starts by closing blocks is indented at the outer level.
                    val lineDepth = (depth - scan.leadingCloses).coerceAtLeast(0)
                    INDENT.repeat(lineDepth) + trimmed
                }
            }
            depth = (depth + scan.opens - scan.closes).coerceAtLeast(0)
            out
        }
    }

    private class BraceScan(val opens: Int, val closes: Int, val leadingCloses: Int, val inBlockComment: Boolean)

    private fun scanBraces(line: String, startInBlockComment: Boolean): BraceScan {
        var opens = 0
        var closes = 0
        var leadingCloses = 0
        var sawCode = false
        var inComment = startInBlockComment
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val next = line.getOrNull(i + 1)
            when {
                inComment -> if (c == '*' && next == '/') { inComment = false; i++ }
                quote != null -> when (c) {
                    '\\' -> i++
                    quote -> quote = null
                }
                c == '/' && next == '/' -> break
                c == '/' && next == '*' -> { inComment = true; i++ }
                c == '"' || c == '\'' -> { quote = c; sawCode = true }
                c == '{' -> { opens++; sawCode = true }
                c == '}' -> { closes++; if (!sawCode) leadingCloses++ }
                !c.isWhitespace() -> sawCode = true
            }
            i++
        }
        return BraceScan(opens, closes, leadingCloses, inComment)
    }
}
