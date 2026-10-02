package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.weight.WeightTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

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
    fun `the newest point has nothing to its right, so it is not claimed to be lag-free`() {
        // It degrades towards a one-sided average rather than breaking, which is why the
        // field is documented as retrospective only.
        val trend = WeightTrend.from(steadyLoss())
        val last = trend.points.last()
        assertTrue(last.centredKg.isFinite())
        assertTrue("still within reach of the real value", abs(last.centredKg - last.rawKg) < 0.5)
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
