package nl.flwe.kcalwidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import nl.flwe.kcalwidget.data.settings.AppSettings
import java.time.Instant

/**
 * What a typical day looks like for this person, learned from their own history.
 *
 * @param meanFullDayKcal mean total burn of the recent complete days that looked plausible.
 * @param cumulativeByHour 25 monotone values, 0.0 at the start of the logical day and 1.0
 *   at the end of it; index `h` is the share of a day's burn normally done `h` hours in.
 *   This is the denominator that makes early-day extrapolation honest: at 08:00 roughly a
 *   quarter of the day's energy is spent, not a third, because most of those hours were
 *   asleep.
 * @param dayStartHour the day boundary this was learned in. A curve learned against a
 *   different boundary is meaningless, so a mismatch forces a relearn.
 * @param sampleDays how many days went into it; 0 means this is the fallback, not learned.
 */
data class TdeeBaseline(
    val meanFullDayKcal: Double,
    val cumulativeByHour: List<Double>,
    val sampleDays: Int,
    val dayStartHour: Int,
    val computedAt: Instant,
) {
    val isLearned: Boolean get() = sampleDays > 0

    /** Share of a normal day's burn done by this point, interpolated within the hour. */
    fun expectedFractionAt(hoursIntoDay: Double): Double {
        val clamped = hoursIntoDay.coerceIn(0.0, 24.0)
        val low = clamped.toInt().coerceAtMost(23)
        val frac = clamped - low
        val a = cumulativeByHour[low]
        val b = cumulativeByHour[low + 1]
        return (a + (b - a) * frac).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Minimum plausible days before the learned curve is trusted over the fallback. */
        const val MIN_SAMPLE_DAYS = 3

        /** Recompute at most this often; the shape of a week does not move quickly. */
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

        /** Days of history to learn from. */
        const val WINDOW_DAYS = 14

        /**
         * Used until enough history exists. Sleep is charged at the resting rate and waking
         * hours at [WAKING_RATE] times that, which is the shape of an ordinary day without
         * claiming to know anything about this particular person.
         */
        private const val WAKING_RATE = 1.6
        private const val SLEEP_UNTIL_HOUR = 7
        private const val SLEEP_FROM_HOUR = 23

        val DEFAULT_CURVE: List<Double> = buildDefaultCurve()

        private fun buildDefaultCurve(): List<Double> {
            val weights = DoubleArray(24) { hour ->
                if (hour < SLEEP_UNTIL_HOUR || hour >= SLEEP_FROM_HOUR) 1.0 else WAKING_RATE
            }
            val total = weights.sum()
            val cumulative = DoubleArray(25)
            var acc = 0.0
            for (h in 0 until 24) {
                acc += weights[h]
                cumulative[h + 1] = acc / total
            }
            return cumulative.toList()
        }

        /** The stand-in used before any history exists. */
        fun fallback(bmrPerDayKcal: Double, dayStartHour: Int = 0): TdeeBaseline = TdeeBaseline(
            meanFullDayKcal = bmrPerDayKcal * Energetics.DEFAULT_ACTIVITY_FACTOR,
            cumulativeByHour = DEFAULT_CURVE,
            sampleDays = 0,
            dayStartHour = dayStartHour,
            computedAt = Instant.EPOCH,
        )
    }
}

private val Context.baselineStore: DataStore<Preferences> by preferencesDataStore("kcal_baseline")

/**
 * Caches the learned baseline. Relearning reads two weeks of history from Health Connect,
 * which is far too heavy to repeat on every widget refresh, so it happens twice a day.
 */
class BaselineRepository(private val context: Context) {

    suspend fun get(
        health: HealthRepository,
        settings: AppSettings,
        bmrPerDayKcal: Double,
    ): TdeeBaseline {
        val dayStartHour = settings.calculation.dayStartHour
        val cached = read()
        if (cached != null && !cached.isStale(dayStartHour)) return cached

        val fresh = runCatching { health.computeBaseline(settings, bmrPerDayKcal) }.getOrNull()
        if (fresh != null) {
            write(fresh)
            return fresh
        }
        // A failed relearn keeps the stale baseline, which still beats no history — unless
        // it was learned against a different day boundary, where it would be nonsense.
        return cached?.takeIf { it.dayStartHour == dayStartHour }
            ?: TdeeBaseline.fallback(bmrPerDayKcal, dayStartHour)
    }

    private fun TdeeBaseline.isStale(dayStartHour: Int): Boolean =
        this.dayStartHour != dayStartHour ||
            System.currentTimeMillis() - computedAt.toEpochMilli() > TdeeBaseline.MAX_AGE_MS

    private suspend fun read(): TdeeBaseline? {
        val prefs = context.baselineStore.data.first()
        val mean = prefs[Keys.MEAN] ?: return null
        val curve = prefs[Keys.CURVE]
            ?.split(',')
            ?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 25 }
            ?: return null
        return TdeeBaseline(
            meanFullDayKcal = mean,
            cumulativeByHour = curve,
            sampleDays = prefs[Keys.DAYS] ?: 0,
            dayStartHour = prefs[Keys.DAY_START_HOUR] ?: 0,
            computedAt = Instant.ofEpochMilli(prefs[Keys.AT] ?: 0L),
        )
    }

    private suspend fun write(baseline: TdeeBaseline) {
        context.baselineStore.edit { prefs ->
            prefs[Keys.MEAN] = baseline.meanFullDayKcal
            prefs[Keys.CURVE] = baseline.cumulativeByHour.joinToString(",")
            prefs[Keys.DAYS] = baseline.sampleDays
            prefs[Keys.DAY_START_HOUR] = baseline.dayStartHour
            prefs[Keys.AT] = baseline.computedAt.toEpochMilli()
        }
    }

    private object Keys {
        val MEAN = doublePreferencesKey("mean_full_day_kcal")
        val CURVE = stringPreferencesKey("cumulative_by_hour")
        val DAYS = intPreferencesKey("sample_days")
        val DAY_START_HOUR = intPreferencesKey("day_start_hour")
        val AT = longPreferencesKey("computed_at")
    }
}
