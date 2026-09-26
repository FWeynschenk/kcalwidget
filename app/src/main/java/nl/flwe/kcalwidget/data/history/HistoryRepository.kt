package nl.flwe.kcalwidget.data.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import nl.flwe.kcalwidget.data.DayWindow
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.HealthMetric
import nl.flwe.kcalwidget.data.weight.WeightTrend
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/** One complete day, as it actually happened. */
data class DayRow(
    val date: LocalDate,
    val intakeKcal: Double?,
    val burnKcal: Double?,
    val weightKg: Double?,
) {
    /** Positive means eaten more than burned. Null unless both halves are known. */
    val netKcal: Double?
        get() = if (intakeKcal != null && burnKcal != null) intakeKcal - burnKcal else null
}

/**
 * What the last history read actually saw.
 *
 * Health Connect is opaque and its failures are quiet, so the app keeps a record of what
 * it asked for and what came back. Without this, "no history" is indistinguishable from
 * "permission refused", "provider threw" and "you genuinely have no data".
 */
data class HistoryDiagnostics(
    val requestedDays: Int,
    val allowedDays: Int,
    val hasHistoryPermission: Boolean,
    val intakeDays: Int,
    val burnDays: Int,
    val weighInCount: Int,
    /** The most recent readings, exactly as Health Connect returned them. */
    val weightSample: List<Pair<LocalDate, Double>>,
    val errors: List<String>,
    /** How long each read took, so a slow one can be identified rather than guessed at. */
    val timings: List<String>,
)

/** What one past day lends to, or takes from, today's budget. */
data class CarryDay(
    val date: LocalDate,
    /** Calibrated burn for that day, as the carry counted it. */
    val burnKcal: Double,
    /** Calibrated intake for that day, as the carry counted it. */
    val intakeKcal: Double,
    /** The goal's own allowance for that day. */
    val goalDeltaKcal: Double,
) {
    /** Positive means that day left something over for today. */
    val kcal: Double get() = (burnKcal + goalDeltaKcal) - intakeKcal
}

/**
 * The past week's surplus or deficit, and where every kcal of it came from.
 *
 * Banking silently moves the budget by hundreds of kcal, so the total alone is not enough:
 * without the days behind it, a budget that looks wrong cannot be checked against anything.
 */
data class BankedCarry(
    val days: List<CarryDay>,
    /** Before the cap. */
    val rawTotalKcal: Double,
    /** After the cap: what actually reaches the budget. */
    val totalKcal: Double,
) {
    val capped: Boolean get() = kotlin.math.abs(rawTotalKcal - totalKcal) > 0.5

    companion object {
        val NONE = BankedCarry(emptyList(), 0.0, 0.0)
    }
}

data class History(
    /** Complete days only, oldest first. Today is excluded because it is still running. */
    val rows: List<DayRow>,
    val trend: WeightTrend,
    /**
     * Carried-over surplus or deficit from the past week, for weekly banking. Positive
     * means the week is ahead of the goal and today can afford more.
     */
    val carry: BankedCarry,
    val diagnostics: HistoryDiagnostics,
) {
    val bankedAdjustmentKcal: Double get() = carry.totalKcal
    val daysWithIntake: Int get() = rows.count { it.intakeKcal != null }
}

