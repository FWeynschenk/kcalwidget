package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.history.HistoryRepository
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.GoalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * The complaint this exists to answer: one exceptional day handed out several hundred
 * kcal of room all week, then took every last one back overnight when it left the
 * six-day window. Eat into the room and the swing doubles -- "you have spare" becomes
 * "you are 700 over" in a single step.
 */
class CarrySmoothingTest {

    private val start = LocalDate.of(2026, 9, 1)
    private val maintaining = AppSettings(goal = GoalSettings(weeklyChangeKg = 0.0))

    /** Days ending on [lastDay], each with the given net left over (burn + goal - intake). */
    private fun days(leftOver: List<Double>, lastDay: LocalDate = start.plusDays(20)) =
        leftOver.mapIndexed { i, over ->
            val date = lastDay.minusDays((leftOver.size - 1 - i).toLong())
            DayRow(date, 2000.0, 2000.0 + over, null)
        }

    private fun carry(rows: List<DayRow>) =
        HistoryRepository.bankedCarry(rows, maintaining).totalKcal

    @Test
    fun `a day leaving the window is never a cliff`() {
        // One big day, then ordinary ones. Walk forward and watch what the carry does.
        val big = 1500.0
        val readings = (0..9).map { after ->
            val leftOver = listOf(big) + List(after) { 0.0 }
            carry(days(leftOver, lastDay = start.plusDays(after.toLong())))
        }
        val steps = readings.zipWithNext { a, b -> abs(b - a) }
        val worst = steps.max()
        assertTrue("carry jumped $worst kcal in one day", worst < 200.0)
    }

    @Test
    fun `no single day can dominate the carry`() {
        // A 3000 kcal day is clipped: the carry it produces is nothing like 3000.
        val huge = carry(days(listOf(3000.0)))
        assertTrue("one day produced $huge kcal of room", huge <= HistoryRepository.MAX_DAY_CONTRIBUTION_KCAL)
    }

    @Test
    fun `clipping is symmetric, so a blow-out is forgiven on a fast's terms`() {
        assertEquals(-carry(days(listOf(3000.0))), carry(days(listOf(-3000.0))), 0.001)
    }

    @Test
    fun `yesterday counts fully and the far edge barely counts`() {
        assertEquals(1.0, HistoryRepository.ageWeight(1), 0.001)
        assertEquals(0.5, HistoryRepository.ageWeight(5), 0.001)
        assertEquals(0.25, HistoryRepository.ageWeight(9), 0.001)
        // The oldest day in the window, whose departure must not be felt.
        val edge = HistoryRepository.ageWeight(HistoryRepository.CARRY_WINDOW_DAYS.toLong())
        assertTrue("edge weight was $edge", edge < 0.12)
        val worstStep = edge * HistoryRepository.MAX_DAY_CONTRIBUTION_KCAL
        assertTrue("a day falling out could move the carry $worstStep kcal", worstStep < 80.0)
    }

    @Test
    fun `a steady week still reads about six days' worth, as the old window did`() {
        // The scale has to stay familiar or every budget shifts for no stated reason.
        val steady = carry(days(List(21) { 100.0 }))
        assertEquals(600.0, steady, 60.0)
    }

    @Test
    fun `sparse logging shrinks the carry rather than being scaled up to fill the window`() {
        val full = carry(days(List(21) { 100.0 }))
        val sparse = HistoryRepository.bankedCarry(
            days(List(21) { 100.0 }).filterIndexed { i, _ -> i % 3 == 0 },
            maintaining,
        ).totalKcal
        assertTrue("sparse $sparse should be well under full $full", sparse < full * 0.6)
    }

    @Test
    fun `eating into the spare drains it gradually instead of flipping`() {
        // The reported scenario, played out: one big day, then 100 kcal a day too much.
        val big = 1500.0
        val readings = (0..9).map { after ->
            val leftOver = listOf(big) + List(after) { -100.0 }
            carry(days(leftOver, lastDay = start.plusDays(after.toLong())))
        }
        val worst = readings.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("carry jumped $worst kcal in one day", worst < 200.0)
        // And it does land in debt eventually, because the overeating was real.
        assertTrue("sustained overeating should still show up", readings.last() < readings.first())
    }

    @Test
    fun `the old rectangular window would have failed the cliff test`() {
        // Kept as the counter-example, so the fix is not mistaken for decoration. Six days
        // at full weight and then nothing: the drop is the whole day.
        val window = 6
        val big = 1500.0
        fun rectangular(after: Int): Double {
            val contributions = (listOf(big) + List(after) { 0.0 }).takeLast(window)
            return contributions.sum().coerceIn(-700.0, 700.0)
        }
        val steps = (0..9).map { rectangular(it) }.zipWithNext { a, b -> abs(b - a) }
        assertTrue("the old scheme should show a cliff, worst was ${steps.max()}", steps.max() > 600.0)
    }
}
