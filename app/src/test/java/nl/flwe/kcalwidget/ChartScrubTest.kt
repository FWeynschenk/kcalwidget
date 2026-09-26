package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.ui.components.ChartRange
import nl.flwe.kcalwidget.ui.components.nearestIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartScrubTest {

    /** Bars: one slot each, marker in the middle of the slot. */
    private val bars = (0 until 5).map { (it + 0.5f) / 5 }

    @Test
    fun `a touch lands on the bar under it`() {
        assertEquals(0, nearestIndex(bars, 0.05f))
        assertEquals(2, nearestIndex(bars, 0.5f))
        assertEquals(4, nearestIndex(bars, 0.95f))
    }

    @Test
    fun `dragging off either edge sticks to the end, it does not wrap or crash`() {
        assertEquals(0, nearestIndex(bars, -3f))
        assertEquals(4, nearestIndex(bars, 7f))
    }

    @Test
    fun `an empty series is a no-op rather than an exception`() {
        assertEquals(0, nearestIndex(emptyList(), 0.5f))
    }

    @Test
    fun `irregular points are picked by position, not by even slots`() {
        // Three weigh-ins clustered early and one much later: the real weight-chart case.
        // Splitting the width into four equal buckets would put most of the chart on the
        // wrong day.
        val irregular = listOf(0f, 0.04f, 0.08f, 1f)
        assertEquals(2, nearestIndex(irregular, 0.3f))
        assertEquals(3, nearestIndex(irregular, 0.6f))
        assertEquals(0, nearestIndex(irregular, 0.01f))
    }

    @Test
    fun `a single point absorbs every touch`() {
        assertEquals(0, nearestIndex(listOf(0.5f), 0f))
        assertEquals(0, nearestIndex(listOf(0.5f), 1f))
    }

    @Test
    fun `ranges are ordered shortest first, so the fallback is the smallest`() {
        assertEquals(ChartRange.WEEK, ChartRange.entries.first())
        assertEquals(listOf(7, 30, 90), ChartRange.entries.map { it.days })
    }
}
