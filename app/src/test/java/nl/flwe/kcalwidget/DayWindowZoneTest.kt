package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.DayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A 1286 kcal quick log at 00:22 was invisible to a window of 00:00 to now.
 *
 * Health Connect's local-time filter matches the local time a record carries, which is
 * only our wall clock when the writer recorded a zone offset. This one had not, so the
 * record sat at 22:22Z and a local 00:00-to-now filter asked, in effect, for 02:00 to
 * 02:46. Every range the app sends is now an instant, which has no such ambiguity.
 */
class DayWindowZoneTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    @Test
    fun `a local wall clock time becomes the moment it means here, not the same digits in UTC`() {
        val midnight = LocalDateTime.of(2026, 10, 7, 0, 0)
        val instant = DayWindow.instantOf(midnight, amsterdam)

        // Central European Summer Time in early October: two hours ahead.
        assertEquals("2026-10-06T22:00:00Z", instant.toString())
        assertNotEquals(midnight.toInstant(ZoneOffset.UTC), instant)
    }

    @Test
    fun `the record that started this falls inside the window it belongs to`() {
        val dayStart = DayWindow.instantOf(LocalDateTime.of(2026, 10, 7, 0, 0), amsterdam)
        val now = DayWindow.instantOf(LocalDateTime.of(2026, 10, 7, 0, 46), amsterdam)
        val quickLog = java.time.Instant.parse("2026-10-06T22:22:00Z")

        assertEquals(true, !quickLog.isBefore(dayStart) && quickLog.isBefore(now))
    }

    @Test
    fun `and would have fallen outside it when the digits were read as UTC`() {
        // The counter-example, so the fix is not mistaken for decoration.
        val naiveStart = LocalDateTime.of(2026, 10, 7, 0, 0).toInstant(ZoneOffset.UTC)
        val quickLog = java.time.Instant.parse("2026-10-06T22:22:00Z")
        assertEquals(true, quickLog.isBefore(naiveStart))
    }

    @Test
    fun `a zone behind UTC shifts the other way`() {
        val newYork = ZoneId.of("America/New_York")
        val instant = DayWindow.instantOf(LocalDateTime.of(2026, 10, 7, 0, 0), newYork)
        assertEquals("2026-10-07T04:00:00Z", instant.toString())
    }

    @Test
    fun `a configured day boundary moves with the clock, not against it`() {
        val fourAm = DayWindow.instantOf(LocalDateTime.of(2026, 10, 7, 4, 0), amsterdam)
        val midnight = DayWindow.instantOf(LocalDateTime.of(2026, 10, 7, 0, 0), amsterdam)
        assertEquals(4 * 3600L, fourAm.epochSecond - midnight.epochSecond)
    }
}
