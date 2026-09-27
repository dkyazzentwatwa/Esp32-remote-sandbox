package org.espsketchide.buildengine

/** Splitting of expanded recipe patterns into argv, the way the Arduino IDE does it. */
object CommandLine {

    /**
     * Splits on whitespace. A token that *starts* with `"` or `'` runs to the matching quote and
     * loses the quotes (`"{source_file}"` -> the path); quotes elsewhere stay part of the token
     * (`-DARDUINO_BOARD="ESP32_DEV"` keeps them, so the macro is a string literal).
     */
    fun split(command: String): List<String> {
        val args = mutableListOf<String>()
        var i = 0
        val n = command.length
        while (i < n) {
            val c = command[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '\'' -> {
                    val close = command.indexOf(c, i + 1)
                    if (close < 0) throw BuildException("Unbalanced quotes in command: $command")
                    args += command.substring(i + 1, close)
                    i = close + 1
                }
                else -> {
                    val start = i
                    while (i < n && !command[i].isWhitespace()) i++
                    args += command.substring(start, i)
                }
            }
        }
        return args
    }
}
