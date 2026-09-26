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
 * Two stacked strips: the smoothed weight line on top, with its range labelled, and
 * daily net balance below as bars against the goal's own dashed line. Stacked rather than
 * overlaid, because one set of pixels cannot mean kilograms and kilocalories at once.
 *
 * The point is the comparison: a run of on-target bars that is not accompanied by the
 * weight line moving is exactly what calibration exists to catch.
 */
object TrendChart {

    private const val BAR_GAP_FRACTION = 0.3f

    /** Share of the height given to the weight line, above the bars. */
    private const val WEIGHT_BAND = 0.34f

    /** Clear air between the two strips, so neither reads as part of the other. */
    private const val BAND_GAP = 0.06f

    /** Right-hand margin holding the weight strip's kg labels. */
    private const val LABEL_GUTTER = 0.13f

    private const val TEXT_FRACTION = 0.11f

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
        val label = if (night) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        val trend = if (night) 0xFFB9C3FF.toInt() else 0xFF3F51B5.toInt()
        val target = if (night) 0xFFE9B3F5.toInt() else 0xFF8E24AA.toInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textSize = height * TEXT_FRACTION

        // Two strips, not one overlay. Sharing the plot meant a pixel's height meant kcal
        // or kg depending on which line you were looking at, and half a kilo of scale
        // noise swung across the bars as though it were a thousand calories.
        val weightBand = if (weightSeries.size >= 2) height * WEIGHT_BAND else 0f
        val barTop = if (weightBand > 0f) weightBand + height * BAND_GAP else 0f
        val barBottom = height.toFloat()
        // Room for the kg labels down the right-hand edge of the weight strip.
        val plotWidth = if (weightBand > 0f) width * (1f - LABEL_GUTTER) else width.toFloat()

        if (weightSeries.size >= 2) {
            val min = weightSeries.min()
            val max = weightSeries.max()
            val span = (max - min).coerceAtLeast(0.2)
            val step = plotWidth / (weightSeries.size - 1)
            val path = Path()
            weightSeries.forEachIndexed { index, kg ->
                val x = index * step
                val y = (1f - ((kg - min) / span).toFloat()) * weightBand * 0.82f +
                    weightBand * 0.09f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.color = trend
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = height * 0.025f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.pathEffect = null

            // Without these the line is a shape with no magnitude, and a steep-looking
            // climb might be two hundred grams.
            paint.color = label
            paint.textSize = textSize
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("%.1f".format(max), width.toFloat(), textSize, paint)
            canvas.drawText("%.1f".format(min), width.toFloat(), weightBand, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        if (netSeries.isNotEmpty()) {
            // The axis covers what is there plus zero and the target, rather than running
            // symmetrically around zero: a fortnight of pure deficit under a symmetric
            // axis leaves the top half of the strip empty.
            val values = netSeries + targetNetKcal + 0.0
            val high = values.max()
            val low = values.min()
            val axisSpan = (high - low).coerceAtLeast(1.0)
            val strip = barBottom - barTop

            fun y(kcal: Double) =
                barTop + ((high - kcal) / axisSpan).toFloat() * strip * 0.94f + strip * 0.03f

            val zeroY = y(0.0)
            paint.color = axis
            paint.strokeWidth = 1f
            canvas.drawLine(0f, zeroY, width.toFloat(), zeroY, paint)

            val slot = plotWidth / netSeries.size
            val barWidth = slot * (1f - BAR_GAP_FRACTION)
            val gaining = targetNetKcal > 0

            netSeries.forEachIndexed { index, net ->
                val left = index * slot + (slot - barWidth) / 2f
                // Colour answers "did this day meet the goal", not "which side of zero".
                val onTrack = if (gaining) net >= targetNetKcal else net <= targetNetKcal
                paint.color = if (onTrack) deficit else surplus
                canvas.drawRect(left, minOf(y(net), zeroY), left + barWidth, maxOf(y(net), zeroY), paint)
            }

            if (targetNetKcal != 0.0) {
                paint.color = target
                paint.strokeWidth = height * 0.012f
                paint.pathEffect = DashPathEffect(floatArrayOf(width * 0.03f, width * 0.02f), 0f)
                canvas.drawLine(0f, y(targetNetKcal), plotWidth, y(targetNetKcal), paint)
                paint.pathEffect = null
            }
        }

        return bitmap
    }
}
