package org.espsketchide.app.editor

object SearchCounter {
    /** [index] is EditorSearcher's zero-based current match, -1 when none is selected. */
    fun text(index: Int, count: Int, noResults: String): String = when {
        count <= 0 -> noResults
        index < 0 -> "-/$count"
        else -> "${index + 1}/$count"
    }
}
