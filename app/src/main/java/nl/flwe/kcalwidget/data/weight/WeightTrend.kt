package nl.flwe.kcalwidget.data.weight

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

data class WeightPoint(
    val date: LocalDate,
    val rawKg: Double,
    /**
     * Causal smoothing: only the past is used, so this is what the trend looked like on
     * the day. Necessarily lags, which is the price of being computable before the next
     * reading exists.
     */
    val trendKg: Double,
    /**
     * Symmetric smoothing, using the readings either side. No lag, and therefore only
     * meaningful looking back -- the newest point has nothing to its right and drifts
     * towards the causal value. For charts of what happened, not for "what do I weigh".
     */
    val centredKg: Double = trendKg,
)

/**
 * A smoothed view of the scale.
 *
 * Day-to-day weight is mostly water, gut contents and salt; a single reading says almost
 * nothing, and two readings a week apart can disagree by more than a fortnight of real
 * change. Everything downstream — BMI, goal progress, calibration — uses [currentTrendKg]
 * rather than the last number the scale showed.
 */
data class WeightTrend(
    val points: List<WeightPoint>,
    val currentTrendKg: Double?,
    /** Slope of the smoothed series, kg per week. Null when there is too little to fit. */
    val weeklyChangeKg: Double?,
    val lastWeighIn: LocalDate?,
    val weighInDays: Int,
) {
    val hasEnoughForTrend: Boolean get() = weeklyChangeKg != null

    companion object {
        /**
         * Hacker's Diet style smoothing. Low enough that a heavy meal the night before
         * barely moves the line, high enough to follow a real change within a fortnight.
         */
        const val ALPHA = 0.1

        /** A slope fitted to fewer points than this is noise with a direction. */
        const val MIN_POINTS_FOR_SLOPE = 3

        /**
         * Builds the trend from raw readings, which may be irregular, out of order, or
         * several on one day.
         *
         * Gaps are handled by ageing the smoothing factor: skipping a week should let the
         * next reading move the trend further than a reading from yesterday would.
         */
        fun from(readings: List<Pair<LocalDate, Double>>): WeightTrend {
            if (readings.isEmpty()) {
                return WeightTrend(emptyList(), null, null, null, 0)
            }

            // Several readings on one day average out; morning and evening disagree more
            // than consecutive mornings do.
            val byDay = readings
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.average() }
                .toSortedMap()

            val points = mutableListOf<WeightPoint>()
            var trend = byDay.values.first()
            var previousDate: LocalDate? = null

            byDay.forEach { (date, raw) ->
                val gapDays = previousDate?.let { ChronoUnit.DAYS.between(it, date).toInt() } ?: 0
                if (gapDays > 0) {
                    val aged = 1.0 - (1.0 - ALPHA).pow(gapDays)
                    trend += aged * (raw - trend)
                }
                points += WeightPoint(date, raw, trend)
                previousDate = date
            }

            return WeightTrend(
                points = withCentred(points, byDay),
                currentTrendKg = points.last().trendKg,
                weeklyChangeKg = weeklySlope(points),
                lastWeighIn = points.last().date,
                weighInDays = points.size,
            )
        }

        /**
         * Fills in the lag-free series.
         *
         * A causal EMA is the right tool for "what do I weigh today" and the wrong one for
         * a chart of the past: it is behind the readings by construction, so plotting it
         * against anything makes the scale look like it is trailing the calories when it
         * is really just trailing itself. Looking back, the readings on both sides are
         * available, so there is no reason to use only one.
         *
         * A Gaussian over the actual dates rather than over positions, because weigh-ins
         * are irregular and a positional window would weight a cluster of three days the
         * same as three weeks.
         *
         * Local *line*, not local average, and that distinction is the difference between
         * working and not. A weighted mean has no neighbours to its right at the newest
         * point, so it quietly becomes a backwards-only average: on a falling weight it
         * averages in older, heavier readings and sits a few hundred grams above the
         * scale. That is the boundary bias of a kernel mean, and it lands exactly where
         * everyone looks. Fitting a line and taking its value at the point carries the
         * local slope into the estimate instead of flattening it, so both ends are
         * unbiased for anything locally straight -- which a weight trend is.
         */
        private fun withCentred(
            points: List<WeightPoint>,
            byDay: Map<LocalDate, Double>,
        ): List<WeightPoint> = points.map { point ->
            // Weighted sums for a straight line through the neighbourhood, y = a + b*gap,
            // read off at gap = 0. Fitting the line rather than averaging the values is
            // the whole point; see the note above.
            var sw = 0.0
            var swx = 0.0
            var swy = 0.0
            var swxx = 0.0
            var swxy = 0.0
            byDay.forEach { (date, raw) ->
                val gap = ChronoUnit.DAYS.between(point.date, date).toDouble()
                if (abs(gap) <= CENTRED_REACH_DAYS) {
                    val w = exp(-(gap * gap) / (2 * CENTRED_SIGMA_DAYS * CENTRED_SIGMA_DAYS))
                    sw += w
                    swx += w * gap
                    swy += w * raw
                    swxx += w * gap * gap
                    swxy += w * gap * raw
                }
            }
            if (sw <= 0.0) return@map point

            val denominator = sw * swxx - swx * swx
            // One reading, or several on a single day: no slope to fit, and the mean is
            // then both all there is and correct.
            if (abs(denominator) < 1e-9) return@map point.copy(centredKg = swy / sw)

            val slope = (sw * swxy - swx * swy) / denominator
            point.copy(centredKg = (swy - slope * swx) / sw)
        }

        /** Width of the symmetric smoother. Comparable in effect to the causal alpha. */
        const val CENTRED_SIGMA_DAYS = 3.5

        /** Beyond this a reading contributes nothing worth the arithmetic. */
        const val CENTRED_REACH_DAYS = 10.0

        /** Least-squares slope of the smoothed series, converted to kg per week. */
        private fun weeklySlope(points: List<WeightPoint>): Double? {
            if (points.size < MIN_POINTS_FOR_SLOPE) return null
            val origin = points.first().date
            val xs = points.map { ChronoUnit.DAYS.between(origin, it.date).toDouble() }
            val ys = points.map { it.trendKg }
            val meanX = xs.average()
            val meanY = ys.average()
            var numerator = 0.0
            var denominator = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - meanX
                numerator += dx * (ys[i] - meanY)
                denominator += dx * dx
            }
            // Every reading on the same day: a vertical line has no slope to report.
            if (denominator == 0.0) return null
            return (numerator / denominator) * 7.0
        }
    }
}
