package org.espsketchide.buildengine

/**
 * Finds top-level function definitions in C++ source so the sketch preprocessor can add
 * prototypes, the way the Arduino IDE lets sketches call functions defined further down.
 *
 * Meant to run on `gcc -E` output (so `#if`-disabled code and macros are already resolved) but
 * works on plain source too. Follows GCC line markers (`# 12 "file"`) and `#line`, and skips
 * comments, string/char/raw-string literals and all other preprocessor lines.
 */
object PrototypeScanner {

    data class Function(
        val name: String,
        val file: String,
        val line: Int,
        /** Declaration text ending in ';', default arguments removed. */
        val prototype: String,
        /** False for templates, class members, operators and functions already declared. */
        val needsPrototype: Boolean,
    )

    private enum class Kind { WORD, PUNCT, LITERAL }

    private data class Token(val text: String, val kind: Kind, val file: String, val line: Int)

    private val NOT_FUNCTION_NAMES = setOf(
        "if", "for", "while", "switch", "catch", "return", "sizeof", "alignof", "decltype", "typeof",
        "__attribute__", "defined", "static_assert", "__typeof__", "__asm__", "asm",
    )
    private val BLOCK_STARTERS = setOf("struct", "class", "union", "enum", "namespace")
    private val TRAILING_QUALIFIERS = setOf("const", "volatile", "noexcept", "override", "final", "&", "&&")

