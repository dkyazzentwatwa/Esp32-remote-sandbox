package org.espsketchide.app.reference

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.espsketchide.app.R
import org.espsketchide.app.settings.Board

/** Dialogs that show reference cards, and loading the bundled reference (call off the main thread). */
object ReferenceCards {

    @Volatile
    private var cached: ArduinoReference? = null

    fun load(context: Context): ArduinoReference = cached ?: synchronized(this) {
        cached ?: ArduinoReference.parse(
            context.assets.open("reference/reference.json").bufferedReader().use { it.readText() }
        ).also { cached = it }
    }

    fun show(context: Context, entry: ReferenceEntry, board: Board) {
        MaterialAlertDialogBuilder(context)
            .setTitle(entry.name)
            .setView(cardView(context, entry, board))
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    /** Every entry, by category; tapping one opens its card. */
    fun showIndex(context: Context, reference: ArduinoReference, board: Board) {
        val entries = reference.byCategory().flatMap { it.second }.filter { it.appliesTo(board) }
        val labels = entries.map { "${it.name}  ·  ${it.category}" }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.reference_title)
            .setItems(labels) { _, which -> show(context, entries[which], board) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun cardView(context: Context, entry: ReferenceEntry, board: Board): View {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        fun text(value: CharSequence, style: Int, monospace: Boolean = false, topDp: Int = 8) = TextView(context).apply {
            text = value
            setTextAppearance(style)
            if (monospace) typeface = mono
            setPadding(0, (topDp * density).toInt(), 0, 0)
            setTextIsSelectable(true)
        }.also(column::addView)
        val label = com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
        val body = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium

        text(entry.syntax, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, monospace = true, topDp = 0)
        text(entry.summary, body)
        if (entry.params.isNotEmpty()) {
            text(context.getString(R.string.reference_parameters), label, topDp = 16)
            entry.params.forEach { (name, desc) -> text("$name: $desc", body, topDp = 4) }
        }
        entry.returns?.let {
            text(context.getString(R.string.reference_returns), label, topDp = 16)
            text(it, body, topDp = 4)
        }
        entry.example?.let {
            text(context.getString(R.string.reference_example), label, topDp = 16)
            text(it, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, monospace = true, topDp = 4)
        }
        entry.note?.let { text(context.getString(R.string.reference_note, it), body, topDp = 16) }
        if (!entry.appliesTo(board)) {
            text(context.getString(R.string.reference_other_board, entry.boards.joinToString { it.uppercase() }), body, topDp = 16)
        }
        return ScrollView(context).apply { addView(column) }
    }
}
