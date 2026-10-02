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
import nl.flwe.kcalwidget.data.settings.BankingState
import nl.flwe.kcalwidget.data.settings.HealthMetric
import nl.flwe.kcalwidget.data.weight.WeightTrend
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.temporal.ChronoUnit
import kotlin.math.pow
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
    /** How much this day still counts, 1.0 the morning after and halving every few days. */
    val weight: Double,
    /**
     * What the day came to from the figures the apps reported, before any correction.
     * Kept rather than recovered by dividing the factors back out, which only works
     * while nothing else has touched them.
     */
    val asLoggedKcal: Double = 0.0,
) {
    /** What the day actually came to. Positive means it left something over. */
    val rawKcal: Double get() = (burnKcal + goalDeltaKcal) - intakeKcal

    /** [rawKcal] after the per-day limit, before ageing. */
    val clippedKcal: Double
        get() = rawKcal.coerceIn(
            -HistoryRepository.MAX_DAY_CONTRIBUTION_KCAL,
            HistoryRepository.MAX_DAY_CONTRIBUTION_KCAL,
        )

    val wasClipped: Boolean get() = kotlin.math.abs(rawKcal - clippedKcal) > 1.0

    /** What this day contributes to today's carry, after clipping and ageing. */
    val kcal: Double get() = clippedKcal * weight
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

    /** False when no complete day could be read, which is not the same as a carry of zero. */
    val isReal: Boolean get() = days.isNotEmpty()

    companion object {
        val NONE = BankedCarry(emptyList(), 0.0, 0.0)
    }
}

/** Where today's carry figure came from, which decides what the UI is allowed to claim. */
enum class CarryState {
    /** Banking is off. */
    OFF,

    /** Read from complete days just now. */
    FRESH,

    /** The read came back short, so the last good figure is standing in. */
    STALE,

    /** Nothing readable and nothing remembered. The budget runs without a carry. */
    UNAVAILABLE,
}

data class ResolvedCarry(val kcal: Double, val state: CarryState) {
    val applies: Boolean get() = state == CarryState.FRESH || state == CarryState.STALE

    companion object {
        val OFF = ResolvedCarry(0.0, CarryState.OFF)
    }
}

/**
 * Decides which carry today's budget should use.
 *
 * This exists because the obvious version -- `carry ?: 0.0` -- is actively dangerous. A
 * Health Connect read that comes back short is indistinguishable from a week that came
 * out even, and treating the two the same silently cancels a penalty of up to 700 kcal:
 * the budget jumps by that much, mid-afternoon, for no reason the user can see. It has
 * happened, and it read as a small walk somehow being worth a thousand calories.
 *
 * A carry is computed from days that are already over, so it does not change during the
 * day. Yesterday's figure standing in for a failed read is a far better estimate than
 * zero, which is not an estimate at all.
 */
object Banking {

    /** Beyond this the remembered carry is describing a window that has moved on. */
    const val MAX_REMEMBERED_DAYS = 2L

    fun resolve(
        fresh: BankedCarry?,
        remembered: BankingState,
        today: LocalDate,
        enabled: Boolean,
    ): ResolvedCarry {
        if (!enabled) return ResolvedCarry.OFF
        if (fresh != null && fresh.isReal) return ResolvedCarry(fresh.totalKcal, CarryState.FRESH)

        val kcal = remembered.lastCarryKcal
        val day = remembered.lastCarryDay
        if (kcal != null && day != null) {
            val age = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(day), today)
            if (age in 0..MAX_REMEMBERED_DAYS) return ResolvedCarry(kcal, CarryState.STALE)
        }
        return ResolvedCarry(0.0, CarryState.UNAVAILABLE)
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

            val today = rows.lastOrNull()?.date?.plusDays(1) ?: return BankedCarry.NONE
            val oldest = today.minusDays(CARRY_WINDOW_DAYS.toLong())

