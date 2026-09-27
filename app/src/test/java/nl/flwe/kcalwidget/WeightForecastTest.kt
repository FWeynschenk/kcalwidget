package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.CalibrationState
import nl.flwe.kcalwidget.data.settings.FeatureFlags
import nl.flwe.kcalwidget.data.settings.GoalSettings
import nl.flwe.kcalwidget.data.weight.WeightForecast
import nl.flwe.kcalwidget.data.weight.WeightPoint
import nl.flwe.kcalwidget.data.weight.WeightTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeightForecastTest {

    private val today = LocalDate.of(2026, 9, 27)

    private fun trend(rateKgPerWeek: Double?, currentKg: Double? = 92.0) = WeightTrend(
        points = listOf(WeightPoint(today, currentKg ?: 92.0, currentKg ?: 92.0)),
        currentTrendKg = currentKg,
        weeklyChangeKg = rateKgPerWeek,
        lastWeighIn = today,
        weighInDays = 1,
    )

    /** [net] kcal per day, for [days] days. */
    private fun rows(net: Double, days: Int = 21) = (0 until days).map {
        DayRow(today.minusDays((days - it).toLong()), 2000.0, 2000.0 - net, null)
    }

    private val settings = AppSettings(goal = GoalSettings(weeklyChangeKg = -0.5))

    @Test
    fun `agreeing methods give a narrow band`() {
        // -550 kcal/day is -0.5 kg/week, the same as the fitted trend.
        val forecast = WeightForecast.from(trend(-0.5), rows(-550.0), settings, today)!!
        val inFourWeeks = forecast.at(today.plusDays(28))!!
        assertEquals(90.0, inFourWeeks.midKg, 0.05)
        assertTrue("band should be tight, was ${inFourWeeks.highKg - inFourWeeks.lowKg}",
            inFourWeeks.highKg - inFourWeeks.lowKg < 0.2)
    }

    @Test
    fun `disagreeing methods widen the band rather than picking a winner`() {
        // The scale says flat; the calories say half a kilo a week.
        val forecast = WeightForecast.from(trend(0.0), rows(-550.0), settings, today)!!
        val inFourWeeks = forecast.at(today.plusDays(28))!!
        assertTrue("band should be wide", inFourWeeks.highKg - inFourWeeks.lowKg > 1.5)
        assertEquals(92.0, inFourWeeks.highKg, 0.05)
        assertNotNull(forecast.disagreementKgPerWeek)
    }

    @Test
    fun `low is always the lighter end, whichever method produced it`() {
        listOf(-550.0, 550.0).forEach { net ->
            val forecast = WeightForecast.from(trend(-0.5), rows(net), settings, today)!!
            forecast.points.forEach { assertTrue(it.lowKg <= it.highKg) }
        }
    }

    @Test
    fun `the balance rate is calibrated, since the raw one is what the scale corrected`() {
        val calibrated = settings.copy(
            features = FeatureFlags(autoCalibration = true),
            calibration = CalibrationState(expenditureFactor = 0.9, intakeFactor = 1.0),
        )
        val plain = WeightForecast.balanceRate(rows(-550.0), settings)!!
        val corrected = WeightForecast.balanceRate(rows(-550.0), calibrated)!!
        // Burn corrected downwards means a smaller deficit, so a slower loss.
        assertTrue("corrected $corrected should be slower than plain $plain", corrected > plain)
    }

    @Test
    fun `too few logged days is no balance rate at all, not a rate from three days`() {
        assertNull(WeightForecast.balanceRate(rows(-550.0, days = 4), settings))
    }

    @Test
    fun `a target ahead of you gets an arrival window`() {
        val withTarget = settings.copy(goal = settings.goal.copy(targetWeightKg = 88.0))
        val forecast = WeightForecast.from(trend(-0.5), rows(-550.0), settings = withTarget, today)!!
        val (first, last) = forecast.targetRange!!
        // 4 kg at 0.5 kg/week is 8 weeks.
        assertEquals(today.plusDays(56), first)
        assertTrue(!last.isBefore(first))
    }

    @Test
    fun `a rate pointing away from the target never arrives`() {
        val withTarget = settings.copy(goal = settings.goal.copy(targetWeightKg = 88.0))
        val forecast = WeightForecast.from(trend(0.3), rows(330.0), settings = withTarget, today)!!
        assertNull("gaining cannot reach a lighter target", forecast.targetRange)
    }

    @Test
    fun `a rate too small to be real does not produce a confident date`() {
        val withTarget = settings.copy(goal = settings.goal.copy(targetWeightKg = 88.0))
        val forecast = WeightForecast.from(trend(-0.001), emptyList(), withTarget, today)!!
        assertNull(forecast.targetRange)
    }

    @Test
    fun `a target further off than two years is not dated`() {
        val withTarget = settings.copy(goal = settings.goal.copy(targetWeightKg = 60.0))
        // 32 kg at 0.05 kg/week is over twelve years.
        val forecast = WeightForecast.from(trend(-0.05), emptyList(), withTarget, today)!!
        assertNull(forecast.targetRange)
    }

    @Test
    fun `no weight at all means no forecast, rather than a forecast from nothing`() {
        assertNull(WeightForecast.from(trend(-0.5, currentKg = null), rows(-550.0), settings, today))
    }

    @Test
    fun `one method is still a forecast, just without a band`() {
        val forecast = WeightForecast.from(trend(-0.5), emptyList(), settings, today)!!
        assertTrue(!forecast.hasBand)
        assertNull(forecast.disagreementKgPerWeek)
        val inFourWeeks = forecast.at(today.plusDays(28))!!
        assertEquals(inFourWeeks.lowKg, inFourWeeks.highKg, 0.0001)
        assertEquals(90.0, inFourWeeks.midKg, 0.05)
    }

    @Test
    fun `the forecast starts where the trend is now`() {
        val forecast = WeightForecast.from(trend(-0.5), rows(-550.0), settings, today)!!
        assertEquals(92.0, forecast.points.first().midKg, 0.0001)
        assertEquals(today, forecast.points.first().date)
    }
}
