package org.espsketchide.app.editor

import org.espsketchide.app.reference.ArduinoReference
import org.espsketchide.app.settings.Board

/** A completion that inserts code with tab stops, e.g. `digitalWrite(${1:pin}, ${2:value})`. */
data class ArduinoSnippet(val label: String, val body: String, val detail: String)

/** A snippet chosen for the current prefix, and the text to insert in place of the prefix. */
data class SnippetMatch(val snippet: ArduinoSnippet, val insert: String)

/**
 * Parameter-filling completions built from the offline reference: each `name(a, b)` syntax
 * becomes a snippet, plus block templates for the control keywords and setup()/loop().
 */
object ArduinoSnippets {

    private val CALL = Regex("""^([A-Za-z_][\w.]*)\((.*)\)$""")

    private val BLOCKS = mapOf(
        "if" to "if (\${1:condition}) {\n  \$0\n}",
        "for" to "for (int \${1:i} = 0; \${1:i} < \${2:10}; \${1:i}++) {\n  \$0\n}",
        "while" to "while (\${1:condition}) {\n  \$0\n}",
        "setup" to "void setup() {\n  \$0\n}",
        "loop" to "void loop() {\n  \$0\n}",
    )

    /** Snippet body for a reference syntax line, or null if it isn't a function call. */
    fun fromSyntax(syntax: String): String? {
        val first = syntax.split("  or  ").first().trim()
        val call = CALL.matchEntire(first) ?: return null
        val name = call.groupValues[1]
        val args = splitTopLevel(call.groupValues[2]).map { it.trim().removeSuffix("…").trim() }.filter { it.isNotEmpty() }
        return args.withIndex().joinToString(", ", prefix = "$name(", postfix = ")") { (i, arg) -> "\${${i + 1}:$arg}" }
    }

    fun from(reference: ArduinoReference, board: Board): List<ArduinoSnippet> =
        reference.entries.filter { it.appliesTo(board) }.mapNotNull { entry ->
            val body = BLOCKS[entry.name] ?: fromSyntax(entry.syntax) ?: return@mapNotNull null
            ArduinoSnippet(entry.name, body, entry.summary)
        }

    /**
     * Snippets for what is being typed. After `Serial.` ([objectName] "Serial") only Serial's
     * members match and only the member part is inserted; otherwise names match by prefix,
     * ignoring case, and typing an object name offers its members in full.
     */
    fun match(snippets: List<ArduinoSnippet>, objectName: String?, prefix: String): List<SnippetMatch> {
        if (prefix.isEmpty()) return emptyList()
        return snippets.mapNotNull { snippet ->
            val dot = snippet.label.indexOf('.')
            if (objectName != null) {
                if (dot < 0 || snippet.label.substring(0, dot) != objectName) return@mapNotNull null
                val member = snippet.label.substring(dot + 1)
                if (!member.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
                SnippetMatch(snippet, snippet.body.substring(dot + 1))
            } else {
                if (!snippet.label.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
                SnippetMatch(snippet, snippet.body)
            }
        }
    }

    /** Splits on commas that aren't inside parentheses. */
    private fun splitTopLevel(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        args.forEachIndexed { i, c ->
            when (c) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { parts += args.substring(start, i); start = i + 1 }
            }
        }
        parts += args.substring(start)
        return parts
    }
}
