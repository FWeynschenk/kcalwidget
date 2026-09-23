package nl.flwe.kcalwidget.data.sources

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.data.settings.HealthMetric
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period

/** One app that writes a metric, with enough context to choose between them. */
data class SourceOption(
    val packageName: String,
    val label: String,
    val daysWithData: Int,
    val recommended: Boolean,
)

data class SourceCatalog(
    val byMetric: Map<HealthMetric, List<SourceOption>>,
    val computedAtMs: Long,
) {
    fun optionsFor(metric: HealthMetric): List<SourceOption> = byMetric[metric].orEmpty()
}

/**
 * Finds which apps write which metrics, so the user can point each calculation at the
 * right one instead of guessing package names.
 *
 * Only the settings and wizard screens need this, never a widget refresh, so the result is
 * held in memory for the session rather than persisted. A manual refresh drops it.
 */
class SourceDiscovery(
    private val context: Context,
    private val health: HealthRepository,
) {

    suspend fun catalog(forceRefresh: Boolean = false): SourceCatalog {
        cached?.takeIf { !forceRefresh && !it.isStale() }?.let { return it }
        val fresh = discover()
        cached = fresh
        return fresh
    }

    private fun SourceCatalog.isStale(): Boolean =
        System.currentTimeMillis() - computedAtMs > CACHE_MS

    private suspend fun discover(): SourceCatalog {
        val client = health.clientOrNull()
            ?: return SourceCatalog(emptyMap(), System.currentTimeMillis())
        val range = TimeRangeFilter.between(
            LocalDate.now().minusDays(WINDOW_DAYS.toLong()).atStartOfDay(),
            LocalDateTime.now(),
        )
        val pm = context.packageManager

        // One round trip per metric, and they do not depend on each other, so they go
        // out together rather than one at a time.
        val byMetric = coroutineScope {
            HealthMetric.entries.map { metric ->
                async { metric to countDaysByOrigin(client, metric, range) }
            }.awaitAll()
        }.associate { (metric, daysByOrigin) ->
            val ranked = daysByOrigin.entries.sortedByDescending { it.value }
            // Health Connect sums overlapping records, so two trackers writing the same
            // walk are added together. For those metrics one writer must win; for the
            // rest, multiple writers are complementary and "all apps" is correct.
            val recommendedPackage = if (metric in SINGLE_WRITER_METRICS && ranked.size > 1) {
                ranked.first().key
            } else {
                null
            }

            metric to ranked.map { (pkg, days) ->
                SourceOption(
                    packageName = pkg,
                    label = labelFor(pm, pkg),
                    daysWithData = days,
                    recommended = pkg == recommendedPackage,
                )
            }
        }

        return SourceCatalog(byMetric, System.currentTimeMillis())
    }

    /**
     * How many days each app contributed for a metric. Counting days beats counting totals:
     * an app that wrote once a fortnight ago is not the main source.
     */
    private suspend fun countDaysByOrigin(
        client: androidx.health.connect.client.HealthConnectClient,
        metric: HealthMetric,
        range: TimeRangeFilter,
    ): Map<String, Int> = runCatching {
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(setOf(aggregateFor(metric)), range, Period.ofDays(1))
        ).flatMap { bucket -> bucket.result.dataOrigins.map { it.packageName } }
            .groupingBy { it }
            .eachCount()
    }.getOrNull().orEmpty()

    private fun labelFor(pm: android.content.pm.PackageManager, packageName: String): String =
        runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

    companion object {
        // Six metrics bucketed per day is a lot of IPC; a fortnight is plenty to tell
        // a main source from an app that wrote once.
        private const val WINDOW_DAYS = 14
        private const val CACHE_MS = 10 * 60 * 1000L

        /** Volatile per-process cache; discovery is only ever needed while browsing settings. */
        @Volatile
        private var cached: SourceCatalog? = null

        /** Metrics where Health Connect would sum two writers into a double count. */
        val SINGLE_WRITER_METRICS = setOf(
            HealthMetric.TOTAL_BURN,
            HealthMetric.ACTIVE_BURN,
            HealthMetric.STEPS,
        )

        fun aggregateFor(metric: HealthMetric): AggregateMetric<*> = when (metric) {
            HealthMetric.NUTRITION -> NutritionRecord.ENERGY_TOTAL
            HealthMetric.TOTAL_BURN -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
            HealthMetric.ACTIVE_BURN -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
            HealthMetric.STEPS -> StepsRecord.COUNT_TOTAL
            HealthMetric.WEIGHT -> WeightRecord.WEIGHT_AVG
            HealthMetric.BASAL -> BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL
        }

        fun displayName(metric: HealthMetric): String = when (metric) {
            HealthMetric.NUTRITION -> "Food intake"
            HealthMetric.TOTAL_BURN -> "Total calories burned"
            HealthMetric.ACTIVE_BURN -> "Active calories"
            HealthMetric.STEPS -> "Steps"
            HealthMetric.WEIGHT -> "Weight"
            HealthMetric.BASAL -> "Resting rate"
        }

        fun explanation(metric: HealthMetric): String = when (metric) {
            HealthMetric.NUTRITION -> "Where your eaten calories come from."
            HealthMetric.TOTAL_BURN ->
                "Whole-day burn including resting. Pick one tracker: two would be summed."
            HealthMetric.ACTIVE_BURN ->
                "Exercise calories, used when no total is available. One tracker only."
            HealthMetric.STEPS -> "Last-resort burn estimate. One counter only."
            HealthMetric.WEIGHT -> "Used for resting rate, BMI and goal progress."
            HealthMetric.BASAL -> "A measured resting rate, preferred over the estimate."
        }
    }
}
