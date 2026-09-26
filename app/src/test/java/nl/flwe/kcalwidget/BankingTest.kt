package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.history.HistoryRepository
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.CalibrationState
import nl.flwe.kcalwidget.data.settings.FeatureFlags
import nl.flwe.kcalwidget.data.settings.GoalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BankingTest {

    private val start = LocalDate.of(2026, 9, 1)

    /** Six days of eating 2000 against burning 2500. */
    private fun week(intake: Double? = 2000.0, burn: Double? = 2500.0) =
        (0 until 6).map { DayRow(start.plusDays(it.toLong()), intake, burn, null) }

    private val maintaining = AppSettings(goal = GoalSettings(weeklyChangeKg = 0.0))

    @Test
    fun `a week of deficit banks a surplus for today`() {
        val banked = HistoryRepository.bankedAdjustment(week(), maintaining)
        // 500 a day under maintenance, six days, capped at 700.
        assertEquals(HistoryRepository.MAX_BANKED_KCAL, banked, 0.001)
    }

    @Test
    fun `the carry is capped in both directions`() {
        val over = HistoryRepository.bankedAdjustment(week(intake = 4000.0), maintaining)
        assertEquals(-HistoryRepository.MAX_BANKED_KCAL, over, 0.001)
    }

    @Test
    fun `days without logged food are skipped, not read as a fast`() {
        assertEquals(0.0, HistoryRepository.bankedAdjustment(week(intake = null), maintaining), 0.001)
    }

    @Test
    fun `the carry is calibrated, because it lands in a calibrated budget`() {
        // Two scales in one budget would bias the whole week. One day, to stay under the cap.
        val oneDay = listOf(DayRow(start, 2000.0, 2500.0, null))
        val plain = HistoryRepository.bankedAdjustment(oneDay, maintaining)
        assertEquals(500.0, plain, 0.001)

        val calibrated = maintaining.copy(
            features = FeatureFlags(autoCalibration = true),
            calibration = CalibrationState(expenditureFactor = 0.9, intakeFactor = 1.1),
        )
        // 2500 x 0.9 - 2000 x 1.1 = 2250 - 2200.
        assertEquals(50.0, HistoryRepository.bankedAdjustment(oneDay, calibrated), 0.001)
        assertTrue(HistoryRepository.bankedAdjustment(oneDay, calibrated) < plain)
    }

    @Test
    fun `calibration is ignored while auto is off`() {
        val advisory = maintaining.copy(
            calibration = CalibrationState(expenditureFactor = 0.9, intakeFactor = 1.1),
        )
        val oneDay = listOf(DayRow(start, 2000.0, 2500.0, null))
        assertEquals(500.0, HistoryRepository.bankedAdjustment(oneDay, advisory), 0.001)
    }
}

class CarryBreakdownTest {

    private val start = LocalDate.of(2026, 9, 1)
    private val losing = AppSettings(goal = GoalSettings(weeklyChangeKg = -0.5))

    @Test
    fun `the carry can show the days it came from`() {
        val rows = (0 until 3).map { DayRow(start.plusDays(it.toLong()), 2000.0, 2400.0, null) }
        val carry = HistoryRepository.bankedCarry(rows, losing)

        assertEquals(3, carry.days.size)
        assertEquals(rows.map { it.date }, carry.days.map { it.date })
        // Each day must account for itself, and the days must account for the total.
        assertEquals(carry.totalKcal, carry.days.sumOf { it.kcal }, 0.001)
    }

    @Test
    fun `a day's contribution is its burn plus the goal, less what was eaten`() {
        val delta = losing.goal.dailyEnergyDelta
        val carry = HistoryRepository.bankedCarry(
            listOf(DayRow(start, 2000.0, 2400.0, null)),
            losing,
        )
        assertEquals(2400.0 + delta - 2000.0, carry.days.single().kcal, 0.001)
    }

    @Test
    fun `the cap is visible, not silent`() {
        // Ten days of 500 over would be 5000; the cap has to announce itself or the
        // breakdown will not add up to the total and look like a bug.
        val rows = (0 until 10).map { DayRow(start.plusDays(it.toLong()), 1000.0, 2000.0, null) }
        val carry = HistoryRepository.bankedCarry(rows, AppSettings())

        assertTrue("the cap should have bitten", carry.capped)
        assertEquals(HistoryRepository.MAX_BANKED_KCAL, carry.totalKcal, 0.001)
        assertTrue("the raw total is what the days actually add to", carry.rawTotalKcal > carry.totalKcal)
    }

    @Test
    fun `an ordinary carry is not flagged as capped`() {
        val carry = HistoryRepository.bankedCarry(
            listOf(DayRow(start, 2000.0, 2100.0, null)),
            AppSettings(),
        )
        assertTrue(!carry.capped)
        assertEquals(carry.rawTotalKcal, carry.totalKcal, 0.001)
    }

    @Test
    fun `no usable days carries nothing at all`() {
        val carry = HistoryRepository.bankedCarry(emptyList(), losing)
        assertEquals(0.0, carry.totalKcal, 0.001)
        assertTrue(carry.days.isEmpty())
    }
}
