package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.HealthSnapshot
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.BodyProfile
import nl.flwe.kcalwidget.data.settings.GoalSettings
import nl.flwe.kcalwidget.data.settings.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

/**
 * Landing within a few dozen kcal of a budget that is itself an estimate is not a miss,
 * and calling it one is both wrong and discouraging.
 */
class OnTargetTest {

    private val halfDay = Duration.ofHours(12)

    private fun settings(band: Int = 50) = AppSettings(
        body = BodyProfile(
            sex = Sex.MALE,
            birthYear = LocalDate.now().year - 30,
            heightCm = 180,
            fallbackWeightKg = 80.0,
        ),
        goal = GoalSettings(weeklyChangeKg = -0.5, onTargetBandKcal = band),
    )

    /** Eats [intake] against a day that burns [burn] so far. */
    private fun day(intake: Double, burn: Double = 1400.0, band: Int = 50) =
        Energetics.compute(
            HealthSnapshot(
                intakeKcal = intake,
                totalBurnedKcal = burn,
                activeBurnedKcal = null,
                steps = null,
                basalKcalPerDay = null,
                weightKg = 80.0,
                lastWeighInAt = null,
                burnOrigins = emptySet(),
            ),
            settings(band),
            halfDay,
            null,
        )

    @Test
    fun `exactly on the budget is on target`() {
        val result = day(intake = 0.0)
        val onBudget = day(intake = result.budgetKcal)
        assertTrue(onBudget.isOnTarget)
        assertEquals(0.0, onBudget.remainingKcal, 1.0)
    }

    @Test
    fun `a small overshoot is on target, not over`() {
        val budget = day(intake = 0.0).budgetKcal
        val result = day(intake = budget + 40)
        assertTrue("40 kcal past a 50 kcal band should be on target", result.isOnTarget)
        assertTrue("and it is still genuinely past it", result.remainingKcal < 0)
    }

    @Test
    fun `a small undershoot is on target too, so it cuts both ways`() {
        val budget = day(intake = 0.0).budgetKcal
        assertTrue(day(intake = budget - 40).isOnTarget)
    }

    @Test
    fun `past the band it is a miss again`() {
        val budget = day(intake = 0.0).budgetKcal
        assertFalse(day(intake = budget + 60).isOnTarget)
        assertFalse(day(intake = budget - 60).isOnTarget)
    }

    @Test
    fun `the edge of the band counts as on target`() {
        val budget = day(intake = 0.0).budgetKcal
        assertTrue(day(intake = budget + 50).isOnTarget)
    }

    @Test
    fun `a band of zero restores the old always-a-verdict behaviour`() {
        val budget = day(intake = 0.0, band = 0).budgetKcal
        assertFalse(day(intake = budget + 1, band = 0).isOnTarget)
    }

    @Test
    fun `a wider band is honoured`() {
        val budget = day(intake = 0.0, band = 200).budgetKcal
        assertTrue(day(intake = budget + 150, band = 200).isOnTarget)
    }

    @Test
    fun `being far off is never on target`() {
        assertFalse(day(intake = 0.0).isOnTarget)
    }
}
