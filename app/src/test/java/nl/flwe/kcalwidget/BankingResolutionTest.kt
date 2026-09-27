package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.history.Banking
import nl.flwe.kcalwidget.data.history.BankedCarry
import nl.flwe.kcalwidget.data.history.CarryDay
import nl.flwe.kcalwidget.data.history.CarryState
import nl.flwe.kcalwidget.data.settings.BankingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The bug this exists to prevent: a Health Connect read that came back short produced an
 * empty carry, which was read as a week that came out even, which cancelled a 700 kcal
 * penalty mid-afternoon. On screen it looked like a short walk had been worth a thousand
 * calories.
 */
class BankingResolutionTest {

    private val today = LocalDate.of(2026, 9, 27)

    private fun realCarry(total: Double) = BankedCarry(
        days = listOf(CarryDay(today.minusDays(1), 2500.0, 2500.0 - total, -0.0)),
        rawTotalKcal = total,
        totalKcal = total,
    )

    private val nothingRemembered = BankingState()
    private val remembered = BankingState(-700.0, today.toEpochDay())

    @Test
    fun `a real carry is used as read`() {
        val carry = Banking.resolve(realCarry(-650.0), nothingRemembered, today, enabled = true)
        assertEquals(CarryState.FRESH, carry.state)
        assertEquals(-650.0, carry.kcal, 0.001)
    }

    @Test
    fun `an unreadable week falls back to the last one read, not to zero`() {
        val carry = Banking.resolve(BankedCarry.NONE, remembered, today, enabled = true)
        assertEquals(CarryState.STALE, carry.state)
        assertEquals(-700.0, carry.kcal, 0.001)
        assertTrue("a remembered carry still applies", carry.applies)
    }

    @Test
    fun `a null history is treated the same as an empty one`() {
        assertEquals(
            CarryState.STALE,
            Banking.resolve(null, remembered, today, enabled = true).state,
        )
    }

    @Test
    fun `a carry too old to describe this window is not used`() {
        val ancient = BankingState(-700.0, today.minusDays(9).toEpochDay())
        val carry = Banking.resolve(BankedCarry.NONE, ancient, today, enabled = true)
        assertEquals(CarryState.UNAVAILABLE, carry.state)
        assertEquals(0.0, carry.kcal, 0.001)
        assertFalse("an unknown carry must not be applied as zero", carry.applies)
    }

    @Test
    fun `an unavailable carry is distinguishable from a week that came out even`() {
        val even = Banking.resolve(realCarry(0.0), nothingRemembered, today, enabled = true)
        val missing = Banking.resolve(BankedCarry.NONE, nothingRemembered, today, enabled = true)
        assertEquals(0.0, even.kcal, 0.001)
        assertEquals(0.0, missing.kcal, 0.001)
        // Same number, different claim. Conflating them is the whole bug.
        assertEquals(CarryState.FRESH, even.state)
        assertEquals(CarryState.UNAVAILABLE, missing.state)
    }

    @Test
    fun `banking off short-circuits everything`() {
        val carry = Banking.resolve(realCarry(-650.0), remembered, today, enabled = false)
        assertEquals(CarryState.OFF, carry.state)
        assertEquals(0.0, carry.kcal, 0.001)
        assertFalse(carry.applies)
    }

    @Test
    fun `a carry remembered from yesterday still stands in`() {
        val yesterday = BankingState(-420.0, today.minusDays(1).toEpochDay())
        val carry = Banking.resolve(null, yesterday, today, enabled = true)
        assertEquals(CarryState.STALE, carry.state)
        assertEquals(-420.0, carry.kcal, 0.001)
    }

    @Test
    fun `a carry stamped in the future is not trusted`() {
        val ahead = BankingState(-700.0, today.plusDays(3).toEpochDay())
        assertEquals(
            CarryState.UNAVAILABLE,
            Banking.resolve(null, ahead, today, enabled = true).state,
        )
    }
}
