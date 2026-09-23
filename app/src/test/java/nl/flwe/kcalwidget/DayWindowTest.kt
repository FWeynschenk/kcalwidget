package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.DayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class DayWindowTest {

    private val day = LocalDate.of(2026, 9, 23)

    @Test
    fun `a midnight boundary is the plain calendar day`() {
        val noon = day.atTime(12, 0)
        assertEquals(day.atStartOfDay(), DayWindow.currentStart(0, noon))
        assertEquals(Duration.ofHours(12), DayWindow.elapsed(0, noon))
    }

    @Test
    fun `before the boundary still belongs to yesterday`() {
        // 02:00 with a 04:00 boundary is the small hours of the previous logical day.
        val lateNight = day.atTime(2, 0)
        assertEquals(
            day.minusDays(1).atTime(4, 0),
            DayWindow.currentStart(4, lateNight),
        )
        assertEquals(Duration.ofHours(22), DayWindow.elapsed(4, lateNight))
    }

    @Test
    fun `after the boundary starts the new day`() {
        val morning = day.atTime(10, 0)
        assertEquals(day.atTime(4, 0), DayWindow.currentStart(4, morning))
        assertEquals(Duration.ofHours(6), DayWindow.elapsed(4, morning))
    }

    @Test
    fun `right on the boundary starts the new day`() {
        val onIt = day.atTime(4, 0)
        assertEquals(day.atTime(4, 0), DayWindow.currentStart(4, onIt))
        assertEquals(Duration.ZERO, DayWindow.elapsed(4, onIt))
    }

    @Test
    fun `elapsed is never negative and never more than a day`() {
        for (boundary in 0..8) {
            for (hour in 0..23) {
                val elapsed = DayWindow.elapsed(boundary, day.atTime(hour, 30))
                assertTrue("boundary $boundary hour $hour", !elapsed.isNegative)
                assertTrue("boundary $boundary hour $hour", elapsed <= Duration.ofDays(1))
            }
        }
    }

    @Test
    fun `hours into the day are measured from the boundary, not from midnight`() {
        assertEquals(6.5, DayWindow.hoursInto(4, day.atTime(10, 30)), 0.001)
        assertEquals(10.5, DayWindow.hoursInto(0, day.atTime(10, 30)), 0.001)
        // Wrapping past midnight keeps counting rather than resetting.
        assertEquals(21.0, DayWindow.hoursInto(4, day.plusDays(1).atTime(1, 0)), 0.001)
    }

    @Test
    fun `a logical day is exactly 24 hours wide`() {
        val start = DayWindow.currentStart(4, day.atTime(10, 0))
        val nextStart = DayWindow.currentStart(4, LocalDateTime.of(day.plusDays(1), start.toLocalTime()))
        assertEquals(Duration.ofDays(1), Duration.between(start, nextStart))
    }

    @Test
    fun `startOf pins a named day to its boundary`() {
        assertEquals(day.atTime(4, 0), DayWindow.startOf(day, 4))
        assertEquals(day.atStartOfDay(), DayWindow.startOf(day, 0))
    }
}
