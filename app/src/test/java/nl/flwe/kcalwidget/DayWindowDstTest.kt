package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.DayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sunday 25 October 2026 logged 72 kcal burned.
 *
 * History read fourteen days as fourteen 24-hour buckets from the first midnight. The
 * clocks went back that night, so from then on every bucket started at 23:00, the one
 * labelled 25 October was overwritten by the next, and that one was cut off an hour later
 * by the end of the range. Days are now read on their real boundaries.
 */
class DayWindowDstTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    @Test
    fun `the day the clocks go back stands alone, at its true 25 hours`() {
        val spans = DayWindow.spans(LocalDate.of(2026, 10, 12), 16, 0, amsterdam)

        assertEquals(3, spans.size)
        val (before, change, after) = spans
        assertEquals(LocalDate.of(2026, 10, 12), before.firstDay)
        assertEquals(13, before.days)
        assertTrue(before.uniform)

        assertEquals(LocalDate.of(2026, 10, 25), change.firstDay)
        assertEquals(1, change.days)
        assertFalse(change.uniform)
        assertEquals(Duration.ofHours(25), Duration.between(change.start, change.end))

        assertEquals(LocalDate.of(2026, 10, 26), after.firstDay)
        assertEquals(2, after.days)
        assertTrue(after.uniform)
    }

    @Test
    fun `every day is covered exactly once, end to end`() {
        for (hour in listOf(0, 2, 3, 4)) {
            val spans = DayWindow.spans(LocalDate.of(2026, 10, 12), 28, hour, amsterdam)
            assertEquals(28, spans.sumOf { it.days })
            spans.zipWithNext().forEach { (a, b) ->
                assertEquals(a.end, b.start)
                assertEquals(a.firstDay.plusDays(a.days.toLong()), b.firstDay)
            }
        }
    }

    @Test
    fun `buckets in a uniform run are labelled by their position, not their local clock`() {
        val span = DayWindow.spans(LocalDate.of(2026, 10, 26), 7, 0, amsterdam).single()
        assertTrue(span.uniform)
        assertEquals(LocalDate.of(2026, 10, 26), span.dayAt(span.start))
        assertEquals(LocalDate.of(2026, 10, 30), span.dayAt(span.start.plus(Duration.ofDays(4))))
    }

    @Test
    fun `the spring change is a 23 hour day of its own`() {
        val spans = DayWindow.spans(LocalDate.of(2026, 3, 23), 14, 0, amsterdam)
        val change = spans.single { !it.uniform }
        assertEquals(LocalDate.of(2026, 3, 29), change.firstDay)
        assertEquals(Duration.ofHours(23), Duration.between(change.start, change.end))
    }

    @Test
    fun `a fortnight without a change is a single read`() {
        assertEquals(1, DayWindow.spans(LocalDate.of(2026, 9, 1), 14, 4, amsterdam).size)
    }
}
