package org.espsketchide.buildengine

import java.io.File

/**
 * Arduino-style `.txt` properties (platform.txt, boards.txt): `key=value` lines, `#` comments,
 * and `{key}` placeholders expanded on demand. Keys ending in `.linux` replace their base key
 * (the engine always runs on Linux/Android); `.windows`/`.macosx` keys are kept as-is and ignored.
 */
class Properties private constructor(private val map: LinkedHashMap<String, String>) {

    val keys: Set<String> get() = map.keys

    operator fun get(key: String): String? = map[key]

    fun getValue(key: String): String = map[key] ?: throw BuildException("Missing property '$key'")

    operator fun plus(other: Properties): Properties =
        Properties(LinkedHashMap(map).apply { putAll(other.map) })

    fun with(vararg pairs: Pair<String, String>): Properties =
        Properties(LinkedHashMap(map).apply { pairs.forEach { (k, v) -> put(k, v) } })

    /** Keys under `prefix.` with the prefix removed. */
    fun subtree(prefix: String): Properties {
        val p = "$prefix."
        return Properties(LinkedHashMap(map.filterKeys { it.startsWith(p) }.mapKeys { it.key.removePrefix(p) }))
    }

    fun toMap(): Map<String, String> = map.toMap()

    /**
     * Replaces `{key}` with its value, innermost placeholders first, until nothing changes.
     * Placeholders without a value (e.g. `{source_file}`) are left for the caller to fill.
     */
    fun expand(value: String): String {
        var current = value
        repeat(MAX_PASSES) {
            val next = PLACEHOLDER.replace(current) { m -> map[m.groupValues[1]] ?: m.value }
            if (next == current) return current
            current = next
        }
        return current
    }

    /** The value of [key], expanded. */
    fun expanded(key: String): String = expand(getValue(key))

    companion object {
        private val PLACEHOLDER = Regex("""\{([^{}]+)\}""")
        private const val MAX_PASSES = 20
        private const val OS = "linux"

        fun of(vararg pairs: Pair<String, String>) = Properties(LinkedHashMap(pairs.toMap()))

        fun load(file: File): Properties = parse(file.readText())

        fun parse(text: String): Properties {
            val map = LinkedHashMap<String, String>()
            val osOverrides = LinkedHashMap<String, String>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq < 0) return@forEach
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.endsWith(".$OS")) osOverrides[key.removeSuffix(".$OS")] = value else map[key] = value
            }
            map.putAll(osOverrides)
            return Properties(map)
        }
    }
}

class BuildException(message: String, cause: Throwable? = null) : Exception(message, cause)
