package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.weight.WeightTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp

/**
 * The complaint: on the divergence chart the measured line visibly trailed the readings.
 * It was the causal EMA, which lags by construction. Looking back, both sides of a point
 * are known, so there is no reason to use only one.
 */
class CentredSmoothingTest {

    private val start = LocalDate.of(2026, 8, 1)

    /** A clean downward line, one reading a day. */
    private fun steadyLoss(days: Int = 40, perDay: Double = -0.07) =
        (0 until days).map { start.plusDays(it.toLong()) to 95.0 + perDay * it }

    @Test
    fun `the causal trend lags a steady loss and the centred one does not`() {
        val trend = WeightTrend.from(steadyLoss())
        // Mid-series, where the centred smoother has readings on both sides.
        val point = trend.points[20]
        assertTrue(
            "causal ${point.trendKg} should sit above the reading ${point.rawKg}",
            point.trendKg - point.rawKg > 0.1,
        )
        assertEquals("centred should sit on the line", point.rawKg, point.centredKg, 0.03)
    }

    @Test
    fun `centred smoothing still removes noise`() {
        // Alternating half-kilo water swings around a flat trend.
        val noisy = (0 until 40).map {
            start.plusDays(it.toLong()) to 90.0 + if (it % 2 == 0) 0.5 else -0.5
        }
        val point = WeightTrend.from(noisy).points[20]
        assertEquals(90.0, point.centredKg, 0.1)
        assertTrue("the raw reading is the noise", abs(point.rawKg - 90.0) > 0.4)
    }

    @Test
    fun `the newest point sits on the line rather than above it`() {
        // The reported bug, and the reason this is a local line and not a local mean.
        val last = WeightTrend.from(steadyLoss()).points.last()
        assertEquals("edge estimate drifted off the line", last.rawKg, last.centredKg, 0.05)
    }

    @Test
    fun `the oldest point too, since both ends are one-sided`() {
        val first = WeightTrend.from(steadyLoss()).points.first()
        assertEquals(first.rawKg, first.centredKg, 0.05)
    }

    @Test
    fun `a local mean would have failed that, which is why it is not used`() {
        // Kept as the counter-example so the choice is not mistaken for decoration.
        val readings = steadyLoss()
        val target = readings.last().first
        var weighted = 0.0
        var total = 0.0
        readings.forEach { (date, kg) ->
            val gap = ChronoUnit.DAYS.between(target, date).toDouble()
            if (abs(gap) <= WeightTrend.CENTRED_REACH_DAYS) {
                val sigma = WeightTrend.CENTRED_SIGMA_DAYS
                val w = exp(-(gap * gap) / (2 * sigma * sigma))
                weighted += w * kg
                total += w
            }
        }
        val drift = weighted / total - readings.last().second
        assertTrue("a local mean should read high; it was off by $drift", drift > 0.1)
    }

    @Test
    fun `a single reading smooths to itself`() {
        val trend = WeightTrend.from(listOf(start to 88.0))
        assertEquals(88.0, trend.points.single().centredKg, 0.0001)
    }

    @Test
    fun `irregular weigh-ins are weighted by date, not by position`() {
        // Three readings close together then a long gap. A positional window would let
        // the distant reading pull the cluster; a date-based one barely feels it.
        val readings = listOf(
            start to 90.0,
            start.plusDays(1) to 90.0,
            start.plusDays(2) to 90.0,
            start.plusDays(40) to 80.0,
        )
        val cluster = WeightTrend.from(readings).points[1]
        assertEquals(90.0, cluster.centredKg, 0.05)
    }
}

/**
 * The lag was never confined to a chart line. currentTrendKg feeds BMI, the goal's "to
 * go", the milestone test, the forecast's starting point and the widget, so a trend
 * reading high meant every one of those was too.
 */
class CurrentTrendTest {

    private val start = LocalDate.of(2026, 8, 1)

    private fun steadyLoss(days: Int = 40, perDay: Double = -0.07) =
        (0 until days).map { start.plusDays(it.toLong()) to 95.0 + perDay * it }

    @Test
    fun `the reported trend weight is not above the scale during a steady loss`() {
        val readings = steadyLoss()
        val trend = WeightTrend.from(readings)
        val latest = readings.last().second
        assertEquals("trend weight drifted off the scale", latest, trend.currentTrendKg!!, 0.05)
    }

    @Test
    fun `the causal value is the one that would have read high`() {
        val readings = steadyLoss()
        val points = WeightTrend.from(readings).points
        val drift = points.last().trendKg - readings.last().second
        assertTrue("the EMA should read high; it was off by $drift", drift > 0.1)
        assertTrue(
            "and the reported figure should not inherit that",
            abs(points.last().centredKg - readings.last().second) < drift / 2,
        )
    }

    @Test
    fun `the measured rate is the real one, not the EMA's shallower version`() {
        // -0.07 kg a day is -0.49 a week, and that is what should be reported.
        val readings = steadyLoss()
        val trend = WeightTrend.from(readings)
        assertEquals(-0.49, trend.weeklyChangeKg!!, 0.02)

        // The EMA's lag is not constant: it starts at the first reading and converges,
        // which tilts a line fitted through it. Fitting the causal series understated
        // this loss by about a fifth, and that figure is what calibration compares the
        // calories against -- so the error landed straight in the reported bias.
        val origin = readings.first().first
        val xs = trend.points.map {
            ChronoUnit.DAYS.between(origin, it.date).toDouble()
        }
        val ys = trend.points.map { it.trendKg }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        xs.indices.forEach { i ->
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        val causalSlope = num / den * 7.0
        assertTrue(
            "the causal slope should be shallower; it was $causalSlope",
            causalSlope > trend.weeklyChangeKg!! + 0.05,
        )
    }

    @Test
    fun `a gain reads low under the causal trend and correct under the reported one`() {
        val gaining = (0 until 40).map { start.plusDays(it.toLong()) to 70.0 + 0.05 * it }
        val trend = WeightTrend.from(gaining)
        val latest = gaining.last().second
        assertTrue("the EMA should read low", trend.points.last().trendKg < latest - 0.1)
        assertEquals(latest, trend.currentTrendKg!!, 0.05)
    }
}
