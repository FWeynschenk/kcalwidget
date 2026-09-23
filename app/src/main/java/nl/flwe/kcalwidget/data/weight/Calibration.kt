package nl.flwe.kcalwidget.data.weight

import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.settings.CalibrationState
import nl.flwe.kcalwidget.data.settings.GoalDirection
import java.time.LocalDate
import kotlin.math.abs

/** Why calibration cannot say anything yet. Named so the UI can be specific. */
enum class CalibrationGap {
    NO_HISTORY,
    TOO_FEW_DAYS,
    TOO_FEW_WEIGH_INS,
    WEIGH_INS_NOT_SPREAD,
    SPARSE_FOOD_LOGGING,
}

/** One day on the two curves: where the calories say you should be, and where you are. */
data class DivergencePoint(
    val date: LocalDate,
    val expectedKg: Double,
    /** Null on days without a weigh-in; the smoothed trend only exists where it was measured. */
    val actualKg: Double?,
)

sealed interface CalibrationResult {
    /** Not enough to say anything honest. The UI names [gap] rather than showing a number. */
    data class NotEnoughData(val gap: CalibrationGap) : CalibrationResult

    /** The numbers agree within the noise of a bathroom scale. */
    data class Agrees(val kcalPerDay: Double) : CalibrationResult

    /**
     * Weight and energy balance disagree.
     *
     * Positive [kcalPerDay] means you gained more, or lost less, than the numbers predict:
     * either intake is under-recorded or burn is over-estimated.
     */
    data class Bias(
        val kcalPerDay: Double,
        /** What the scale says, kg per week. */
        val observedWeeklyKg: Double,
        /** What the food and burn numbers predict, kg per week. */
        val expectedWeeklyKg: Double,
        val expenditureShareKcal: Double,
        val intakeShareKcal: Double,
        val suggestedExpenditureFactor: Double,
        val suggestedIntakeFactor: Double,
        val windowDays: Int,
        val weighIns: Int,
        val intakeCoverage: Double,
    ) : CalibrationResult
}

/**
 * Checks the model against the only ground truth available: the scale.
 *
 * Over a long enough window, weight change and energy balance have to agree. Where they do
 * not, one of the inputs is biased — and since Health Connect gives no way to know which,
 * the split is a judgement the user makes, not one the app can measure.
 */
object Calibration {

    const val MIN_WINDOW_DAYS = 14

    /**
     * How far back to look.
     *
     * Not the whole history: someone who started logging a month ago would be judged on a
     * quarter of mostly empty days and fail the coverage check forever, even though their
     * recent weeks are complete.
     */
    const val WINDOW_DAYS = 28
    const val MIN_WEIGH_INS = 3

    /** Below this share of days carrying food logs, expected change is meaningless. */
    const val MIN_INTAKE_COVERAGE = 0.8

    /** A scale is good to a few hundred grams; below this the "bias" is just noise. */
    const val NOISE_FLOOR_KCAL = 75.0

    /** No correction may move a number more than this, however confident the fit looks. */
    const val MAX_FACTOR_DRIFT = 0.2

    /** Fraction of the gap closed per recomputation, so the budget never jumps. */
    const val EASING = 0.25

