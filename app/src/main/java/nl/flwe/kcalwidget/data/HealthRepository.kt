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
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.HealthMetric
import java.time.Duration
import java.time.Instant
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
)

enum class HealthAvailability { AVAILABLE, UPDATE_REQUIRED, UNSUPPORTED }

class HealthRepository(private val context: Context) {

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.UNSUPPORTED
    }

    fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthAvailability.AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
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
        val today = DayWindow.todayRange(settings.calculation.dayStartHour)

        val intake = aggregateOrNull(
            client,
            setOf(NutritionRecord.ENERGY_TOTAL),
            today,
            originsFor(settings, HealthMetric.NUTRITION),
        )?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories

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
        )
    }

    /** Convenience wrapper: read, then model, in one call. */
    suspend fun todayEnergy(
        settings: AppSettings,
        baseline: TdeeBaseline? = null,
        bankedAdjustmentKcal: Double = 0.0,
        now: Instant = Instant.now(),
    ): DayEnergy? {
        val snapshot = readToday(settings) ?: return null
        val elapsed = DayWindow.elapsed(settings.calculation.dayStartHour)
        return Energetics.compute(snapshot, settings, elapsed, baseline, bankedAdjustmentKcal, now)
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

        val dailyTotals = runCatching {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    range,
                    Period.ofDays(1),
                    origins,
                )
            ).mapNotNull { it.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories }
        }.getOrNull().orEmpty()

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
    ): AggregationResult? = runCatching {
        client.aggregate(AggregateRequest(metrics, range, origins))
    }.getOrNull()

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
