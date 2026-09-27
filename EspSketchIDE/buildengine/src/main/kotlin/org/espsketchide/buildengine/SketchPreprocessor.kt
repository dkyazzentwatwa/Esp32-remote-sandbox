package org.espsketchide.buildengine

import java.io.File

/**
 * Turns a sketch's `.ino` files into one C++ file, the Arduino way:
 *  1. merge: `#include <Arduino.h>`, then each `.ino` (primary first, others alphabetically)
 *     preceded by `#line 1 "<file>"`;
 *  2. run the C preprocessor over the merged text (so `#if`-disabled code and macros resolve);
 *  3. find functions defined in the `.ino` files and insert prototypes for them just before the
 *     first function definition, each with a `#line` pointing at its definition.
 */
object SketchPreprocessor {

    /** The `.ino` files of [sketchDir] in merge order. */
    fun inoFiles(sketchDir: File): List<File> {
        val primary = File(sketchDir, "${sketchDir.name}.ino")
        if (!primary.isFile) throw BuildException("Sketch ${sketchDir.name} has no ${primary.name}")
        val others = sketchDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "ino" && it.name != primary.name }
            .sortedBy { it.name }
        return listOf(primary) + others
    }

    fun merge(files: List<File>): String = buildString {
        append("#include <Arduino.h>\n")
        for (f in files) {
            append("#line 1 ").append(quote(f.path)).append('\n')
            append(f.readText())
            append('\n')
        }
    }

    /**
     * Inserts prototypes into [merged]. [preprocessed] is the C preprocessor's output for the
     * merged text; functions are taken from it only where they come from one of [inoPaths].
     */
    fun addPrototypes(merged: String, preprocessed: String, inoPaths: Set<String>): String {
        val fromSketch = { file: String -> unquote(file) in inoPaths }
        val functions = PrototypeScanner.scan(preprocessed, defaultFile = "", countsAsDeclaration = fromSketch)
            .filter { fromSketch(it.file) }
        val first = functions.firstOrNull() ?: return merged
        val prototypes = functions.filter { it.needsPrototype }
        if (prototypes.isEmpty()) return merged

        val lines = merged.split('\n')
        val insertAt = mergedLineIndex(lines, unquote(first.file), first.line)
            ?: throw BuildException("Couldn't find ${first.file}:${first.line} in the merged sketch")
        val block = buildList {
            for (p in prototypes) {
                add("#line ${p.line} ${quote(unquote(p.file))}")
                add(p.prototype)
            }
            add("#line ${first.line} ${quote(unquote(first.file))}")
        }
        return (lines.subList(0, insertAt) + block + lines.subList(insertAt, lines.size)).joinToString("\n")
    }

    /** Index in [lines] of source line [line] of [file], following the `#line` markers. */
    private fun mergedLineIndex(lines: List<String>, file: String, line: Int): Int? {
        var currentFile: String? = null
        var currentLine = 0
        for ((i, text) in lines.withIndex()) {
            val marker = LINE_DIRECTIVE.matchEntire(text)
            if (marker != null) {
                currentFile = unescape(marker.groupValues[2])
                currentLine = marker.groupValues[1].toInt()
                continue
            }
            if (currentFile == file && currentLine == line) return i
            if (currentFile != null) currentLine++
        }
        return null
    }

    private val LINE_DIRECTIVE = Regex("""#line (\d+) "((?:[^"\\]|\\.)*)"""")

    private fun quote(path: String) = "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")

    /** Line markers from the scanner carry the raw (escaped) file text. */
    private fun unquote(file: String) = unescape(file)
}
