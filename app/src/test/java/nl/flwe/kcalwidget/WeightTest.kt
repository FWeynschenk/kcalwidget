package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.settings.GoalDirection
import nl.flwe.kcalwidget.data.weight.Bmi
import nl.flwe.kcalwidget.data.weight.BmiBand
import nl.flwe.kcalwidget.data.weight.WeightGoal
import nl.flwe.kcalwidget.data.weight.WeightTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class WeightTest {

    private val start = LocalDate.of(2026, 6, 1)
    private val height = 180

    // --- BMI ---------------------------------------------------------------------------

    @Test
    fun `bmi and its inverse agree`() {
        val bmi = Bmi.value(81.0, height)
        assertEquals(25.0, bmi, 0.01)
        assertEquals(81.0, Bmi.weightForBmi(bmi, height), 0.01)
    }

    @Test
    fun `bands land on the right side of each boundary`() {
        assertEquals(BmiBand.UNDERWEIGHT, Bmi.bandFor(18.4))
        assertEquals(BmiBand.HEALTHY, Bmi.bandFor(18.5))
        assertEquals(BmiBand.HEALTHY, Bmi.bandFor(24.9))
        assertEquals(BmiBand.OVERWEIGHT, Bmi.bandFor(25.0))
        assertEquals(BmiBand.OBESE_I, Bmi.bandFor(30.0))
        assertEquals(BmiBand.OBESE_II, Bmi.bandFor(35.0))
        assertEquals(BmiBand.OBESE_III, Bmi.bandFor(41.0))
    }

    @Test
    fun `a nonsense height does not divide by zero`() {
        assertEquals(0.0, Bmi.value(80.0, 0), 0.001)
    }

    // --- trend -------------------------------------------------------------------------

    @Test
    fun `smoothing damps a single odd reading`() {
        val readings = (0..20).map { start.plusDays(it.toLong()) to 80.0 } +
            listOf(start.plusDays(21) to 84.0)
        val trend = WeightTrend.from(readings)
        // A 4 kg jump is mostly water; the trend should barely twitch.
        assertTrue(trend.currentTrendKg!! < 80.5)
        assertEquals(84.0, trend.points.last().rawKg, 0.001)
    }

    @Test
    fun `the slope recovers a steady loss`() {
        val readings = (0..89).map { start.plusDays(it.toLong()) to 90.0 - it * 0.1 }
        val trend = WeightTrend.from(readings)
        assertEquals(-0.7, trend.weeklyChangeKg!!, 0.1)
        assertTrue(trend.hasEnoughForTrend)
    }

    @Test
    fun `the slope recovers a steady gain`() {
        val readings = (0..89).map { start.plusDays(it.toLong()) to 55.0 + it * 0.05 }
        val trend = WeightTrend.from(readings)
        assertEquals(0.35, trend.weeklyChangeKg!!, 0.1)
    }

    @Test
    fun `a longer gap moves the trend further than a daily reading would`() {
        val daily = WeightTrend.from(listOf(start to 80.0, start.plusDays(1) to 85.0))
        val gapped = WeightTrend.from(listOf(start to 80.0, start.plusDays(30) to 85.0))
        assertTrue(
            "a month of drift should carry more weight than one day",
            gapped.currentTrendKg!! > daily.currentTrendKg!!,
        )
    }

    @Test
    fun `several readings in one day are averaged`() {
        val trend = WeightTrend.from(listOf(start to 80.0, start to 82.0))
        assertEquals(81.0, trend.points.single().rawKg, 0.001)
        assertEquals(1, trend.weighInDays)
    }

    @Test
    fun `too few points give no slope rather than a fake one`() {
        assertNull(WeightTrend.from(listOf(start to 80.0)).weeklyChangeKg)
        assertNull(WeightTrend.from(listOf(start to 80.0, start.plusDays(1) to 79.0)).weeklyChangeKg)
    }

    @Test
    fun `no readings is handled, not crashed`() {
        val empty = WeightTrend.from(emptyList())
        assertNull(empty.currentTrendKg)
        assertNull(empty.lastWeighIn)
        assertEquals(0, empty.weighInDays)
    }

    // --- goals -------------------------------------------------------------------------

    @Test
    fun `someone with obesity is offered the next band down first`() {
        val current = Bmi.weightForBmi(34.0, height)
        val presets = WeightGoal.presets(current, height)
        assertTrue(presets.isNotEmpty())
        // The nearest milestone, not a distant ideal.
        assertEquals(Bmi.weightForBmi(30.0, height), presets.first().targetKg, 0.1)
        assertTrue("every target must be a loss", presets.all { it.targetKg < current })
    }

    @Test
    fun `someone underweight is offered targets to gain towards`() {
        val current = Bmi.weightForBmi(17.0, height)
        val presets = WeightGoal.presets(current, height)
        assertTrue(presets.isNotEmpty())
        assertTrue("every target must be a gain", presets.all { it.targetKg > current })
        assertEquals(Bmi.weightForBmi(18.5, height), presets.first().targetKg, 0.1)
    }

    @Test
    fun `someone already healthy is offered maintenance`() {
        val current = Bmi.weightForBmi(22.0, height)
        val presets = WeightGoal.presets(current, height)
        assertTrue(presets.any { abs(it.targetKg - current) < 0.5 })
    }

    @Test
    fun `the suggested rate points the right way and stays sane`() {
        assertTrue(WeightGoal.suggestedWeeklyRate(93.0, GoalDirection.LOSE) < 0)
        assertTrue(WeightGoal.suggestedWeeklyRate(55.0, GoalDirection.GAIN) > 0)
        assertEquals(0.0, WeightGoal.suggestedWeeklyRate(80.0, GoalDirection.MAINTAIN), 0.001)
        assertTrue(abs(WeightGoal.suggestedWeeklyRate(200.0, GoalDirection.LOSE)) <= 0.75)
        assertTrue(abs(WeightGoal.suggestedWeeklyRate(40.0, GoalDirection.LOSE)) >= 0.25)
    }

    @Test
    fun `a rate past one percent of body weight is flagged`() {
        assertTrue(WeightGoal.isAggressive(-1.0, 93.0))
        assertTrue(!WeightGoal.isAggressive(-0.5, 93.0))
        // The same rate is fine for a heavier person and steep for a lighter one.
        assertTrue(!WeightGoal.isAggressive(-1.0, 120.0))
        assertTrue(WeightGoal.isAggressive(-0.8, 60.0))
    }

    @Test
    fun `a target below the healthy range is flagged in either direction of travel`() {
        assertTrue(WeightGoal.isBelowHealthy(Bmi.weightForBmi(17.0, height), height))
        assertTrue(!WeightGoal.isBelowHealthy(Bmi.weightForBmi(19.0, height), height))
    }

    @Test
    fun `reaching a target works for losing and for gaining`() {
        assertTrue(WeightGoal.isReached(74.9, 75.0, GoalDirection.LOSE))
        assertTrue(!WeightGoal.isReached(75.1, 75.0, GoalDirection.LOSE))
        assertTrue(WeightGoal.isReached(60.1, 60.0, GoalDirection.GAIN))
        assertTrue(!WeightGoal.isReached(59.9, 60.0, GoalDirection.GAIN))
    }

    @Test
    fun `time to target is only reported when heading towards it`() {
        assertEquals(10.0, WeightGoal.weeksToTarget(90.0, 85.0, -0.5)!!, 0.01)
        // Losing weight with a gaining target will never arrive.
        assertNull(WeightGoal.weeksToTarget(90.0, 85.0, 0.5))
        assertNull(WeightGoal.weeksToTarget(90.0, 85.0, 0.0))
    }
}
