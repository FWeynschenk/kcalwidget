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
