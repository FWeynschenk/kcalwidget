package nl.flwe.kcalwidget.data.weight

import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.settings.AppSettings
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** One day of the forecast, as a band rather than a single confident line. */
data class ForecastPoint(
    val date: LocalDate,
    /** The two methods, ordered so [lowKg] is always the lighter of them. */
    val lowKg: Double,
    val highKg: Double,
) {
    val midKg: Double get() = (lowKg + highKg) / 2.0
}

/**
 * Where the weight is heading, from the two things that can say so.
 *
 * The scale, fitted over recent weeks, is ground truth but backward-looking: it cannot
 * know about a change made this week. The calorie balance is forward-looking but only as
 * trustworthy as the numbers feeding it, which is exactly what calibration exists to
 * measure.
 *
 * Neither deserves to be presented as the answer, so both are, as a band. When they agree
 * the band is narrow and the forecast means something; when they disagree it is wide, and
 * that width is the honest report. Collapsing it to one line would be inventing precision
 * that is not there.
 */
data class WeightForecast(
    val startKg: Double,
    val points: List<ForecastPoint>,
    /** Fitted from the scale, kg per week. Negative loses. */
    val trendRateKgPerWeek: Double?,
    /** Implied by recent intake against burn, kg per week. */
    val balanceRateKgPerWeek: Double?,
    val targetKg: Double?,
    /** Earliest and latest the two methods have the target arriving. */
    val targetRange: Pair<LocalDate, LocalDate>?,
) {
    val hasBand: Boolean get() = trendRateKgPerWeek != null && balanceRateKgPerWeek != null

    /** How far apart the two methods are, in kg per week. Null unless both exist. */
    val disagreementKgPerWeek: Double?
        get() = if (hasBand) abs(trendRateKgPerWeek!! - balanceRateKgPerWeek!!) else null

    fun at(date: LocalDate): ForecastPoint? = points.firstOrNull { it.date == date }

    companion object {

        /** Twelve weeks. Far enough to be useful, near enough not to be fiction. */
        const val HORIZON_DAYS = 84L

        /** Below this a rate is noise, and projecting it produces a confident nonsense. */
        const val MIN_RATE_KG_PER_WEEK = 0.02

        /** Past this the arrival date is a guess dressed as a plan. */
        const val MAX_PROJECTION_DAYS = 730L

        /** Recent days the balance rate is averaged over. */
        const val BALANCE_WINDOW_DAYS = 21

        /**
         * @param rows complete days, oldest first, as recorded by the apps (uncalibrated).
         */
        fun from(
            trend: WeightTrend,
            rows: List<DayRow>,
            settings: AppSettings,
            today: LocalDate,
            horizonDays: Long = HORIZON_DAYS,
        ): WeightForecast? {
            val start = trend.currentTrendKg ?: return null

            val trendRate = trend.weeklyChangeKg
            val balanceRate = balanceRate(rows, settings)
            val rates = listOfNotNull(trendRate, balanceRate)
            if (rates.isEmpty()) return null

            val points = (0..horizonDays).map { day ->
                val weeks = day / 7.0
                val projected = rates.map { start + it * weeks }
                ForecastPoint(today.plusDays(day), projected.min(), projected.max())
            }

            val target = settings.goal.targetWeightKg
            val arrivals = target?.let { t -> rates.mapNotNull { arrival(start, t, it, today) } }
            val range = if (arrivals.isNullOrEmpty()) null else arrivals.min() to arrivals.max()

            return WeightForecast(start, points, trendRate, balanceRate, target, range)
        }

        /**
         * Recent energy balance as a weekly weight change.
         *
         * Calibrated, because an uncalibrated balance is the very number the scale has
         * already been used to correct: forecasting from it would re-introduce the error
         * calibration exists to remove.
         */
        internal fun balanceRate(rows: List<DayRow>, settings: AppSettings): Double? {
            val calibrating = settings.features.autoCalibration
            val burnFactor = if (calibrating) settings.calibration.expenditureFactor else 1.0
            val intakeFactor = if (calibrating) settings.calibration.intakeFactor else 1.0

            val recent = rows.takeLast(BALANCE_WINDOW_DAYS)
                .filter { it.intakeKcal != null && it.burnKcal != null }
            if (recent.size < 7) return null

            val meanNet = recent.sumOf {
                it.intakeKcal!! * intakeFactor - it.burnKcal!! * burnFactor
            } / recent.size
            return meanNet * 7.0 / Energetics.KCAL_PER_KG_FAT
        }

        /** When a rate carries [from] to [target], or null if it never does. */
        private fun arrival(
            from: Double,
            target: Double,
            ratePerWeek: Double,
            today: LocalDate,
        ): LocalDate? {
            val gap = target - from
            if (abs(gap) < 0.05) return today
            if (abs(ratePerWeek) < MIN_RATE_KG_PER_WEEK) return null
            // A rate pointing the other way never arrives, however long you wait.
            if (gap > 0 != ratePerWeek > 0) return null

            val days = (gap / ratePerWeek * 7.0).roundToLong()
            return if (days in 0..MAX_PROJECTION_DAYS) today.plusDays(days) else null
        }
    }
}

/** Whole weeks between two dates, for phrasing a forecast without a date everyone re-reads. */
fun weeksBetween(from: LocalDate, to: LocalDate): Long =
    ChronoUnit.DAYS.between(from, to) / 7
