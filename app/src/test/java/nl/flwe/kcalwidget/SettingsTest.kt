package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.BodyProfile
import nl.flwe.kcalwidget.data.settings.GoalDirection
import nl.flwe.kcalwidget.data.settings.GoalSettings
import nl.flwe.kcalwidget.data.settings.HealthMetric
import nl.flwe.kcalwidget.data.settings.SourceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SettingsTest {

    @Test
    fun `age comes from the birth year`() {
        val body = BodyProfile(birthYear = LocalDate.now().year - 42)
        assertEquals(42, body.age)
    }

    @Test
    fun `an absurd birth year is clamped rather than producing a negative age`() {
        assertTrue(BodyProfile(birthYear = 1800).age <= 110)
        assertTrue(BodyProfile(birthYear = LocalDate.now().year + 50).age >= 10)
    }

    @Test
    fun `the daily delta follows the weekly rate`() {
        assertEquals(-550.0, GoalSettings(weeklyChangeKg = -0.5).dailyEnergyDelta, 0.001)
        assertEquals(275.0, GoalSettings(weeklyChangeKg = 0.25).dailyEnergyDelta, 0.001)
        assertEquals(0.0, GoalSettings(weeklyChangeKg = 0.0).dailyEnergyDelta, 0.001)
    }

    @Test
    fun `goal direction works in both directions and tolerates being close`() {
        val losing = GoalSettings(targetWeightKg = 75.0)
        assertEquals(GoalDirection.LOSE, losing.direction(90.0))
        assertEquals(GoalDirection.GAIN, losing.direction(60.0))
        assertEquals(GoalDirection.MAINTAIN, losing.direction(75.1))
    }

    @Test
    fun `no target means no direction`() {
        assertNull(GoalSettings(targetWeightKg = null).direction(80.0))
    }

    @Test
    fun `an unset metric accepts every app`() {
        val sources = SourceSettings()
        assertTrue(sources.packagesFor(HealthMetric.TOTAL_BURN).isEmpty())
    }

    @Test
    fun `choosing a source for one metric leaves the others alone`() {
        val sources = SourceSettings().with(HealthMetric.TOTAL_BURN, setOf("com.example.tracker"))
        assertEquals(setOf("com.example.tracker"), sources.packagesFor(HealthMetric.TOTAL_BURN))
        assertTrue(sources.packagesFor(HealthMetric.NUTRITION).isEmpty())
    }

    @Test
    fun `calibration trusts logging less than expenditure until told otherwise`() {
        // Expenditure is the softer number, so an unasked user gets the benefit of the doubt.
        val fresh = AppSettings().calibration
        assertEquals(0.85, fresh.intakeTrust, 0.001)
        assertNull(fresh.loggingAccuracy)
        assertEquals(1.0, fresh.expenditureFactor, 0.001)
        assertEquals(1.0, fresh.intakeFactor, 0.001)
    }

    @Test
    fun `defaults match what was agreed`() {
        val d = AppSettings()
        assertEquals(7, d.features.weighInIntervalDays)
        assertTrue("widget nudge defaults on", d.features.widgetWeighInNudge)
        assertTrue("notification defaults off", !d.features.weighInNotification)
        assertTrue("calibration does not act by itself", !d.features.autoCalibration)
        assertEquals(0, d.calculation.dayStartHour)
    }
}