    /**
     * [countsAsDeclaration] limits which files' declarations suppress a prototype; Arduino only
     * honours declarations in the sketch itself (Arduino.h declares `setup` and `loop` too).
     */
    fun scan(source: String, defaultFile: String, countsAsDeclaration: (file: String) -> Boolean = { true }): List<Function> {
        val tokens = tokenize(source, defaultFile)
        val found = mutableListOf<Function>()
        val declared = mutableSetOf<String>()
        val decl = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when (t.text) {
                ";" -> {
                    if (decl.isNotEmpty() && countsAsDeclaration(decl.first().file)) declaredName(decl)?.let(declared::add)
                    decl.clear()
                }
                "{" -> {
                    val fn = asFunction(decl)
                    if (fn != null) found += fn
                    i = skipBlock(tokens, i)
                    // An initialiser (`int a[] = { ... }`) continues to its ';'; any other block
                    // (function, operator, struct, namespace ...) ends the declaration.
                    if (decl.any { it.text == "=" }) decl += Token("{...}", Kind.PUNCT, t.file, t.line)
                    else decl.clear()
                }
                "}" -> decl.clear() // stray (unbalanced input); resynchronise
                else -> decl += t
            }
            i++
        }
        return found.map { if (it.needsPrototype && it.name in declared) it.copy(needsPrototype = false) else it }
    }

    /** Index of the `}` matching the `{` at [open]. */
    private fun skipBlock(tokens: List<Token>, open: Int): Int {
        var depth = 0
        var i = open
        while (i < tokens.size) {
            when (tokens[i].text) {
                "{" -> depth++
                "}" -> if (--depth == 0) return i
            }
            i++
        }
        return tokens.size - 1
    }

    /** `struct X {`, `namespace n {`, `extern "C" {` ... (no parameter list, so not a function). */
    private fun isBlockStarter(decl: List<Token>): Boolean {
        if (decl.firstOrNull()?.text == "extern" && decl.getOrNull(1)?.kind == Kind.LITERAL) return true
        return decl.none { it.text == "(" } && decl.any { it.kind == Kind.WORD && it.text in BLOCK_STARTERS }
    }

    /** Position of the `(` of the parameter list, i.e. the paren group right after the name. */
    private data class Signature(val nameIndex: Int, val openParen: Int, val closeParen: Int)

    private fun signature(decl: List<Token>): Signature? {
        if (decl.isEmpty() || isBlockStarter(decl)) return null
        // Walk back over trailing qualifiers / attributes / trailing return type to the params.
        var end = decl.size - 1
        val arrow = decl.indexOfFirst { it.text == "->" }
        if (arrow > 0) end = arrow - 1
        while (end >= 0) {
            val t = decl[end]
            when {
                t.text in TRAILING_QUALIFIERS -> end--
                t.text == ")" && isAttributeGroupEnd(decl, end) -> end = matchingOpen(decl, end)!! - 2
                t.text == ")" && isThrowGroupEnd(decl, end) -> end = matchingOpen(decl, end)!! - 2
                else -> break
            }
        }
        if (end < 0 || decl[end].text != ")") return null
        val open = matchingOpen(decl, end) ?: return null
        val nameIndex = open - 1
        val name = decl.getOrNull(nameIndex) ?: return null
        if (name.kind != Kind.WORD || name.text in NOT_FUNCTION_NAMES) return null
        // `x = f(1)` or `auto f = [](...)` are initialisers, not signatures.
        if (decl.subList(0, nameIndex).any { it.text == "=" }) return null
        if (nameIndex == 0) return null // no return type: a call or a macro invocation
        return Signature(nameIndex, open, end)
    }

    private fun isAttributeGroupEnd(decl: List<Token>, close: Int): Boolean {
        val open = matchingOpen(decl, close) ?: return false
        return decl.getOrNull(open - 1)?.text in setOf("__attribute__", "__declspec", "alignas")
    }

    private fun isThrowGroupEnd(decl: List<Token>, close: Int): Boolean {
        val open = matchingOpen(decl, close) ?: return false
        return decl.getOrNull(open - 1)?.text in setOf("throw", "noexcept")
    }

    private fun matchingOpen(decl: List<Token>, close: Int): Int? {
        var depth = 0
        for (i in close downTo 0) {
            when (decl[i].text) {
                ")" -> depth++
                "(" -> if (--depth == 0) return i
            }
        }
        return null
    }

    private fun declaredName(decl: List<Token>): String? {
        val sig = signature(decl) ?: return null
        return decl[sig.nameIndex].text
    }

    private fun asFunction(decl: List<Token>): Function? {
        val sig = signature(decl) ?: return null
        val name = decl[sig.nameIndex]
        val words = decl.map { it.text }
        val skip = "template" in words || "operator" in words || decl.getOrNull(sig.nameIndex - 1)?.text == "::" ||
            decl.getOrNull(sig.nameIndex - 1)?.text == "~"
        val start = decl.first()
        val text = render(withoutDefaultArguments(decl, sig)) + ";"
        return Function(name.text, start.file, start.line, text, needsPrototype = !skip)
    }

    /** Drops `= value` from each top-level parameter. */
    private fun withoutDefaultArguments(decl: List<Token>, sig: Signature): List<Token> {
        val out = mutableListOf<Token>()
        var depth = 0
        var skipping = false
        for ((i, t) in decl.withIndex()) {
            if (i <= sig.openParen || i >= sig.closeParen) { out += t; continue }
            when (t.text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> depth--
            }
            if (depth == 0 && t.text == ",") skipping = false
            if (depth == 0 && t.text == "=") skipping = true
            if (!skipping) out += t
        }
        return out
    }

    private fun render(tokens: List<Token>): String {
        val sb = StringBuilder()
        var prev: Token? = null
        for (t in tokens) {
            if (prev != null && needsSpace(prev, t)) sb.append(' ')
            sb.append(t.text)
            prev = t
        }
        return sb.toString()
    }

    private fun needsSpace(prev: Token, t: Token): Boolean {
        if (t.text in setOf(",", ")", "(", ";", "::", "[", "]")) return false
        if (prev.text in setOf("(", "::", "~", "[", "!")) return false
        if (t.text in setOf("*", "&", "&&")) return prev.kind == Kind.WORD || prev.text == ")"
        if (prev.text in setOf("*", "&", "&&")) return false
        if (t.text == "<" || prev.text == "<" || t.text == ">") return false
        return true
    }

    // --- tokenizer ---

    private fun tokenize(src: String, defaultFile: String): List<Token> {
        val out = mutableListOf<Token>()
        var file = defaultFile
        var line = 1
        var i = 0
        var atLineStart = true
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++; atLineStart = true }
                c == ' ' || c == '\t' || c == '\r' || c == '\u000c' -> i++
                c == '#' && atLineStart -> {
                    // Preprocessor line (with backslash continuations).
                    val sb = StringBuilder()
                    var continued = 0
                    while (i < n && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < n && src[i + 1] == '\n') { i += 2; continued++; continue }
                        sb.append(src[i]); i++
                    }
                    val marker = LINE_MARKER.matchEntire(sb.toString().trim())
                    if (marker != null) {
                        file = marker.groupValues[2]
                        line = marker.groupValues[1].toInt() - 1 // the '\n' below advances to it
                    } else {
                        line += continued
                    }
                }
                c == '/' && i + 1 < n && src[i + 1] == '/' -> { while (i < n && src[i] != '\n') i++ }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i < n && !(src[i] == '*' && i + 1 < n && src[i + 1] == '/')) { if (src[i] == '\n') line++; i++ }
                    i += 2
                }
                c == 'R' && i + 1 < n && src[i + 1] == '"' -> {
                    val startLine = line
                    val open = src.indexOf('(', i + 2)
                    val delim = src.substring(i + 2, open)
                    val close = src.indexOf(")$delim\"", open)
                    val end = if (close < 0) n else close + delim.length + 2
                    line += src.substring(i, end).count { it == '\n' }
                    out += Token(src.substring(i, end), Kind.LITERAL, file, startLine)
                    i = end; atLineStart = false
                }
                c == '"' || c == '\'' -> {
                    val start = i
                    i++
                    while (i < n && src[i] != c) { if (src[i] == '\\') i++; if (i < n && src[i] == '\n') line++; i++ }
                    i++
                    out += Token(src.substring(start, minOf(i, n)), Kind.LITERAL, file, line)
                    atLineStart = false
                }
                c.isLetterOrDigit() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$' || (src[i] == '.' && src[start].isDigit()))) i++
                    // String literal prefixes (u8"...", L'x') belong to the literal.
                    if (i < n && (src[i] == '"' || src[i] == '\'') && src.substring(start, i) in setOf("u8", "u", "U", "L")) {
                        continue
                    }
                    out += Token(src.substring(start, i), Kind.WORD, file, line)
                    atLineStart = false
                }
                else -> {
                    val three = if (i + 3 <= n) src.substring(i, i + 3) else ""
                    val two = if (i + 2 <= n) src.substring(i, i + 2) else ""
                    val text = when {
                        three == "..." -> three
                        two in setOf("::", "->", "&&", "||", "==", "!=", "<=", ">=", "++", "--", "<<", ">>") -> two
                        else -> c.toString()
                    }
                    out += Token(text, Kind.PUNCT, file, line)
                    i += text.length
                    atLineStart = false
                }
            }
        }
        return out
    }

    /** GCC line marker `# 12 "file" 1 3` or `#line 12 "file"`. */
    private val LINE_MARKER = Regex("""#\s*(?:line\s+)?(\d+)\s+"((?:[^"\\]|\\.)*)".*""")
}
