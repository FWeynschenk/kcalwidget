package nl.flwe.kcalwidget.data.weight

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

data class WeightPoint(
    val date: LocalDate,
    val rawKg: Double,
    val trendKg: Double,
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
                points = points,
                currentTrendKg = points.last().trendKg,
                weeklyChangeKg = weeklySlope(points),
                lastWeighIn = points.last().date,
                weighInDays = points.size,
            )
        }

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
