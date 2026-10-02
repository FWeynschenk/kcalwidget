package nl.flwe.kcalwidget.data.weight

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

data class WeightPoint(
    val date: LocalDate,
    val rawKg: Double,
    /**
     * Causal smoothing: only the past is used, so this is what the trend looked like on
     * the day. Necessarily lags, which is the price of being computable before the next
     * reading exists.
     *
     * Nothing displays this. It seeds [centredKg] and stands in where there is too
     * little around a point to fit a line. On a steady loss it sits above every reading,
     * which is correct for what it measures and misleading for everything else.
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

            // Computed once and used for all of it. Reading centredKg off the list that
            // went in rather than the one that came out silently gives back the causal
            // value, because that is what the field defaults to.
            val smoothed = withCentred(points, byDay)

            return WeightTrend(
                points = smoothed,
                // The lag-free estimate, not the causal one. A fortnight of steady loss
                // puts the EMA a few hundred grams above the scale, and this figure is
                // what BMI, the goal's "to go", the milestone test, the forecast's
                // starting point and the widget all read -- so the lag was not confined
                // to a chart line, it was quietly in every derived number.
                currentTrendKg = smoothed.last().centredKg,
                weeklyChangeKg = weeklySlope(smoothed),
                lastWeighIn = smoothed.last().date,
                weighInDays = smoothed.size,
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
        ): List<WeightPoint> {
            val dates = byDay.keys.toList()
            val values = byDay.values.toDoubleArray()
            var robustness = DoubleArray(dates.size) { 1.0 }
            var fitted = DoubleArray(dates.size)

            repeat(ROBUST_PASSES + 1) { pass ->
                dates.indices.forEach { i ->
                    fitted[i] = fitAt(dates[i], dates, values, robustness)
                }
                if (pass < ROBUST_PASSES) {
                    robustness = robustWeights(values, fitted)
                }
            }

            val byDate = dates.indices.associate { dates[it] to fitted[it] }
            return points.map { point ->
                byDate[point.date]?.let { point.copy(centredKg = it) } ?: point
            }
        }

        /** The local line through [target]'s neighbourhood, read off at [target] itself. */
        private fun fitAt(
            target: LocalDate,
            dates: List<LocalDate>,
            values: DoubleArray,
            robustness: DoubleArray,
        ): Double {
            var sw = 0.0
            var swx = 0.0
            var swy = 0.0
            var swxx = 0.0
            var swxy = 0.0
            dates.indices.forEach { i ->
                val gap = ChronoUnit.DAYS.between(target, dates[i]).toDouble()
                if (abs(gap) <= CENTRED_REACH_DAYS) {
                    val w = exp(-(gap * gap) / (2 * CENTRED_SIGMA_DAYS * CENTRED_SIGMA_DAYS)) *
                        robustness[i]
                    if (w > 0.0) {
                        sw += w
                        swx += w * gap
                        swy += w * values[i]
                        swxx += w * gap * gap
                        swxy += w * gap * values[i]
                    }
                }
            }
            if (sw <= 0.0) return values[dates.indexOf(target)]

            val denominator = sw * swxx - swx * swx
            // One reading, or several on a single day: no slope to fit, and the mean is
            // then both all there is and correct.
            if (abs(denominator) < 1e-9) return swy / sw

            val slope = (sw * swxy - swx * swy) / denominator
            return (swy - slope * swx) / sw
        }

        /**
         * Tukey's biweight over the residuals, which is what makes the local line safe.
         *
         * A local line is unbiased on a trend and, for exactly the same reason, credulous
         * about an outlier sitting at the edge of the data: it reads a single bad morning
         * as the start of a slope and follows it. Down-weighting whatever the first pass
         * could not explain, then fitting again, keeps the lag-free behaviour on a real
         * trend and discards the four kilos of water. Points more than six median
         * deviations out are dropped entirely.
         */
        private fun robustWeights(values: DoubleArray, fitted: DoubleArray): DoubleArray {
            val residuals = DoubleArray(values.size) { values[it] - fitted[it] }
            // Floored, because a run of consistent readings drives the median residual to
            // zero and then every deviation looks infinitely suspicious -- including the
            // real ones. A quarter of a kilo is about what water and gut contents move a
            // scale between mornings, so nothing inside that is treated as a signal.
            val spread = max(median(residuals.map { abs(it) }), MIN_RESIDUAL_SPREAD_KG)

            return DoubleArray(values.size) {
                val u = residuals[it] / (6.0 * spread)
                if (abs(u) >= 1.0) 0.0 else (1.0 - u * u).pow(2)
            }
        }

        private fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val sorted = xs.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }

        /** Re-weighting passes. One is enough to shrug off a stray morning; two is ample. */
        const val ROBUST_PASSES = 2

        /**
         * The smallest believable spread of residuals, in kg. Day-to-day scale noise does
         * not go below about this, so treating a tighter run as noiseless would make the
         * smoother reject ordinary variation as though it were an outlier.
         */
        const val MIN_RESIDUAL_SPREAD_KG = 0.25

        /** Width of the symmetric smoother. Comparable in effect to the causal alpha. */
        const val CENTRED_SIGMA_DAYS = 3.5

        /** Beyond this a reading contributes nothing worth the arithmetic. */
        const val CENTRED_REACH_DAYS = 10.0

        /**
         * Least-squares slope of the smoothed series, converted to kg per week.
         *
         * Over the lag-free series, so one object does not carry two different ideas of
         * what the weight was doing. A constant lag leaves a slope unchanged, so this is
         * near enough the same number either way -- but mixing the two is how they stop
         * agreeing the moment the trend bends.
         */
        private fun weeklySlope(points: List<WeightPoint>): Double? {
            if (points.size < MIN_POINTS_FOR_SLOPE) return null
            val origin = points.first().date
            val xs = points.map { ChronoUnit.DAYS.between(origin, it.date).toDouble() }
            val ys = points.map { it.centredKg }
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