    fun analyse(history: History?, state: CalibrationState): CalibrationResult {
        if (history == null || history.rows.isEmpty()) {
            return CalibrationResult.NotEnoughData(CalibrationGap.NO_HISTORY)
        }

        val latest = history.rows.maxOfOrNull { it.date }
            ?: return CalibrationResult.NotEnoughData(CalibrationGap.NO_HISTORY)
        val cutoff = latest.minusDays(WINDOW_DAYS - 1L)

        val rows = history.rows.filter { it.date >= cutoff }
        val complete = rows.filter { it.intakeKcal != null && it.burnKcal != null }
        val points = history.trend.points.filter { it.date >= cutoff }
        val windowDays = rows.size

        if (windowDays < MIN_WINDOW_DAYS || complete.size < MIN_WINDOW_DAYS) {
            return CalibrationResult.NotEnoughData(CalibrationGap.TOO_FEW_DAYS)
        }
        if (points.size < MIN_WEIGH_INS) {
            return CalibrationResult.NotEnoughData(CalibrationGap.TOO_FEW_WEIGH_INS)
        }

        // Weigh-ins bunched at one end measure a moment, not a trend.
        val first = points.first().date
        val last = points.last().date
        val spanDays = java.time.temporal.ChronoUnit.DAYS.between(first, last)
        if (spanDays < MIN_WINDOW_DAYS - 1) {
            return CalibrationResult.NotEnoughData(CalibrationGap.WEIGH_INS_NOT_SPREAD)
        }

        val coverage = complete.size.toDouble() / windowDays
        if (coverage < MIN_INTAKE_COVERAGE) {
            return CalibrationResult.NotEnoughData(CalibrationGap.SPARSE_FOOD_LOGGING)
        }

        // Only days inside the weighed span can be blamed for the weight change across it.
        val inSpan = complete.filter { it.date >= first && it.date <= last }
        if (inSpan.isEmpty()) {
            return CalibrationResult.NotEnoughData(CalibrationGap.WEIGH_INS_NOT_SPREAD)
        }

        val expectedDeltaKg = inSpan.sumOf { it.netKcal!! } / Energetics.KCAL_PER_KG_FAT

        // The observed change comes from the fitted slope, not from the difference between
        // the first and last smoothed values. An EMA lags its input by a constant, and a
        // series that starts cold and ends warm carries that lag entirely in the endpoint
        // difference — which would understate every change and invent a bias of its own.
        // A slope is immune: lagging a line does not tilt it.
        val weeklySlope = history.trend.weeklyChangeKg
            ?: return CalibrationResult.NotEnoughData(CalibrationGap.TOO_FEW_WEIGH_INS)
        val observedDeltaKg = weeklySlope * spanDays / 7.0
        val days = inSpan.size
        val bias = (observedDeltaKg - expectedDeltaKg) * Energetics.KCAL_PER_KG_FAT / days

        if (abs(bias) < NOISE_FLOOR_KCAL) return CalibrationResult.Agrees(bias)

        val trust = state.intakeTrust
        val expenditureShare = bias * trust
        val intakeShare = bias * (1.0 - trust)

        val meanBurn = inSpan.mapNotNull { it.burnKcal }.average()
        val meanIntake = inSpan.mapNotNull { it.intakeKcal }.average()

        // Burn is running high by its share, so scale it down; intake is running low by
        // the rest, so scale it up.
        val expenditureFactor = if (meanBurn > 0) 1.0 - expenditureShare / meanBurn else 1.0
        val intakeFactor = if (meanIntake > 0) 1.0 + intakeShare / meanIntake else 1.0

        return CalibrationResult.Bias(
            kcalPerDay = bias,
            observedWeeklyKg = weeklySlope,
            expectedWeeklyKg = expectedDeltaKg / days * 7.0,
            expenditureShareKcal = expenditureShare,
            intakeShareKcal = intakeShare,
            suggestedExpenditureFactor = expenditureFactor.clampFactor(),
            suggestedIntakeFactor = intakeFactor.clampFactor(),
            windowDays = days,
            weighIns = points.size,
            intakeCoverage = coverage,
        )
    }

