package org.espsketchide.app.upload

/**
 * Turns serial output into plot series, following the Arduino IDE's Serial Plotter format:
 * numbers separated by spaces, commas or tabs (series named "1", "2", … by position), or
 * `label:value` pairs. Lines without numbers are ignored.
 */
object SerialPlot {

    private val SEPARATORS = Regex("""[\s,]+""")
    private val LABELLED = Regex("""([A-Za-z_][\w ]*?)\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)""")

    fun parseLine(line: String): List<Pair<String?, Float>> {
        val text = line.trim()
        if (text.isEmpty()) return emptyList()
        if (':' in text) {
            return LABELLED.findAll(text).mapNotNull { m -> m.groupValues[2].toFloatOrNull()?.let { m.groupValues[1].trim() to it } }.toList()
        }
        val tokens = text.split(SEPARATORS).filter { it.isNotEmpty() }
        val values = tokens.map { it.toFloatOrNull() }
        // A line counts only if every token is a number, so text that mentions a number is skipped.
        if (values.any { it == null }) return emptyList()
        return values.map { null to it!! }
    }

    /** The last [maxPoints] values of each series in [text]; an unfinished last line is skipped. */
    fun series(text: String, maxPoints: Int): LinkedHashMap<String, FloatArray> {
        val complete = text.substringBeforeLast('\n', "")
        val lists = LinkedHashMap<String, ArrayDeque<Float>>()
        for (line in complete.lineSequence()) {
            parseLine(line).forEachIndexed { index, (label, value) ->
                val points = lists.getOrPut(label ?: (index + 1).toString()) { ArrayDeque() }
                points.addLast(value)
                if (points.size > maxPoints) points.removeFirst()
            }
        }
        return lists.mapValuesTo(LinkedHashMap()) { it.value.toFloatArray() }
    }
}
