package org.espsketchide.app.editor

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
