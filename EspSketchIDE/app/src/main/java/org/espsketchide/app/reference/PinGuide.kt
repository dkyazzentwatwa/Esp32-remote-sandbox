package org.espsketchide.app.reference

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.espsketchide.app.R
import org.espsketchide.app.settings.Board
import org.json.JSONArray
import org.json.JSONObject

enum class PinLevel(val key: String, val marker: String) {
    SAFE("safe", "✅"), CAUTION("caution", "⚠️"), AVOID("avoid", "⛔");

    companion object {
        fun fromKey(key: String): PinLevel =
            entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown pin level '$key'")
    }
}

data class Pin(val name: String, val level: PinLevel, val features: String, val note: String)

data class BoardPins(val board: String, val title: String, val notes: List<String>, val pins: List<Pin>)

/** Which pins are safe to use on each board family (assets/reference/pins.json). */
class PinGuide(private val boards: List<BoardPins>) {

    fun forBoard(board: Board): BoardPins? = boards.firstOrNull { it.board == board.key }

    companion object {

        fun parse(json: String): PinGuide {
            val array = JSONObject(json).getJSONArray("boards")
            return PinGuide((0 until array.length()).map { i ->
                val b = array.getJSONObject(i)
                val pins = b.getJSONArray("pins")
                BoardPins(
                    board = b.getString("board"),
                    title = b.getString("title"),
                    notes = b.getJSONArray("notes").strings(),
                    pins = (0 until pins.length()).map { j ->
                        val p = pins.getJSONObject(j)
                        Pin(p.getString("name"), PinLevel.fromKey(p.getString("level")), p.optString("features"), p.optString("note"))
                    },
                )
            })
        }

        private fun JSONArray.strings() = (0 until length()).map { getString(it) }

        /** Loads the guide (small asset) and shows the selected board family's pins. */
        fun show(context: Context, board: Board) {
            val guide = parse(context.assets.open("reference/pins.json").bufferedReader().use { it.readText() })
            val pins = guide.forBoard(board) ?: return
            MaterialAlertDialogBuilder(context)
                .setTitle(pins.title)
                .setView(view(context, pins))
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }

        private fun view(context: Context, board: BoardPins): View {
            val density = context.resources.displayMetrics.density
            val pad = (24 * density).toInt()
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, (4 * density).toInt(), pad, 0)
            }
            fun text(value: String, style: Int, topDp: Int) = column.addView(TextView(context).apply {
                text = value
                setTextAppearance(style)
                setPadding(0, (topDp * density).toInt(), 0, 0)
            })
            val label = com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
            val body = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
            val small = com.google.android.material.R.style.TextAppearance_Material3_BodySmall
            board.notes.forEach { text("• $it", body, 4) }
            text(context.getString(R.string.pins_legend), small, 12)
            board.pins.forEach { pin ->
                text("${pin.level.marker}  ${pin.name}", label, 14)
                text(pin.features, body, 2)
                if (pin.note.isNotEmpty()) text(pin.note, small, 2)
            }
            return ScrollView(context).apply { addView(column) }
        }
    }
}
