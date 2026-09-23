package nl.flwe.kcalwidget.data

import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.BodyProfile
import nl.flwe.kcalwidget.data.settings.Sex
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Where the burn figure came from, so the UI can be honest about its confidence. */
enum class BurnSource {
    /** Health Connect TotalCaloriesBurned, which already includes resting burn. */
    HC_TOTAL,

    /** Estimated resting burn plus Health Connect ActiveCaloriesBurned. */
    BMR_PLUS_ACTIVE,

    /** Estimated resting burn plus an estimate derived from step count. */
    BMR_PLUS_STEPS,

    /** Nothing but resting burn was available. */
    BMR_ONLY,
}

/** The finished picture of one day, ready to render. */
data class DayEnergy(
    /** Intake as counted, after any calibration adjustment. */
    val intakeKcal: Double,
    /** Intake exactly as Health Connect reported it, before calibration. */
    val rawIntakeKcal: Double,
    /**
     * Burn accumulated since the start of the logical day, exactly as reported. This one
     * is deliberately never corrected: it is what the tracker said, and rewriting it would
     * hide the very disagreement calibration exists to surface.
     */
    val burnedSoFarKcal: Double,
    /** Best estimate of what the whole day will come to. */
    val projectedBurnKcal: Double,
    val bmrPerDayKcal: Double,
    /** The typical-day figure the projection starts from, calibrated when that is on. */
    val typicalDayKcal: Double,
    /** How much of the projection is today's own data rather than the typical day, 0..1. */
    val confidence: Double,
    /** How far ahead of a typical day this one is running, in kcal. Negative is behind. */
    val surplusVsTypicalKcal: Double,
    /** Days of history behind [typicalDayKcal]; 0 means the fallback, not learned. */
    val baselineDays: Int,
    /** What the goal allows eating today, never below the configured intake floor. */
    val budgetKcal: Double,
    /** Surplus or deficit carried in from the past week, 0 unless banking is on. */
    val bankedAdjustmentKcal: Double,
    /** True when learned calibration factors were applied to burn and intake. */
    val calibrationApplied: Boolean,
    /** True when the goal's own budget was below the floor and the floor won. */
    val intakeFloorApplied: Boolean,
    /** Positive means there is room left to eat, negative means over budget. */
    val remainingKcal: Double,
    /** Active kcal that would bring an over-budget day back to zero. 0 when under. */
    val moveKcalToClear: Double,
    /** [moveKcalToClear] expressed as minutes of brisk walking. */
    val walkMinutesToClear: Int,
    val weightKg: Double,
    /** Whole days since the last weight record, or null if there has never been one. */
    val daysSinceWeighIn: Int?,
    val source: BurnSource,
    val hasNutritionData: Boolean,
    val updatedAt: Instant,
) {
    /** Fraction of the budget already eaten, clamped for progress bars. */
    val intakeFraction: Float
        get() = if (budgetKcal <= 0.0) 1f else (intakeKcal / budgetKcal).toFloat().coerceIn(0f, 1f)
}

object Energetics {

    /** Energy density of body fat, the usual 7700 kcal per kg figure. */
    const val KCAL_PER_KG_FAT = 7700.0

    /** Stand-in multiple of BMR for a typical day, used only until history exists. */
    const val DEFAULT_ACTIVITY_FACTOR = 1.35

    /** Brisk walking, ~5.5 km/h. Net of resting burn, so (MET - 1). */
    private const val WALK_MET = 4.3

    /** Rough net kcal per step when nothing better than a step count is available. */
    private const val KCAL_PER_STEP_PER_KG = 0.00045

    /** Mifflin-St Jeor resting metabolic rate, kcal per day. */
    fun bmrMifflinStJeor(body: BodyProfile, weightKg: Double): Double {
        val base = 10.0 * weightKg + 6.25 * body.heightCm - 5.0 * body.age
        return if (body.sex == Sex.MALE) base + 5.0 else base - 161.0
    }

    /** Net kcal burned per minute of brisk walking, above resting. */
    fun walkKcalPerMinute(weightKg: Double): Double = (WALK_MET - 1.0) * 3.5 * weightKg / 200.0

    fun kcalFromSteps(steps: Long, weightKg: Double): Double = steps * KCAL_PER_STEP_PER_KG * weightKg

