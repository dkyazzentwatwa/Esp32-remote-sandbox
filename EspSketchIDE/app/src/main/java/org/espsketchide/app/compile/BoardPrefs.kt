package org.espsketchide.app.compile

import android.content.Context
import org.espsketchide.buildengine.esp32.PackLayout

/** The board picked for building, remembered across launches. */
class BoardPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("esp_sketch_ide", Context.MODE_PRIVATE)

    /** The chosen board if [pack] still has it, else the pack's default. */
    fun board(pack: PackLayout): String =
        prefs.getString(KEY_BOARD, null)?.takeIf { id -> pack.boards.any { it.first == id } } ?: pack.defaultBoard

    fun setBoard(id: String) = prefs.edit().putString(KEY_BOARD, id).apply()

    private companion object {
        const val KEY_BOARD = "board_id"
    }
}
