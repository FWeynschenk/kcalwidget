package nl.flwe.kcalwidget.data

import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Where "today" starts and stops.
 *
 * With a day boundary of 0 this is plain local midnight. With a boundary of, say, 4 the
 * day runs 04:00 to 04:00, so a late dinner is charged to the day it was eaten rather than
 * to tomorrow's budget.
 *
 * Everything that builds a [TimeRangeFilter] or measures elapsed time goes through here,
 * because a boundary applied in some places and not others is worse than no boundary.
 */
object DayWindow {

    /** Start of the logical day that [now] falls in. */
    fun currentStart(dayStartHour: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val candidate = now.toLocalDate().atStartOfDay().plusHours(dayStartHour.toLong())
        return if (now.isBefore(candidate)) candidate.minusDays(1) else candidate
    }

    /** Start of the logical day that begins on [date]. */
    fun startOf(date: LocalDate, dayStartHour: Int): LocalDateTime =
        date.atStartOfDay().plusHours(dayStartHour.toLong())

    /** How much of the logical day has passed, capped at a full day. */
    fun elapsed(dayStartHour: Int, now: LocalDateTime = LocalDateTime.now()): Duration {
        val elapsed = Duration.between(currentStart(dayStartHour, now), now)
        return if (elapsed.isNegative) Duration.ZERO else elapsed.coerceAtMost(Duration.ofDays(1))
    }

    /** Midnight-to-now, in the configured frame. */
    fun todayRange(dayStartHour: Int, now: LocalDateTime = LocalDateTime.now()): TimeRangeFilter =
        TimeRangeFilter.between(currentStart(dayStartHour, now), now)

    /** The [days] complete logical days before the current one. */
    fun recentCompleteDays(
        days: Int,
        dayStartHour: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ): TimeRangeFilter {
        val end = currentStart(dayStartHour, now)
        return TimeRangeFilter.between(end.minusDays(days.toLong()), end)
    }

    /**
     * Hours since the logical day started, which is the frame the learned intraday curve
     * is indexed in. Always 0..24 regardless of where the boundary sits.
     */
    fun hoursInto(dayStartHour: Int, moment: LocalDateTime): Double {
        val start = currentStart(dayStartHour, moment)
        return Duration.between(start, moment).seconds / 3600.0
    }
}
