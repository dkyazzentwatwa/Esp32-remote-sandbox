package org.espsketchide.app.editor

import android.content.Context
import androidx.core.content.edit

/** Editor text size in sp, kept within a range that stays readable on phones. */
object FontSize {
    const val MIN = 10f
    const val MAX = 28f
    const val DEFAULT = 14f
    const val STEP = 2f

    fun clamp(sp: Float): Float = sp.coerceIn(MIN, MAX)

    fun larger(sp: Float): Float = clamp(sp + STEP)

    fun smaller(sp: Float): Float = clamp(sp - STEP)
}

class EditorPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("editor", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = FontSize.clamp(prefs.getFloat(KEY_FONT_SIZE, FontSize.DEFAULT))
        set(value) = prefs.edit { putFloat(KEY_FONT_SIZE, FontSize.clamp(value)) }

    private companion object {
        const val KEY_FONT_SIZE = "font_size_sp"
    }
}