    /**
     * Combines the raw Health Connect reads with the settings into a day picture.
     *
     * The projection starts the day at the person's typical full-day burn and adjusts by
     * how far today is running ahead of or behind that:
     *
     *   f        share of a normal day's burn that is normally done by now (learned curve)
     *   surplus  burnedSoFar - f x typicalDay, i.e. how far ahead today is
     *   estimate typicalDay + min(surplus, 0)
     *   floor    burnedSoFar + BMR x (rest of the day)
     *
     * The asymmetry is the point. Running behind is believed at once, because a budget
     * that turns out too small can be handed back, while one that turns out too large has
     * already been eaten. Running ahead is not projected forward at all; the floor picks
     * it up as it actually accrues, so an active day earns room through the evening rather
     * than being promised it at lunchtime and losing it by dinner.
     *
     * Both branches converge on the truth: at the end of the day the floor is exactly what
     * was burned, and a shortfall against the typical day has been subtracted in full.
     */
    fun compute(
        snapshot: HealthSnapshot,
        settings: AppSettings,
        elapsedToday: Duration,
        baseline: TdeeBaseline? = null,
        bankedAdjustmentKcal: Double = 0.0,
        now: Instant = Instant.now(),
    ): DayEnergy {
        val weightKg = snapshot.weightKg ?: settings.body.fallbackWeightKg
        val bmrPerDay = snapshot.basalKcalPerDay ?: bmrMifflinStJeor(settings.body, weightKg)
        val dayFraction = (elapsedToday.seconds.toDouble() / 86_400.0).coerceIn(0.0, 1.0)
        val restingSoFar = bmrPerDay * dayFraction

        // A tracker that has not synced yet reports a total far below resting burn.
        // Treating that as truth would invent a deficit, so fall back instead.
        val hcTotal = snapshot.totalBurnedKcal
        val hcTotalUsable = settings.calculation.preferHcTotal &&
            hcTotal != null &&
            hcTotal >= restingSoFar * 0.5

        val burnedSoFar: Double
        val source: BurnSource
        when {
            hcTotalUsable -> {
                burnedSoFar = hcTotal
                source = BurnSource.HC_TOTAL
            }
            snapshot.activeBurnedKcal != null -> {
                burnedSoFar = restingSoFar + snapshot.activeBurnedKcal
                source = BurnSource.BMR_PLUS_ACTIVE
            }
            snapshot.steps != null && snapshot.steps > 0 -> {
                burnedSoFar = restingSoFar + kcalFromSteps(snapshot.steps, weightKg)
                source = BurnSource.BMR_PLUS_STEPS
            }
            else -> {
                burnedSoFar = restingSoFar
                source = BurnSource.BMR_ONLY
            }
        }

        val effectiveBaseline = baseline
            ?: TdeeBaseline.fallback(bmrPerDay, settings.calculation.dayStartHour)
        val observedFraction = effectiveBaseline.expectedFractionAt(elapsedToday.seconds / 3600.0)

        // Calibration says the expenditure numbers themselves read high, so it is applied
        // to the inputs rather than to the answer. Scaling the result alone gives the same
        // projection but leaves every figure behind it -- the typical day, the surplus,
        // the resting rate -- sitting on the tracker's uncorrected scale, which makes the
        // Today card quietly self-contradictory.
        val calibrating = settings.features.autoCalibration
        val burnFactor = if (calibrating) settings.calibration.expenditureFactor else 1.0

        val typicalDay = effectiveBaseline.meanFullDayKcal * burnFactor
        val correctedBurnedSoFar = burnedSoFar * burnFactor
        val correctedBmrPerDay = bmrPerDay * burnFactor

        // How far ahead of, or behind, a normal day this one is running.
        val expectedByNow = typicalDay * observedFraction
        val surplus = correctedBurnedSoFar - expectedByNow

        // Being ahead is not projected forward at all. Being behind is, immediately.
        //
        // An afternoon walk is no promise that the day ends that much higher: the usual
        // evening often does not happen, because the walk stood in for it. Crediting it
        // forward makes the budget spike and then bleed away for hours, which is the worst
        // possible shape -- the room is eaten before the projection quietly takes it back.
        //
        // Nothing is lost by refusing to guess, because the floor below already carries
        // the upside: as real activity accumulates, "what is burned plus resting for the
        // rest of the day" rises on its own and overtakes the typical day exactly when the
        // activity is large enough to be certain of. A genuinely big day is still credited
        // in full, just as it happens rather than in advance, so the budget grows through
        // the evening instead of shrinking.
        val creditedSurplus = minOf(surplus, 0.0)
        val estimate = typicalDay + creditedSurplus

        // Whatever the model says, the rest of today cannot burn less than resting.
        val restOfDayFloor = correctedBurnedSoFar + correctedBmrPerDay * (1.0 - dayFraction)
        val projectedBurn = maxOf(estimate, restOfDayFloor)

        // With banking on, the week is the unit: yesterday's restraint pays for today.
        val banked = if (settings.goal.useWeeklyBanking) bankedAdjustmentKcal else 0.0

        // A goal aggressive enough to push the budget under the intake floor loses to the
        // floor, and the UI says so rather than quietly serving a smaller number.
        val goalBudget = projectedBurn + settings.goal.dailyEnergyDelta + banked
        val floor = settings.goal.minIntakeFloorKcal.toDouble()
        val budget = maxOf(goalBudget, floor)

        val rawIntake = snapshot.intakeKcal ?: 0.0
        val intake = if (calibrating) rawIntake * settings.calibration.intakeFactor else rawIntake
        val remaining = budget - intake
        val moveToClear = if (remaining < 0) -remaining else 0.0

        return DayEnergy(
            intakeKcal = intake,
            rawIntakeKcal = rawIntake,
            burnedSoFarKcal = burnedSoFar,
            projectedBurnKcal = projectedBurn,
            bmrPerDayKcal = correctedBmrPerDay,
            typicalDayKcal = typicalDay,
            confidence = observedFraction,
            surplusVsTypicalKcal = surplus,
            baselineDays = effectiveBaseline.sampleDays,
            budgetKcal = budget,
            bankedAdjustmentKcal = banked,
            calibrationApplied = calibrating,
            intakeFloorApplied = goalBudget < floor,
            remainingKcal = remaining,
            moveKcalToClear = moveToClear,
            walkMinutesToClear = (moveToClear / walkKcalPerMinute(weightKg)).roundToInt(),
            weightKg = weightKg,
            daysSinceWeighIn = snapshot.lastWeighInAt
                ?.let { Duration.between(it, now).toDays().toInt().coerceAtLeast(0) },
            source = source,
            hasNutritionData = snapshot.intakeKcal != null,
            updatedAt = now,
        )
    }
}
