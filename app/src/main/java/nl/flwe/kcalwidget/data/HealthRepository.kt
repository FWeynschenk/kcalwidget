package nl.flwe.kcalwidget.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import nl.flwe.kcalwidget.data.history.ResolvedCarry
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.HealthMetric
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/** Raw numbers straight out of Health Connect, before any modelling. */
data class HealthSnapshot(
    val intakeKcal: Double?,
    val totalBurnedKcal: Double?,
    val activeBurnedKcal: Double?,
    val steps: Long?,
    val basalKcalPerDay: Double?,
    val weightKg: Double?,
    val lastWeighInAt: Instant?,
    val burnOrigins: Set<String>,
    /** The window the figures above were read over. */
    val windowStart: LocalDateTime? = null,
    val windowEnd: LocalDateTime? = null,
    /**
     * Food logged in the hours immediately before the day boundary.
     *
     * Just after midnight the day is minutes old and everything eaten that evening now
     * belongs to yesterday. That is correct and deeply confusing, so the app can say it
     * out loud instead of reporting nothing and leaving the user to wonder whether the
     * read failed.
     */
    val intakeBeforeBoundaryKcal: Double? = null,
    /**
     * Why a read came back empty, when it came back empty for a reason. Empty list means
     * nothing failed; a missing figure with no error here really is missing data.
     */
    val errors: List<String> = emptyList(),
)

/** How long before the day boundary to look when today has no food logged yet. */
private const val BOUNDARY_LOOKBACK_HOURS = 8L

/**
 * Runs a Health Connect call, returning null on failure but never quietly.
 *
 * Two things `runCatching { }.getOrNull()` gets wrong and this does not. It swallows
 * CancellationException, so a `withTimeoutOrNull` around the call looks like a provider
 * that returned nothing -- which is how a burn aggregate timed out for weeks without
 * anyone being able to tell. And it leaves no trace at all, so the only evidence that
 * anything went wrong is a number that reads zero.
 *
 * Callers that can show the user what failed should collect the error instead; this is
 * for the places where null is genuinely handled and a log is the right record.
 */
internal inline fun <T> hcOrNull(label: String, block: () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    android.util.Log.w("KcalHealth", "$label read failed", e)
    null
}

enum class HealthAvailability { AVAILABLE, UPDATE_REQUIRED, UNSUPPORTED }

