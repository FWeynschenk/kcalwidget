package nl.flwe.kcalwidget.data.settings

import nl.flwe.kcalwidget.data.Energetics
import java.time.LocalDate

/**
 * Everything the user can configure, grouped by what it affects.
 *
 * These are small records kept in one file on purpose: they are read together, and a
 * dozen ten-line files would be harder to scan than one page. [SettingsRepository] is the
 * only thing that knows how they are persisted.
 */
data class AppSettings(
    val body: BodyProfile = BodyProfile(),
    val goal: GoalSettings = GoalSettings(),
    val sources: SourceSettings = SourceSettings(),
    val calculation: CalculationSettings = CalculationSettings(),
    val features: FeatureFlags = FeatureFlags(),
    val calibration: CalibrationState = CalibrationState(),
    val reminders: ReminderState = ReminderState(),
    val onboardingCompleted: Boolean = false,
)

enum class Sex { MALE, FEMALE }

/** Body metrics Health Connect cannot supply, or can only supply sometimes. */
data class BodyProfile(
    val sex: Sex = Sex.MALE,
    val birthYear: Int = 1995,
    val heightCm: Int = 180,
    /** Used only when Health Connect has no recent WeightRecord. */
    val fallbackWeightKg: Double = 80.0,
) {
    val age: Int get() = (LocalDate.now().year - birthYear).coerceIn(10, 110)
}

enum class GoalMode {
    /** The user sets a rate directly and the app does not care where it ends. */
    RATE,

    /** The user sets a target weight; the rate carries them towards it. */
    TARGET_WEIGHT,
}

data class GoalSettings(
    val mode: GoalMode = GoalMode.RATE,
    /** Target weight change per week. Negative loses, positive gains. */
    val weeklyChangeKg: Double = -0.5,
    val targetWeightKg: Double? = null,
    /**
     * Budget against a rolling seven-day total instead of each day on its own, so a big
     * Saturday is paid for across the week.
     */
    val useWeeklyBanking: Boolean = false,
    /** The budget is never presented below this without saying so. */
    val minIntakeFloorKcal: Int = 1200,
    /** When the target was last reached, so the celebration is shown once. */
    val goalAchievedAt: Long? = null,
) {
    /** Daily energy delta implied by the goal, in kcal. Negative is a deficit. */
    val dailyEnergyDelta: Double
        get() = weeklyChangeKg * Energetics.KCAL_PER_KG_FAT / 7.0

    /**
     * The direction the goal is actually travelling, taken from the rate rather than from
     * where you happen to be standing.
     *
     * Position-derived direction flips the instant you cross the target, which makes
     * arriving indistinguishable from never having set off — the milestone would never
     * fire, in either direction.
     */
    val travelDirection: GoalDirection
        get() = when {
            weeklyChangeKg < 0 -> GoalDirection.LOSE
            weeklyChangeKg > 0 -> GoalDirection.GAIN
            else -> GoalDirection.MAINTAIN
        }

    /** Whether the target has been reached, judged along the direction of travel. */
    fun isReached(trendKg: Double): Boolean {
        val target = targetWeightKg ?: return false
        return when (travelDirection) {
            GoalDirection.LOSE -> trendKg <= target
            GoalDirection.GAIN -> trendKg >= target
            GoalDirection.MAINTAIN -> kotlin.math.abs(trendKg - target) <= DIRECTION_DEADBAND
        }
    }

    /** Which way the target lies from a given weight, or null when there is no target. */
    fun direction(currentWeightKg: Double): GoalDirection? {
        val target = targetWeightKg ?: return null
        return when {
            target < currentWeightKg - DIRECTION_DEADBAND -> GoalDirection.LOSE
            target > currentWeightKg + DIRECTION_DEADBAND -> GoalDirection.GAIN
            else -> GoalDirection.MAINTAIN
        }
    }

    companion object {
        /** Within this much of target, the goal counts as maintenance rather than a direction. */
        const val DIRECTION_DEADBAND = 0.3
    }
}

enum class GoalDirection { LOSE, GAIN, MAINTAIN }

/** A Health Connect data type this app reads, and can be pointed at specific writers. */
enum class HealthMetric {
    NUTRITION,
    TOTAL_BURN,
    ACTIVE_BURN,
    STEPS,
    WEIGHT,
    BASAL,
}

/**
 * Which apps to accept data from, per metric. An absent or empty entry means every app,
 * which is the default and the right answer for most metrics — it only needs narrowing
 * where two writers would be summed into a double count.
 */
data class SourceSettings(
    val selections: Map<HealthMetric, Set<String>> = emptyMap(),
) {
    fun packagesFor(metric: HealthMetric): Set<String> = selections[metric].orEmpty()

    fun with(metric: HealthMetric, packages: Set<String>): SourceSettings =
        copy(selections = selections + (metric to packages))
}

data class CalculationSettings(
    /** Prefer TotalCaloriesBurned over resting rate plus active calories. */
    val preferHcTotal: Boolean = true,
    /** Prefer a BasalMetabolicRateRecord over the Mifflin-St Jeor estimate. */
    val preferHcBasal: Boolean = true,
    /**
     * The hour a day is considered to start. 0 is midnight; 4 keeps a late dinner on the
     * day it belongs to. Changing this invalidates the learned intraday curve, which is
     * indexed in the same frame.
     */
    val dayStartHour: Int = 0,
)

data class FeatureFlags(
    /** Let calibration move the budget by itself rather than only advising. */
    val autoCalibration: Boolean = false,
    /** Show a weigh-in nudge on the widgets that have room for it. */
    val widgetWeighInNudge: Boolean = true,
    /** Post a system notification when a weigh-in is due. Opt-in. */
    val weighInNotification: Boolean = false,
    val weighInIntervalDays: Int = 7,
)

/** When things were last said, so a daily check does not become daily nagging. */
data class ReminderState(
    val lastWeighInNotifiedAt: Long = 0L,
)

/** How much of the user's food logging the app should believe. */
enum class LoggingAccuracy(val intakeTrust: Double) {
    /** Weighed and logged; if the numbers disagree, suspect the tracker. */
    WEIGHED(0.95),
    MOSTLY(0.80),
    BEST_EFFORT(0.55),
    ROUGH(0.30),
}

data class CalibrationState(
    /** Null until a discrepancy is found and the question is worth asking. */
    val loggingAccuracy: LoggingAccuracy? = null,
    /** Last measured disagreement between the scale and the numbers, kcal/day. */
    val lastBiasKcal: Double? = null,
    val expenditureFactor: Double = 1.0,
    val intakeFactor: Double = 1.0,
    val lastComputedAt: Long = 0L,
    val lastPromptedAt: Long = 0L,
) {
    /**
     * Until the user says otherwise, blame expenditure: a tracker's burn estimate is the
     * softer number, and it is the less accusatory default.
     */
    val intakeTrust: Double get() = loggingAccuracy?.intakeTrust ?: DEFAULT_INTAKE_TRUST

    companion object {
        const val DEFAULT_INTAKE_TRUST = 0.85
    }
}