    /**
     * The disagreement in words.
     *
     * "Gaining more or losing less" is technically right and useless to read. Once the
     * goal direction is known, only one half of that is true, and saying the relevant half
     * is the difference between a sentence someone parses and one they skip.
     */
    fun explain(bias: Double, direction: GoalDirection?): String = when {
        direction == GoalDirection.LOSE && bias > 0 ->
            "You are losing more slowly than these numbers predict."

        direction == GoalDirection.LOSE ->
            "You are losing faster than these numbers predict."

        direction == GoalDirection.GAIN && bias > 0 ->
            "You are gaining faster than these numbers predict."

        direction == GoalDirection.GAIN ->
            "You are gaining more slowly than these numbers predict."

        direction == GoalDirection.MAINTAIN && bias > 0 ->
            "You are drifting upwards against these numbers."

        direction == GoalDirection.MAINTAIN ->
            "You are drifting downwards against these numbers."

        bias > 0 -> "You are gaining more, or losing less, than these numbers predict."
        else -> "You are losing more, or gaining less, than these numbers predict."
    }

    /**
     * The two curves behind the number: what the calories say your weight should have
     * done, and what it actually did.
     *
     * Anchored to the first smoothed weight in the window, so the lines start together and
     * the gap that opens between them is the whole of the disagreement.
     */
    fun divergence(history: History): List<DivergencePoint> {
        val latest = history.rows.maxOfOrNull { it.date } ?: return emptyList()
        val cutoff = latest.minusDays(WINDOW_DAYS - 1L)
        val rows = history.rows.filter { it.date >= cutoff }.sortedBy { it.date }
        val points = history.trend.points.filter { it.date >= cutoff }
        val anchor = points.firstOrNull()?.trendKg ?: return emptyList()
        val actualByDate = points.associate { it.date to it.trendKg }

        // The day's balance is recorded after its point, not before: the weight you see on
        // a given morning reflects the days behind it, not the one still to come. That also
        // makes the two curves genuinely start together, which is what the chart claims.
        var cumulative = 0.0
        return rows.map { row ->
            val point = DivergencePoint(
                date = row.date,
                expectedKg = anchor + cumulative,
                actualKg = actualByDate[row.date],
            )
            row.netKcal?.let { cumulative += it / Energetics.KCAL_PER_KG_FAT }
            point
        }
    }

    /** The correction to store after a fresh measurement. */
    data class Factors(val expenditure: Double, val intake: Double)

    /**
     * Where the correction should sit after a recheck.
     *
     * Only a measured bias moves it, and only when the user has asked for automatic
     * correction. "Agrees" deliberately leaves the factors alone: once a correction is
     * working, agreement is the evidence that it should stay, not that it should be undone.
     */
    fun nextFactors(
        current: CalibrationState,
        result: CalibrationResult,
        autoEnabled: Boolean,
    ): Factors {
        if (!autoEnabled || result !is CalibrationResult.Bias) {
            return Factors(current.expenditureFactor, current.intakeFactor)
        }
        return Factors(
            expenditure = ease(current.expenditureFactor, result.suggestedExpenditureFactor),
            intake = ease(current.intakeFactor, result.suggestedIntakeFactor),
        )
    }

    /** Moves part of the way towards the suggestion, so an applied change is never a jolt. */
    fun ease(current: Double, target: Double): Double =
        (current + EASING * (target - current)).clampFactor()

    private fun Double.clampFactor(): Double =
        coerceIn(1.0 - MAX_FACTOR_DRIFT, 1.0 + MAX_FACTOR_DRIFT)

    fun describeGap(gap: CalibrationGap): String = when (gap) {
        CalibrationGap.NO_HISTORY -> "No history has been read yet."
        CalibrationGap.TOO_FEW_DAYS ->
            "Needs at least $MIN_WINDOW_DAYS complete days with both food and burn recorded."
        CalibrationGap.TOO_FEW_WEIGH_INS -> "Needs at least $MIN_WEIGH_INS weigh-ins."
        CalibrationGap.WEIGH_INS_NOT_SPREAD ->
            "Your weigh-ins are bunched together. Spread over a couple of weeks they measure " +
                "a trend; close together they only measure one moment."
        CalibrationGap.SPARSE_FOOD_LOGGING ->
            "Too many days without food logged. Days with no record look like fasting, which " +
                "would make the comparison meaningless."
    }
}