class HistoryRepository(
    private val context: Context,
    private val health: HealthRepository,
) {

    /**
     * Reads recent complete days. Never returns null: when nothing can be read, the
     * reason travels back in [History.diagnostics] rather than vanishing.
     */
    suspend fun load(settings: AppSettings, days: Int = DEFAULT_DAYS): History {
        val errors = mutableListOf<String>()
        val timings = mutableListOf<String>()
        val hasHistoryPermission = runCatching { health.hasHistoryPermission() }
            .getOrElse {
                errors += "permission check failed: $it"
                false
            }

        // Health Connect refuses anything older than 30 days unless the history permission
        // is held. Asking for 90 without it does not quietly return 30 days, it fails.
        val allowed = if (hasHistoryPermission) {
            days
        } else {
            days.coerceAtMost(HealthRepository.DAYS_WITHOUT_HISTORY_PERMISSION)
        }

        val client = health.clientOrNull()
        if (client == null) {
            errors += "no Health Connect client"
            return empty(days, allowed, hasHistoryPermission, errors)
        }

        val dayStartHour = settings.calculation.dayStartHour
        val rangeEnd = DayWindow.currentStart(dayStartHour)
        val rangeStart = rangeEnd.minusDays(allowed.toLong())

        // Three independent round trips. Issued together they cost one wait instead of
        // three, which matters when each one can take seconds.
        // Each read gets its own budget. One slow query then costs only itself, and the
        // rest of the screen still fills in, instead of the whole thing coming back empty.
        val (intakeByDay, burnByDay, weighIns) = coroutineScope {
            val intake = async {
                timed("intake", timings, errors) {
                    dailyTotals(
                        client, rangeStart, rangeEnd, dayStartHour,
                        NutritionRecord.ENERGY_TOTAL,
                        health.originsFor(settings, HealthMetric.NUTRITION),
                        "intake", errors,
                    )
                } ?: emptyMap()
            }
            val burn = async {
                timed("burn", timings, errors) {
                    dailyTotals(
                        client, rangeStart, rangeEnd, dayStartHour,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        health.originsFor(settings, HealthMetric.TOTAL_BURN),
                        "burn", errors,
                    )
                } ?: emptyMap()
            }
            val weights = async {
                timed("weights", timings, errors) {
                    weightReadings(settings, allowed, errors)
                } ?: emptyList()
            }
            Triple(intake.await(), burn.await(), weights.await())
        }
        val weightByDay = weighIns.groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.average() }

        val dates = (intakeByDay.keys + burnByDay.keys + weightByDay.keys).sorted()
        val rows = dates.map { date ->
            DayRow(
                date = date,
                intakeKcal = intakeByDay[date],
                burnKcal = burnByDay[date],
                weightKg = weightByDay[date],
            )
        }

        return History(
            rows = rows,
            trend = WeightTrend.from(weighIns),
            carry = bankedCarry(rows, settings),
            diagnostics = HistoryDiagnostics(
                requestedDays = days,
                allowedDays = allowed,
                hasHistoryPermission = hasHistoryPermission,
                intakeDays = intakeByDay.size,
                burnDays = burnByDay.size,
                weighInCount = weighIns.size,
                weightSample = weighIns.takeLast(SAMPLE_SIZE),
                errors = errors,
                timings = timings,
            ),
        )
    }

    /** Runs one read under its own timeout, recording how long it took either way. */
    private suspend fun <T> timed(
        label: String,
        timings: MutableList<String>,
        errors: MutableList<String>,
        block: suspend () -> T,
    ): T? {
        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(PER_READ_TIMEOUT_MS) { block() }
        val elapsed = System.currentTimeMillis() - started
        if (result == null) {
            timings += "$label: gave up after ${elapsed}ms"
            errors += "$label read exceeded ${PER_READ_TIMEOUT_MS}ms"
        } else {
            timings += "$label: ${elapsed}ms"
        }
        return result
    }

    private fun empty(
        days: Int,
        allowed: Int,
        hasHistoryPermission: Boolean,
        errors: List<String>,
    ) = History(
        rows = emptyList(),
        trend = WeightTrend.from(emptyList()),
        carry = BankedCarry.NONE,
        diagnostics = HistoryDiagnostics(
            requestedDays = days,
            allowedDays = allowed,
            hasHistoryPermission = hasHistoryPermission,
            intakeDays = 0,
            burnDays = 0,
            weighInCount = 0,
            weightSample = emptyList(),
            errors = errors,
            timings = emptyList(),
        ),
    )

    suspend fun weightReadings(
        settings: AppSettings,
        days: Int = DEFAULT_DAYS,
        errors: MutableList<String> = mutableListOf(),
    ): List<Pair<LocalDate, Double>> {
        val client = health.clientOrNull() ?: return emptyList()
        val zone = ZoneId.systemDefault()
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        LocalDate.now().minusDays(days.toLong()).atStartOfDay(),
                        java.time.LocalDateTime.now(),
                    ),
                    dataOriginFilter = health.originsFor(settings, HealthMetric.WEIGHT),
                    ascendingOrder = true,
                    pageSize = 1000,
                )
            ).records.map { record ->
                val local = record.time.atZone(zone).toLocalDateTime()
                DayWindow.currentStart(settings.calculation.dayStartHour, local)
                    .toLocalDate() to record.weight.inKilograms
            }
        }.onFailure {
            Log.w("KcalHistory", "weight read failed", it)
            errors += "weight read: ${it.javaClass.simpleName}: ${it.message}"
        }.getOrDefault(emptyList())
    }

    /**
     * Daily totals across a span, read in chunks.
     *
     * A quarter of TotalCaloriesBurned is hundreds of thousands of records, and asking
     * Health Connect to bucket all of it in one call does not return. Fourteen days is a
     * span it serves quickly, so a longer one is cut into pieces that size and issued
     * together. A chunk that still times out costs only its own days; the rest survives.
     */
    private suspend fun dailyTotals(
        client: androidx.health.connect.client.HealthConnectClient,
        rangeStart: LocalDateTime,
        rangeEnd: LocalDateTime,
        dayStartHour: Int,
        metric: androidx.health.connect.client.aggregate.AggregateMetric<androidx.health.connect.client.units.Energy>,
        origins: Set<androidx.health.connect.client.records.metadata.DataOrigin>,
        label: String,
        errors: MutableList<String>,
    ): Map<LocalDate, Double> = coroutineScope {
        val chunks = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var cursor = rangeStart
        while (cursor.isBefore(rangeEnd)) {
            val next = minOf(cursor.plusDays(CHUNK_DAYS.toLong()), rangeEnd)
            chunks += cursor to next
            cursor = next
        }

        val results = chunks.map { (from, to) ->
            async {
                withTimeoutOrNull(CHUNK_TIMEOUT_MS) {
                    runCatching {
                        client.aggregateGroupByPeriod(
                            AggregateGroupByPeriodRequest(
                                setOf(metric),
                                TimeRangeFilter.between(from, to),
                                Period.ofDays(1),
                                origins,
                            )
                        ).mapNotNull { bucket ->
                            val kcal = bucket.result[metric]?.inKilocalories
                                ?: return@mapNotNull null
                            DayWindow.currentStart(dayStartHour, bucket.startTime)
                                .toLocalDate() to kcal
                        }.toMap()
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        Log.w("KcalHistory", "$label chunk failed", it)
                    }.getOrNull()
                }
            }
        }.awaitAll()

        val failed = results.count { it == null }
        if (failed > 0) errors += "$label: $failed of ${chunks.size} chunks did not return"
        results.filterNotNull().fold(emptyMap()) { acc, part -> acc + part }
    }

    companion object {
        /**
         * How far ahead or behind the goal the past week ran.
         *
         * Calibrated, because this lands straight in today's budget: carrying a raw figure
         * into a corrected budget mixes two scales and quietly biases the whole week.
         *
         * Only days with logged food count. A day with no nutrition record looks like a
         * whole-day fast, which would hand today an enormous and entirely fictional credit.
         */
        internal fun bankedCarry(rows: List<DayRow>, settings: AppSettings): BankedCarry {
            val calibrating = settings.features.autoCalibration
            val burnFactor = if (calibrating) settings.calibration.expenditureFactor else 1.0
            val intakeFactor = if (calibrating) settings.calibration.intakeFactor else 1.0
            val delta = settings.goal.dailyEnergyDelta

            val days = rows.takeLast(BANKING_DAYS)
                .filter { it.intakeKcal != null && it.burnKcal != null }
                .map { row ->
                    CarryDay(
                        date = row.date,
                        burnKcal = row.burnKcal!! * burnFactor,
                        intakeKcal = row.intakeKcal!! * intakeFactor,
                        goalDeltaKcal = delta,
                    )
                }
            if (days.isEmpty()) return BankedCarry.NONE

            val raw = days.sumOf { it.kcal }
            return BankedCarry(days, raw, raw.coerceIn(-MAX_BANKED_KCAL, MAX_BANKED_KCAL))
        }

        /** The carry as a single number, which is all the budget needs. */
        internal fun bankedAdjustment(rows: List<DayRow>, settings: AppSettings): Double =
            bankedCarry(rows, settings).totalKcal

        const val DEFAULT_DAYS = 90
        const val BANKING_DAYS = 6

        /** One heavy day should not be able to swallow the whole week's budget. */
        const val MAX_BANKED_KCAL = 700.0

        /** How many raw weigh-ins to keep for the diagnostics panel. */
        const val SAMPLE_SIZE = 8

        /** Budget for a whole metric's read before it is abandoned. */
        const val PER_READ_TIMEOUT_MS = 25_000L

        /** A span this size is one Health Connect serves quickly, even for burn. */
        const val CHUNK_DAYS = 14

        /** Budget for one chunk. A slow chunk costs only its own days. */
        const val CHUNK_TIMEOUT_MS = 8_000L
    }
}
