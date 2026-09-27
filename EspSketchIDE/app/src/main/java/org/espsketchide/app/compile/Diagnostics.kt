package org.espsketchide.app.compile

import java.io.File

/** A compiler message. [file] is relative to the sketch (e.g. `Blink.ino`) when it's in the sketch. */
data class Diagnostic(
    val severity: Severity,
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val inSketch: Boolean,
) {
    enum class Severity { ERROR, WARNING, NOTE }
}

/** Reads gcc's `file:line:col: error: message` lines. */
object Diagnostics {

    private val LINE = Regex("""^(.+?):(\d+):(?:(\d+):)? (fatal error|error|warning|note): (.*)$""")

    fun parse(output: String, sketchDir: File): List<Diagnostic> {
        val sketchPath = sketchDir.path.trimEnd('/') + "/"
        return output.lineSequence().mapNotNull { line ->
            val m = LINE.matchEntire(line.trimEnd()) ?: return@mapNotNull null
            val (path, row, col, kind, message) = m.destructured
            val inSketch = path.startsWith(sketchPath)
            Diagnostic(
                severity = when (kind) {
                    "warning" -> Diagnostic.Severity.WARNING
                    "note" -> Diagnostic.Severity.NOTE
                    else -> Diagnostic.Severity.ERROR
                },
                file = if (inSketch) path.removePrefix(sketchPath) else path,
                line = row.toInt(),
                column = col.toIntOrNull() ?: 1,
                message = message,
                inSketch = inSketch,
            )
        }.distinct().toList()
    }
}
