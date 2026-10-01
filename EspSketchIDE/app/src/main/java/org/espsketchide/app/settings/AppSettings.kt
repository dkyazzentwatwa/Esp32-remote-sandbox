package org.espsketchide.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import org.espsketchide.app.editor.FontSize

/** SharedPreferences file shared by settings and [org.espsketchide.app.data.SketchRepository]. */
const val PREFS_NAME = "esp_sketch_ide"

const val KEY_THEME = "theme"
const val KEY_EDITOR_FONT_SIZE = "editor_font_size"
const val KEY_WORD_WRAP = "word_wrap"
const val KEY_BOARD = "board"
const val KEY_SHOW_SYMBOL_BAR = "show_symbol_bar"

enum class ThemeMode(val key: String, val nightMode: Int) {
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    companion object {
        val DEFAULT = DARK

        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Only selects examples, templates and autocomplete lists. The app cannot build for a board yet. */
enum class Board(val key: String) {
    ESP32("esp32"),
    ESP8266("esp8266");

    companion object {
        val DEFAULT = ESP32

        fun fromKey(key: String?): Board = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Whole-sp editor text size as stored by Settings; same range as pinch zoom ([FontSize]). */
object EditorFontSize {
    val MIN = FontSize.MIN.toInt()
    val MAX = FontSize.MAX.toInt()
    val DEFAULT = FontSize.DEFAULT.toInt()

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

/** Read-only, typed view of the user's settings. The Settings screen writes them. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME, null))

    /** Set by the Settings slider and by pinch zoom in the editor. */
    var editorFontSize: Int
        get() = EditorFontSize.clamp(prefs.getInt(KEY_EDITOR_FONT_SIZE, EditorFontSize.DEFAULT))
        set(value) = prefs.edit { putInt(KEY_EDITOR_FONT_SIZE, EditorFontSize.clamp(value)) }

    val wordWrap: Boolean
        get() = prefs.getBoolean(KEY_WORD_WRAP, false)

    val board: Board
        get() = Board.fromKey(prefs.getString(KEY_BOARD, null))

    val showSymbolBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYMBOL_BAR, true)
}
