package org.espsketchide.buildengine

/** Splitting of expanded recipe patterns into argv, the way the Arduino IDE does it. */
object CommandLine {

    /**
     * Splits on unquoted whitespace. Double quotes group text and are removed; there is no
     * escaping (recipes rely on this: `-DARDUINO_BOARD="ESP32_DEV"` becomes `-DARDUINO_BOARD=ESP32_DEV`).
     */
    fun split(command: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasToken = false
        for (c in command) {
            when {
                c == '"' -> { inQuotes = !inQuotes; hasToken = true }
                c.isWhitespace() && !inQuotes -> {
                    if (hasToken) { args += current.toString(); current.clear(); hasToken = false }
                }
                else -> { current.append(c); hasToken = true }
            }
        }
        if (inQuotes) throw BuildException("Unbalanced quotes in command: $command")
        if (hasToken) args += current.toString()
        return args
    }
}
