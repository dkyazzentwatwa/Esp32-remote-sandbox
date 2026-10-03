package org.espsketchide.app.examples

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.espsketchide.app.R

/** The lesson shown when an example is tapped: what it teaches, wiring, how it works, challenges. */
object LessonCard {

    fun show(context: Context, example: Example, onOpen: () -> Unit) {
        val lesson = example.lesson
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(example.name)
            .setPositiveButton(R.string.lesson_open) { _, _ -> onOpen() }
            .setNegativeButton(R.string.dialog_cancel, null)
        if (lesson == null) builder.setMessage(example.description) else builder.setView(view(context, lesson))
        builder.show()
    }

    private fun view(context: Context, lesson: Lesson): View {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * density).toInt(), pad, 0)
        }
        val label = com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
        val body = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        fun text(value: String, style: Int, topDp: Int) = column.addView(TextView(context).apply {
            text = value
            setTextAppearance(style)
            setPadding(0, (topDp * density).toInt(), 0, 0)
        })
        fun section(title: Int, content: String, first: Boolean = false) {
            text(context.getString(title), label, if (first) 0 else 16)
            text(content, body, 4)
        }
        section(R.string.lesson_learn, lesson.learn, first = true)
        section(R.string.lesson_wiring, lesson.wiring)
        section(R.string.lesson_how, lesson.how)
        if (lesson.tryThis.isNotEmpty()) {
            text(context.getString(R.string.lesson_try), label, 16)
            lesson.tryThis.forEachIndexed { i, challenge -> text("${i + 1}. $challenge", body, 4) }
        }
        return ScrollView(context).apply { addView(column) }
    }
}
