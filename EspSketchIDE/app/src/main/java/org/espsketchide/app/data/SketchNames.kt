package org.espsketchide.app.data

/** Pure helpers for naming sketches. Sketch names must match `^[A-Za-z][A-Za-z0-9_]*$`. */
object SketchNames {

    /** [base] if it is free, otherwise the first of `base_2`, `base_3`, … that is. */
    fun nextFreeName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var n = 2
        while ("${base}_$n" in existing) n++
        return "${base}_$n"
    }

    /** A valid sketch name from a display name: drops other characters, prefixes `sketch_` if it starts with a non-letter. */
    fun toSketchName(displayName: String): String {
        val cleaned = displayName.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }
        if (cleaned.isEmpty()) return "sketch"
        return if (cleaned.first().isLetter()) cleaned else "sketch_$cleaned"
    }

    /** Renames the (first) `.ino` in [files] to `<newName>.ino`, keeping order and all other files. */
    fun renamePrimary(files: Map<String, String>, newName: String): Map<String, String> {
        val primary = files.keys.firstOrNull { it.endsWith(".ino") } ?: return files
        val renamed = LinkedHashMap<String, String>()
        for ((name, content) in files) {
            renamed[if (name == primary) "$newName.ino" else name] = content
        }
        return renamed
    }
}
