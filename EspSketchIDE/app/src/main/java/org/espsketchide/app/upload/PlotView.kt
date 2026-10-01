package org.espsketchide.app.upload

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.espsketchide.app.R

/** Draws [SerialPlot] series as lines with an auto-scaled Y axis and a legend. */
class PlotView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var series: Map<String, FloatArray> = emptyMap()
    private val density = resources.displayMetrics.density
    private val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply {
        color = ContextCompat.getColor(context, R.color.esp_divider)
        strokeWidth = density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.esp_on_surface_muted)
        textSize = 11 * density
        typeface = mono
    }
    private val path = Path()

    fun setSeries(value: Map<String, FloatArray>) {
        series = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = 48 * density
        val top = 28 * density
        val right = width - 8 * density
        val bottom = height - 12 * density
        val all = series.values.flatMap { it.asIterable() }
        if (all.isEmpty()) {
            canvas.drawText(context.getString(R.string.plotter_waiting), left, height / 2f, label)
            return
        }
        var min = all.min()
        var max = all.max()
        if (max - min < 1e-6f) { min -= 1f; max += 1f }
        val pad = (max - min) * 0.08f
        min -= pad
        max += pad
        fun y(v: Float) = bottom - (v - min) / (max - min) * (bottom - top)

        for (i in 0..4) {
            val v = min + (max - min) * i / 4
            val gy = y(v)
            canvas.drawLine(left, gy, right, gy, grid)
            canvas.drawText(format(v), 4 * density, gy + 4 * density, label)
        }

        val points = series.values.maxOf { it.size }.coerceAtLeast(2)
        var legendX = left
        series.entries.forEachIndexed { index, (name, values) ->
            line.color = COLORS[index % COLORS.size]
            path.reset()
            values.forEachIndexed { i, v ->
                val x = left + (right - left) * (points - values.size + i) / (points - 1)
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            canvas.drawPath(path, line)
            // Legend: colored name and latest value.
            val text = "$name ${format(values.last())}"
            label.color = COLORS[index % COLORS.size]
            canvas.drawText(text, legendX, 18 * density, label)
            legendX += label.measureText(text) + 16 * density
        }
        label.color = ContextCompat.getColor(context, R.color.esp_on_surface_muted)
    }

    private fun format(v: Float): String =
        if (kotlin.math.abs(v) >= 1000 || v == kotlin.math.floor(v)) "%.0f".format(v) else "%.2f".format(v)

    private companion object {
        val COLORS = intArrayOf(
            Color.parseColor("#4FC1FF"), Color.parseColor("#FFA657"), Color.parseColor("#7EE787"),
            Color.parseColor("#F778BA"), Color.parseColor("#D2A8FF"), Color.parseColor("#E3B341"),
        )
    }
}