            val days = rows
                .filter { it.date >= oldest && it.intakeKcal != null && it.burnKcal != null }
                .map { row ->
                    val age = ChronoUnit.DAYS.between(row.date, today)
                    CarryDay(
                        date = row.date,
                        burnKcal = row.burnKcal!! * burnFactor,
                        intakeKcal = row.intakeKcal!! * intakeFactor,
                        goalDeltaKcal = delta,
                        weight = ageWeight(age),
                        asLoggedKcal = (row.burnKcal + delta) - row.intakeKcal!!,
                    )
                }
            if (days.isEmpty()) return BankedCarry.NONE

            val raw = days.sumOf { it.kcal }
            return BankedCarry(days, raw, raw.coerceIn(-MAX_BANKED_KCAL, MAX_BANKED_KCAL))
        }

        /**
         * How much a day [age] days ago still counts. 1.0 the morning after, halving every
         * [CARRY_HALF_LIFE_DAYS].
         *
         * Deliberately not normalised to a fixed total. Over a full window the weights sum
         * to about six, which is where the old six-day window's scale came from, and when
         * days are missing the carry shrinks by itself -- which is right, because there is
         * less evidence behind it. Normalising would scale three logged days up as though
         * they were six.
         */
        internal fun ageWeight(age: Long): Double =
            0.5.pow((age - 1).coerceAtLeast(0L) / CARRY_HALF_LIFE_DAYS)

        /**
         * Intake minus burn for one day, corrected exactly as the budget and the carry
         * correct it.
         *
         * The raw figures in the day list are what the apps reported and stay that way,
         * but anything that judges a day -- met the goal or missed it -- has to use the
         * same numbers the goal is actually enforced with. Judging the chart raw while
         * the carry runs calibrated is how a day ends up green on one screen and taking
         * 271 kcal off the next.
         */
        fun calibratedNet(row: DayRow, settings: AppSettings): Double? {
            val intake = row.intakeKcal ?: return null
            val burn = row.burnKcal ?: return null
            val calibrating = settings.features.autoCalibration
            val burnFactor = if (calibrating) settings.calibration.expenditureFactor else 1.0
            val intakeFactor = if (calibrating) settings.calibration.intakeFactor else 1.0
            return intake * intakeFactor - burn * burnFactor
        }

        /** The carry as a single number, which is all the budget needs. */
        internal fun bankedAdjustment(rows: List<DayRow>, settings: AppSettings): Double =
            bankedCarry(rows, settings).totalKcal

        const val DEFAULT_DAYS = 90

        /**
         * Days read for the carry, and the span the carry is computed over.
         *
         * Exactly [CHUNK_DAYS], so the resume read is one Health Connect query rather than
         * two. The burn aggregate is the slow one on this provider and has timed out
         * before, so keeping the common path to a single chunk is worth more than the
         * last few percent of a longer tail: at a four-day half-life the oldest day in a
         * fortnight already counts for about a tenth, so its departure is not an event.
         */
        const val BANKING_DAYS = 14
        const val CARRY_WINDOW_DAYS = 14

        /**
         * How fast a day stops counting.
         *
         * A rectangular window was the problem: a day counted fully for six days and then
         * nothing at all, so the budget lurched by the whole of that day overnight. One
         * big Saturday would hand you several hundred kcal of room all week and then take
         * every last one back in a single step, which is the shape most likely to be eaten
         * into and then regretted. Halving smoothly means no day's departure is an event.
         */
        const val CARRY_HALF_LIFE_DAYS = 4.0

        /**
         * The most any single day may contribute before ageing.
         *
         * Without it one exceptional day dominates the whole window, and the licence it
         * grants outlives the memory of earning it. Symmetric, so a blow-out is forgiven
         * on the same terms a fast is discounted.
         */
        const val MAX_DAY_CONTRIBUTION_KCAL = 600.0

        /** One heavy week should not be able to swallow today's budget. */
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
