package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.history.HistoryDiagnostics
import nl.flwe.kcalwidget.data.settings.CalibrationState
import nl.flwe.kcalwidget.data.settings.GoalDirection
import nl.flwe.kcalwidget.data.settings.LoggingAccuracy
import nl.flwe.kcalwidget.data.weight.Calibration
import nl.flwe.kcalwidget.data.weight.CalibrationGap
import nl.flwe.kcalwidget.data.weight.CalibrationResult
import nl.flwe.kcalwidget.data.weight.WeightPoint
import nl.flwe.kcalwidget.data.weight.WeightTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class CalibrationTest {

    private val start = LocalDate.of(2026, 6, 1)
    private val days = 21

    /**
     * Days with a fixed intake and burn. Default is eating 2000 against burning 2500, a
     * 500 kcal/day deficit, which over 21 days predicts losing 1.364 kg.
     */
    private fun rows(
        count: Int = days,
        intake: Double? = 2000.0,
        burn: Double? = 2500.0,
    ) = (0 until count).map { i ->
        DayRow(start.plusDays(i.toLong()), intake, burn, null)
    }

    /** A trend with a slope stated outright, so the arithmetic under test stays visible. */
    private fun trend(weeklyKg: Double?, count: Int = days, spanDays: Int = days - 1) =
        WeightTrend(
            points = (0 until count).map { i ->
                val date = start.plusDays((i.toLong() * spanDays) / (count - 1).coerceAtLeast(1))
                WeightPoint(date, 90.0, 90.0)
            },
            currentTrendKg = 90.0,
            weeklyChangeKg = weeklyKg,
            lastWeighIn = start.plusDays(spanDays.toLong()),
            weighInDays = count,
        )

    private fun history(
        rows: List<DayRow> = rows(),
        trend: WeightTrend = trend(-0.4545),
    ) = History(rows, trend, 0.0, NO_DIAGNOSTICS)

    private val NO_DIAGNOSTICS = HistoryDiagnostics(
        requestedDays = 0,
        allowedDays = 0,
        hasHistoryPermission = true,
        intakeDays = 0,
        burnDays = 0,
        weighInCount = 0,
        weightSample = emptyList(),
        errors = emptyList(),
        timings = emptyList(),
    )

    private fun analyse(h: History?, state: CalibrationState = CalibrationState()) =
        Calibration.analyse(h, state)

    // --- gating ------------------------------------------------------------------------

    @Test
    fun `no history says so rather than guessing`() {
        val gap = analyse(null) as CalibrationResult.NotEnoughData
        assertEquals(CalibrationGap.NO_HISTORY, gap.gap)
    }

    @Test
    fun `a short window is refused`() {
        val gap = analyse(history(rows = rows(count = 10))) as CalibrationResult.NotEnoughData
        assertEquals(CalibrationGap.TOO_FEW_DAYS, gap.gap)
    }

    @Test
    fun `too few weigh-ins is refused`() {
        val gap = analyse(history(trend = trend(-0.45, count = 2)))
            as CalibrationResult.NotEnoughData
        assertEquals(CalibrationGap.TOO_FEW_WEIGH_INS, gap.gap)
    }

    @Test
    fun `weigh-ins bunched into a few days are refused`() {
        // Three weigh-ins across three days measure a moment, not a fortnight.
        val gap = analyse(history(trend = trend(-0.45, count = 3, spanDays = 2)))
            as CalibrationResult.NotEnoughData
        assertEquals(CalibrationGap.WEIGH_INS_NOT_SPREAD, gap.gap)
    }

    @Test
    fun `sparse food logging is refused, because a missing day looks like a fast`() {
        val mixed = rows().mapIndexed { i, row ->
            if (i % 3 == 0) row.copy(intakeKcal = null) else row
        }
        val gap = analyse(history(rows = mixed)) as CalibrationResult.NotEnoughData
        assertEquals(CalibrationGap.SPARSE_FOOD_LOGGING, gap.gap)
    }

    @Test
    fun `a trend with no fitted slope is refused rather than assumed flat`() {
        val gap = analyse(history(trend = trend(null))) as CalibrationResult.NotEnoughData
        assertEquals(CalibrationGap.TOO_FEW_WEIGH_INS, gap.gap)
    }

    // --- the measurement ---------------------------------------------------------------

    @Test
    fun `when the scale matches the numbers it says so`() {
        // -500 kcal/day for 21 days is -1.3636 kg, i.e. -0.4545 kg/week.
        val result = analyse(history(trend = trend(-0.4545))) as CalibrationResult.Agrees
        assertTrue(abs(result.kcalPerDay) < Calibration.NOISE_FLOOR_KCAL)
    }

    @Test
    fun `losing less than predicted is a positive bias`() {
        // Only half the predicted loss: something is adding about 250 kcal a day.
        val result = analyse(history(trend = trend(-0.2273))) as CalibrationResult.Bias
        assertTrue("expected a positive bias, got ${result.kcalPerDay}", result.kcalPerDay > 0)
        assertEquals(250.0, result.kcalPerDay, 15.0)
    }

    @Test
    fun `losing more than predicted is a negative bias`() {
        val result = analyse(history(trend = trend(-0.909))) as CalibrationResult.Bias
        assertTrue(result.kcalPerDay < 0)
    }

    @Test
    fun `the arithmetic matches a hand calculation`() {
        // 21 days at -500 predicts -1.3636 kg. Observed 0 kg means the whole 500 is missing.
        val result = analyse(history(trend = trend(0.0))) as CalibrationResult.Bias
        assertEquals(500.0, result.kcalPerDay, 1.0)
        val expectedKg = -500.0 * days / Energetics.KCAL_PER_KG_FAT
        assertEquals(-1.3636, expectedKg, 0.001)
    }

    // --- attribution ---------------------------------------------------------------------

    @Test
    fun `without an answer most of the blame lands on burn`() {
        val result = analyse(history(trend = trend(0.0))) as CalibrationResult.Bias
        assertEquals(0.85, result.expenditureShareKcal / result.kcalPerDay, 0.001)
        assertTrue(result.expenditureShareKcal > result.intakeShareKcal)
        // Burn is scaled down, intake scaled up.
        assertTrue(result.suggestedExpenditureFactor < 1.0)
        assertTrue(result.suggestedIntakeFactor > 1.0)
    }

    @Test
    fun `admitting rough logging moves the blame onto food`() {
        val weighed = analyse(
            history(trend = trend(0.0)),
            CalibrationState(loggingAccuracy = LoggingAccuracy.WEIGHED),
        ) as CalibrationResult.Bias
        val rough = analyse(
            history(trend = trend(0.0)),
            CalibrationState(loggingAccuracy = LoggingAccuracy.ROUGH),
        ) as CalibrationResult.Bias

        assertEquals(0.95, weighed.expenditureShareKcal / weighed.kcalPerDay, 0.001)
        assertEquals(0.30, rough.expenditureShareKcal / rough.kcalPerDay, 0.001)
        assertTrue(rough.intakeShareKcal > weighed.intakeShareKcal)
        assertEquals(weighed.kcalPerDay, rough.kcalPerDay, 0.001)
    }

    // --- safety ---------------------------------------------------------------------------

    @Test
    fun `corrections are capped however large the disagreement`() {
        // An absurd disagreement must still not move a number by more than the cap.
        val result = analyse(history(trend = trend(2.0))) as CalibrationResult.Bias
        assertTrue(result.suggestedExpenditureFactor >= 1.0 - Calibration.MAX_FACTOR_DRIFT)
        assertTrue(result.suggestedExpenditureFactor <= 1.0 + Calibration.MAX_FACTOR_DRIFT)
        assertTrue(result.suggestedIntakeFactor <= 1.0 + Calibration.MAX_FACTOR_DRIFT)
    }

    @Test
    fun `easing closes a quarter of the gap at a time`() {
        assertEquals(0.95, Calibration.ease(1.0, 0.8), 0.0001)
        assertEquals(1.05, Calibration.ease(1.0, 1.2), 0.0001)
        // Repeated easing converges without overshooting.
        var f = 1.0
        repeat(40) { f = Calibration.ease(f, 0.85) }
        assertEquals(0.85, f, 0.001)
    }

    @Test
    fun `easing respects the cap even if handed something wild`() {
        assertTrue(Calibration.ease(1.0, 10.0) <= 1.0 + Calibration.MAX_FACTOR_DRIFT)
        assertTrue(Calibration.ease(1.0, -10.0) >= 1.0 - Calibration.MAX_FACTOR_DRIFT)
    }

    // --- saying it clearly -----------------------------------------------------------------

    @Test
    fun `the wording follows the direction of travel`() {
        // Same bias, opposite goals, opposite readings.
        assertTrue(Calibration.explain(114.0, GoalDirection.LOSE).contains("losing more slowly"))
        assertTrue(Calibration.explain(114.0, GoalDirection.GAIN).contains("gaining faster"))
        assertTrue(Calibration.explain(-114.0, GoalDirection.LOSE).contains("losing faster"))
        assertTrue(
            Calibration.explain(-114.0, GoalDirection.GAIN).contains("gaining more slowly")
        )
    }

    @Test
    fun `without a goal it stays hedged rather than guessing`() {
        val text = Calibration.explain(114.0, null)
        assertTrue(text.contains("gaining more"))
        assertTrue(text.contains("losing less"))
    }

    @Test
    fun `both rates are reported, not just the difference`() {
        val result = analyse(history(trend = trend(0.0))) as CalibrationResult.Bias
        // Flat scale against a 500 kcal/day deficit.
        assertEquals(0.0, result.observedWeeklyKg, 0.001)
        assertEquals(-0.4545, result.expectedWeeklyKg, 0.01)
    }

    // --- the divergence curves ---------------------------------------------------------

    @Test
    fun `the curves start together and part by the measured amount`() {
        val h = history(trend = trend(0.0))
        val series = Calibration.divergence(h)
        assertEquals(days, series.size)
        // Anchored to the same weight, so day one has no gap at all.
        assertEquals(90.0, series.first().expectedKg, 0.001)
        assertEquals(90.0, series.first().actualKg!!, 0.001)
        // Each point reflects the days behind it, so the last shows 20 days of -500 kcal
        // while the scale stayed at 90.
        assertEquals(90.0 - 20 * 500.0 / 7700.0, series.last().expectedKg, 0.01)
        assertEquals(90.0, series.last().actualKg!!, 0.001)
    }

    @Test
    fun `days without a weigh-in leave a gap rather than inventing one`() {
        val sparse = WeightTrend(
            points = listOf(
                WeightPoint(start, 90.0, 90.0),
                WeightPoint(start.plusDays(20), 89.0, 89.0),
            ),
            currentTrendKg = 89.0,
            weeklyChangeKg = -0.33,
            lastWeighIn = start.plusDays(20),
            weighInDays = 2,
        )
        val series = Calibration.divergence(history(trend = sparse))
        assertEquals(2, series.count { it.actualKg != null })
        assertTrue(series.all { it.expectedKg > 0 })
    }

    @Test
    fun `no weigh-ins means no curves to draw`() {
        val none = WeightTrend.from(emptyList())
        assertTrue(Calibration.divergence(history(trend = none)).isEmpty())
    }

    // --- staying up to date ---------------------------------------------------------------

    @Test
    fun `a recheck leaves the correction alone when auto is off`() {
        val result = analyse(history(trend = trend(0.0))) as CalibrationResult.Bias
        val current = CalibrationState(expenditureFactor = 0.97, intakeFactor = 1.02)
        val next = Calibration.nextFactors(current, result, autoEnabled = false)
        assertEquals(0.97, next.expenditure, 0.0001)
        assertEquals(1.02, next.intake, 0.0001)
    }

    @Test
    fun `a recheck eases the correction along when auto is on`() {
        val result = analyse(history(trend = trend(0.0))) as CalibrationResult.Bias
        val next = Calibration.nextFactors(CalibrationState(), result, autoEnabled = true)
        // A quarter of the way from 1.0 towards the suggestion, not all of it.
        assertEquals(
            Calibration.ease(1.0, result.suggestedExpenditureFactor),
            next.expenditure,
            0.0001,
        )
        assertTrue(next.expenditure < 1.0)
        assertTrue(next.expenditure > result.suggestedExpenditureFactor)
    }

    @Test
    fun `repeated daily rechecks converge and then hold steady`() {
        val result = analyse(history(trend = trend(0.0))) as CalibrationResult.Bias
        var state = CalibrationState()
        repeat(30) {
            val next = Calibration.nextFactors(state, result, autoEnabled = true)
            state = state.copy(expenditureFactor = next.expenditure, intakeFactor = next.intake)
        }
        assertEquals(result.suggestedExpenditureFactor, state.expenditureFactor, 0.001)
        // Another run must not push it past the target.
        val settled = Calibration.nextFactors(state, result, autoEnabled = true)
        assertEquals(state.expenditureFactor, settled.expenditure, 0.001)
    }

    @Test
    fun `agreement leaves a working correction in place`() {
        // Undoing the correction because it is working would put the bias straight back.
        val agrees = CalibrationResult.Agrees(10.0)
        val current = CalibrationState(expenditureFactor = 0.96, intakeFactor = 1.01)
        val next = Calibration.nextFactors(current, agrees, autoEnabled = true)
        assertEquals(0.96, next.expenditure, 0.0001)
        assertEquals(1.01, next.intake, 0.0001)
    }

    @Test
    fun `not enough data never moves the correction`() {
        val gap = CalibrationResult.NotEnoughData(CalibrationGap.TOO_FEW_DAYS)
        val current = CalibrationState(expenditureFactor = 0.96, intakeFactor = 1.01)
        val next = Calibration.nextFactors(current, gap, autoEnabled = true)
        assertEquals(0.96, next.expenditure, 0.0001)
        assertEquals(1.01, next.intake, 0.0001)
    }

    @Test
    fun `every gap has something to say to the user`() {
        CalibrationGap.entries.forEach { gap ->
            assertTrue(gap.name, Calibration.describeGap(gap).length > 20)
        }
    }
}
