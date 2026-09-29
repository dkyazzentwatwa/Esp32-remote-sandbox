package org.espsketchide.app.editor

/** Symbols on the bar above the keyboard. Index 0 is Tab: sora turns an insert text of "\t" into indentOrCommitTab(). */
object SymbolBarSymbols {
    val display = arrayOf(
        "⇥", "{", "}", "(", ")", ";", "#", "<", ">", "\"", "'", "=", "+", "-", "*", "/",
        "&", "|", "!", "[", "]", ",", ".", "_", ":",
    )
    val insert = arrayOf("\t") + display.drop(1)
}
