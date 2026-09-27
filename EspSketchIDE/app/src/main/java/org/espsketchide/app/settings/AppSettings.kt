package org.espsketchide.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** SharedPreferences file shared by settings and [org.espsketchide.app.data.SketchRepository]. */
const val PREFS_NAME = "esp_sketch_ide"

const val KEY_THEME = "theme"
const val KEY_EDITOR_FONT_SIZE = "editor_font_size"
const val KEY_WORD_WRAP = "word_wrap"

enum class ThemeMode(val key: String, val nightMode: Int) {
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    companion object {
        val DEFAULT = DARK

        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

object EditorFontSize {
    const val MIN = 10
    const val MAX = 24
    const val DEFAULT = 14

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

/** Read-only, typed view of the user's settings. The Settings screen writes them. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME, null))

    val editorFontSize: Int
        get() = EditorFontSize.clamp(prefs.getInt(KEY_EDITOR_FONT_SIZE, EditorFontSize.DEFAULT))

    val wordWrap: Boolean
        get() = prefs.getBoolean(KEY_WORD_WRAP, false)
}
