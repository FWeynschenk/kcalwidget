package nl.flwe.kcalwidget.widget.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs

/**
 * Renders the last fortnight as a bitmap, because Glance has no drawing primitives.
 *
 * Daily net balance as bars around a zero line, judged against the goal's own dashed
 * line, with the smoothed weight line over the top. The point is the comparison: a run of
 * on-target bars that is not accompanied by the weight moving is exactly what calibration
 * exists to catch.
 */
object TrendChart {

    private const val BAR_GAP_FRACTION = 0.3f

    fun render(
        widthPx: Int,
        heightPx: Int,
        netSeries: List<Double>,
        weightSeries: List<Double>,
        /** The goal's daily allowance, drawn as the line the bars are judged against. */
        targetNetKcal: Double,
        night: Boolean,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val surplus = if (night) 0xFFFFB4AB.toInt() else 0xFFB3261E.toInt()
        val deficit = if (night) 0xFF7CE0B6.toInt() else 0xFF1B5E20.toInt()
        val axis = if (night) 0x40FFFFFF else 0x30000000
        val trend = if (night) 0xFFB9C3FF.toInt() else 0xFF3F51B5.toInt()
        val target = if (night) 0xFFE9B3F5.toInt() else 0xFF8E24AA.toInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Bars occupy the full height; the weight line rides in the middle band so the two
        // never fight for the same pixels at the extremes.
        val midY = height / 2f
        paint.color = axis
        paint.strokeWidth = 1f
        canvas.drawLine(0f, midY, width.toFloat(), midY, paint)

        if (netSeries.isNotEmpty()) {
            // The target has to be on the axis, or the line the bars are measured against
            // is drawn off the top of the chart.
            val maxMagnitude = (netSeries + targetNetKcal)
                .maxOf { abs(it) }.coerceAtLeast(1.0)
            val slot = width.toFloat() / netSeries.size
            val barWidth = slot * (1f - BAR_GAP_FRACTION)
            val gaining = targetNetKcal > 0

            fun y(kcal: Double) = midY - (kcal / maxMagnitude * (midY * 0.85f)).toFloat()

            netSeries.forEachIndexed { index, net ->
                val left = index * slot + (slot - barWidth) / 2f
                // Colour answers "did this day meet the goal", not "which side of zero".
                val onTrack = if (gaining) net >= targetNetKcal else net <= targetNetKcal
                paint.color = if (onTrack) deficit else surplus
                val top = minOf(y(net), midY)
                val bottom = maxOf(y(net), midY)
                canvas.drawRect(left, top, left + barWidth, bottom, paint)
            }

            if (targetNetKcal != 0.0) {
                paint.color = target
                paint.strokeWidth = height * 0.012f
                paint.pathEffect = DashPathEffect(floatArrayOf(width * 0.03f, width * 0.02f), 0f)
                canvas.drawLine(0f, y(targetNetKcal), width.toFloat(), y(targetNetKcal), paint)
                paint.pathEffect = null
            }
        }

        if (weightSeries.size >= 2) {
            val min = weightSeries.min()
            val max = weightSeries.max()
            val span = (max - min).coerceAtLeast(0.2)
            val band = height * 0.6f
            val top = height * 0.2f
            val step = width.toFloat() / (weightSeries.size - 1)
            val path = Path()
            weightSeries.forEachIndexed { index, kg ->
                val x = index * step
                val y = top + (1f - ((kg - min) / span).toFloat()) * band
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.color = trend
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = height * 0.035f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }

        return bitmap
    }
}