class HealthRepository(private val context: Context) {

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.UNSUPPORTED
    }

    fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthAvailability.AVAILABLE) {
            hcOrNull("client") { HealthConnectClient.getOrCreate(context) }
        } else {
            null
        }

    suspend fun grantedPermissions(): Set<String> =
        clientOrNull()?.permissionController?.getGrantedPermissions() ?: emptySet()

    suspend fun hasRequiredPermissions(): Boolean =
        grantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    suspend fun hasHistoryPermission(): Boolean =
        grantedPermissions().contains(HISTORY_PERMISSION)

    /** Writers the user has chosen for a metric, or every writer when none is chosen. */
    fun originsFor(settings: AppSettings, metric: HealthMetric): Set<DataOrigin> =
        settings.sources.packagesFor(metric).map { DataOrigin(it) }.toSet()

    /**
     * Reads today's numbers in the configured day frame. Every read is individually
     * optional: a missing permission or a data type nothing writes yields null rather than
     * failing the whole snapshot.
     */
    suspend fun readToday(settings: AppSettings): HealthSnapshot? {
        val client = clientOrNull() ?: return null
        val errors = mutableListOf<String>()
        val now = LocalDateTime.now()
        val dayStart = DayWindow.currentStart(settings.calculation.dayStartHour, now)
        val today = DayWindow.todayRange(settings.calculation.dayStartHour, now)

        val intake = aggregateOrNull(
            client,
            setOf(NutritionRecord.ENERGY_TOTAL),
            today,
            originsFor(settings, HealthMetric.NUTRITION),
            errors,
            "nutrition",
        )?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories

        // Only worth asking when today has nothing: it answers "where did my dinner go"
        // in the hours after the boundary, and is pointless at any other time.
        val beforeBoundary = if (intake == null) {
            aggregateOrNull(
                client,
                setOf(NutritionRecord.ENERGY_TOTAL),
                TimeRangeFilter.between(dayStart.minusHours(BOUNDARY_LOOKBACK_HOURS), dayStart),
                originsFor(settings, HealthMetric.NUTRITION),
            )?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories
        } else {
            null
        }

        val totalResult = aggregateOrNull(
            client,
            setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
            today,
            originsFor(settings, HealthMetric.TOTAL_BURN),
        )
        val activeResult = aggregateOrNull(
            client,
            setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            today,
            originsFor(settings, HealthMetric.ACTIVE_BURN),
        )
        val steps = aggregateOrNull(
            client,
            setOf(StepsRecord.COUNT_TOTAL),
            today,
            originsFor(settings, HealthMetric.STEPS),
        )?.get(StepsRecord.COUNT_TOTAL)

        val weighIn = latestWeighIn(client, settings)

        return HealthSnapshot(
            intakeKcal = intake,
            totalBurnedKcal = totalResult?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            activeBurnedKcal = activeResult
                ?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            steps = steps,
            basalKcalPerDay = if (settings.calculation.preferHcBasal) {
                latestBasalKcalPerDay(client, settings)
            } else {
                null
            },
            weightKg = weighIn?.first,
            lastWeighInAt = weighIn?.second,
            burnOrigins = buildSet {
                totalResult?.dataOrigins?.forEach { add(it.packageName) }
                activeResult?.dataOrigins?.forEach { add(it.packageName) }
            },
            windowStart = dayStart,
            windowEnd = now,
            intakeBeforeBoundaryKcal = beforeBoundary,
            errors = errors,
        )
    }

    /** Convenience wrapper: read, then model, in one call. */
    suspend fun todayEnergy(
        settings: AppSettings,
        baseline: TdeeBaseline? = null,
        carry: ResolvedCarry = ResolvedCarry.OFF,
        now: Instant = Instant.now(),
    ): DayEnergy? {
        val snapshot = readToday(settings) ?: return null
        val elapsed = DayWindow.elapsed(settings.calculation.dayStartHour)
        return Energetics.compute(snapshot, settings, elapsed, baseline, carry, now)
    }

    /**
     * Learns what a typical day looks like from recent history.
     *
     * The current day is excluded because it is incomplete, and days whose total falls
     * below [MIN_PLAUSIBLE_DAY_FACTOR] of resting burn are dropped: those are days the
     * tracker was off the wrist, and averaging them in would drag the baseline down.
     *
     * Both the level and the shape are learned in the configured day frame, which is why
     * the result records the boundary it was learned with.
     */
    suspend fun computeBaseline(settings: AppSettings, bmrPerDayKcal: Double): TdeeBaseline? {
        val client = clientOrNull() ?: return null
        val dayStartHour = settings.calculation.dayStartHour
        val range = DayWindow.recentCompleteDays(TdeeBaseline.WINDOW_DAYS, dayStartHour)
        val origins = originsFor(settings, HealthMetric.TOTAL_BURN)

        val dailyTotals = hcOrNull("baseline") {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    range,
                    Period.ofDays(1),
                    origins,
                )
            ).mapNotNull { it.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories }
        }.orEmpty()

        val plausible = dailyTotals.filter { it >= bmrPerDayKcal * MIN_PLAUSIBLE_DAY_FACTOR }
        if (plausible.size < TdeeBaseline.MIN_SAMPLE_DAYS) return null

        // The shape is optional: hourly buckets over two weeks is a big query, and the
        // mean alone already fixes most of the early-day pessimism.
        val curve = runCatching { hourlyCurve(client, range, origins, dayStartHour, bmrPerDayKcal) }
            .getOrNull()
            ?: TdeeBaseline.DEFAULT_CURVE

        return TdeeBaseline(
            meanFullDayKcal = plausible.average(),
            cumulativeByHour = curve,
            sampleDays = plausible.size,
            dayStartHour = dayStartHour,
            computedAt = Instant.now(),
        )
    }

    /**
     * Average of each day's own cumulative burn curve, indexed by hours since that day's
     * start. Normalising per day before averaging keeps a single very active day from
     * distorting the shape as well as the level.
     */
    private suspend fun hourlyCurve(
        client: HealthConnectClient,
        range: TimeRangeFilter,
        origins: Set<DataOrigin>,
        dayStartHour: Int,
        bmrPerDayKcal: Double,
    ): List<Double>? {
        val zone = ZoneId.systemDefault()
        val buckets = client.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                range,
                Duration.ofHours(1),
                origins,
            )
        )

        val perDay = linkedMapOf<LocalDate, DoubleArray>()
        buckets.forEach { bucket ->
            val local = bucket.startTime.atZone(zone).toLocalDateTime()
            val kcal = bucket.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
            val logicalDay = DayWindow.currentStart(dayStartHour, local).toLocalDate()
            val hour = DayWindow.hoursInto(dayStartHour, local).toInt().coerceIn(0, 23)
            perDay.getOrPut(logicalDay) { DoubleArray(24) }[hour] += kcal
        }

        val usable = perDay.values.filter { it.sum() >= bmrPerDayKcal * MIN_PLAUSIBLE_DAY_FACTOR }
        if (usable.size < TdeeBaseline.MIN_SAMPLE_DAYS) return null

        val cumulative = DoubleArray(25)
        usable.forEach { day ->
            val total = day.sum()
            var acc = 0.0
            for (h in 0 until 24) {
                acc += day[h]
                cumulative[h + 1] += acc / total
            }
        }
        for (h in 1..24) cumulative[h] /= usable.size
        cumulative[24] = 1.0
        for (h in 1..24) cumulative[h] = maxOf(cumulative[h], cumulative[h - 1])
        return cumulative.toList()
    }

    suspend fun aggregateOrNull(
        client: HealthConnectClient,
        metrics: Set<AggregateMetric<*>>,
        range: TimeRangeFilter,
        origins: Set<DataOrigin> = emptySet(),
        /** Appended to when the read throws, so a failure is not read as an empty day. */
        errors: MutableList<String>? = null,
        label: String? = null,
    ): AggregationResult? = try {
        client.aggregate(AggregateRequest(metrics, range, origins))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errors?.add("${label ?: "read"} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Latest weight and when it was recorded, for BMR, BMI and the weigh-in reminder. */
    private suspend fun latestWeighIn(
        client: HealthConnectClient,
        settings: AppSettings,
    ): Pair<Double, Instant>? = runCatching {
        client.readRecords(
            ReadRecordsRequest(
                WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    LocalDate.now().minusDays(365).atStartOfDay(),
                    LocalDateTime.now(),
                ),
                dataOriginFilter = originsFor(settings, HealthMetric.WEIGHT),
                ascendingOrder = false,
                pageSize = 1,
            )
        ).records.firstOrNull()?.let { it.weight.inKilograms to it.time }
    }.getOrNull()

    private suspend fun latestBasalKcalPerDay(
        client: HealthConnectClient,
        settings: AppSettings,
    ): Double? = runCatching {
        client.readRecords(
            ReadRecordsRequest(
                BasalMetabolicRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    LocalDate.now().minusDays(30).atStartOfDay(),
                    LocalDateTime.now(),
                ),
                dataOriginFilter = originsFor(settings, HealthMetric.BASAL),
                ascendingOrder = false,
                pageSize = 1,
            )
        ).records.firstOrNull()?.basalMetabolicRate?.inKilocaloriesPerDay
    }.getOrNull()

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )

        /**
         * Requested separately, because Health Connect versions older than the one that
         * introduced it reject the whole request when it contains an unknown permission.
         */
        const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        /**
         * Without this, Health Connect serves only the last 30 days and refuses anything
         * older. Requested separately for the same reason as the background permission:
         * an older provider rejects a whole batch containing a permission it does not know.
         */
        const val HISTORY_PERMISSION = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        /** How far back Health Connect will go without [HISTORY_PERMISSION]. */
        const val DAYS_WITHOUT_HISTORY_PERMISSION = 29

        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

        /** A day below this multiple of resting burn means the tracker was not worn. */
        const val MIN_PLAUSIBLE_DAY_FACTOR = 0.6
    }
}
