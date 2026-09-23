package nl.flwe.kcalwidget.data.weight

import nl.flwe.kcalwidget.data.settings.GoalDirection
import kotlin.math.abs

/** A suggested target, derived from where the person is now. */
data class GoalPreset(
    val label: String,
    val targetKg: Double,
    val rationale: String,
)

/**
 * Turns BMI bands into milestones worth aiming at, in whichever direction applies.
 *
 * Someone with obesity is not well served by being pointed at a healthy BMI thirty kilos
 * away; the next band down is a target they can actually reach, and a new one is offered
 * once they get there. The same logic runs in reverse for someone underweight — gaining
 * towards a healthy weight is a first-class path here, not an afterthought.
 */
object WeightGoal {

    /** Above this share of body weight per week, the rate is worth warning about. */
    const val AGGRESSIVE_WEEKLY_FRACTION = 0.01

    fun presets(currentKg: Double, heightCm: Int): List<GoalPreset> {
        val bmi = Bmi.value(currentKg, heightCm)
        return when {
            bmi >= Bmi.HEALTHY_HIGH -> losingPresets(bmi, currentKg, heightCm)
            bmi < Bmi.HEALTHY_LOW -> gainingPresets(currentKg, heightCm)
            else -> maintainingPresets(currentKg, heightCm)
        }
    }

    private fun losingPresets(bmi: Double, currentKg: Double, heightCm: Int): List<GoalPreset> {
        // The next band down first: a reachable step beats a distant ideal.
        val nextBandBmi = BmiBand.entries
            .map { it.lowerBound }
            .filter { it < bmi && it > 0.0 }
            .maxOrNull()

        val candidates = buildList {
            nextBandBmi?.let {
                add(
                    GoalPreset(
                        label = "Out of ${Bmi.bandFor(bmi).label.lowercase()}",
                        targetKg = Bmi.weightForBmi(it, heightCm),
                        rationale = "The next band down, at BMI $it.",
                    )
                )
            }
            add(
                GoalPreset(
                    label = "Top of healthy",
                    targetKg = Bmi.weightForBmi(Bmi.HEALTHY_HIGH, heightCm),
                    rationale = "BMI ${Bmi.HEALTHY_HIGH}, the upper edge of the healthy range.",
                )
            )
            add(
                GoalPreset(
                    label = "Middle of healthy",
                    targetKg = Bmi.weightForBmi(Bmi.HEALTHY_MID, heightCm),
                    rationale = "BMI ${Bmi.HEALTHY_MID}, comfortably inside the healthy range.",
                )
            )
        }
        return candidates.filter { it.targetKg < currentKg - MIN_STEP_KG }.distinctTarget()
    }

    private fun gainingPresets(currentKg: Double, heightCm: Int): List<GoalPreset> = buildList {
        add(
            GoalPreset(
                label = "Into healthy",
                targetKg = Bmi.weightForBmi(Bmi.HEALTHY_LOW, heightCm),
                rationale = "BMI ${Bmi.HEALTHY_LOW}, the lower edge of the healthy range.",
            )
        )
        add(
            GoalPreset(
                label = "Clear of the edge",
                targetKg = Bmi.weightForBmi(20.0, heightCm),
                rationale = "BMI 20, a little margin above the boundary.",
            )
        )
        add(
            GoalPreset(
                label = "Middle of healthy",
                targetKg = Bmi.weightForBmi(Bmi.HEALTHY_MID, heightCm),
                rationale = "BMI ${Bmi.HEALTHY_MID}, comfortably inside the healthy range.",
            )
        )
    }.filter { it.targetKg > currentKg + MIN_STEP_KG }.distinctTarget()

    private fun maintainingPresets(currentKg: Double, heightCm: Int): List<GoalPreset> = buildList {
        add(
            GoalPreset(
                label = "Stay here",
                targetKg = currentKg,
                rationale = "Already in the healthy range; hold the line.",
            )
        )
        val mid = Bmi.weightForBmi(Bmi.HEALTHY_MID, heightCm)
        if (abs(mid - currentKg) > MIN_STEP_KG) {
            add(
                GoalPreset(
                    label = "Middle of healthy",
                    targetKg = mid,
                    rationale = "BMI ${Bmi.HEALTHY_MID}.",
                )
            )
        }
    }

    private fun List<GoalPreset>.distinctTarget(): List<GoalPreset> =
        distinctBy { (it.targetKg * 10).toInt() }

    /**
     * A rate that will get there without being punishing: half a percent of body weight a
     * week for loss, half that for gain, since gaining faster is mostly fat anyway.
     */
    fun suggestedWeeklyRate(currentKg: Double, direction: GoalDirection): Double = when (direction) {
        GoalDirection.LOSE -> -(currentKg * 0.005).coerceIn(0.25, 0.75)
        GoalDirection.GAIN -> (currentKg * 0.0025).coerceIn(0.15, 0.4)
        GoalDirection.MAINTAIN -> 0.0
    }

    /** True when the rate would be hard to sustain or mostly cost muscle. */
    fun isAggressive(weeklyChangeKg: Double, currentKg: Double): Boolean =
        abs(weeklyChangeKg) > currentKg * AGGRESSIVE_WEEKLY_FRACTION

    /** True when a target sits below the healthy range, which is worth saying out loud. */
    fun isBelowHealthy(targetKg: Double, heightCm: Int): Boolean =
        Bmi.value(targetKg, heightCm) < Bmi.HEALTHY_LOW

    /**
     * Whether the target has been reached, judged on the trend rather than a single
     * reading so one light morning cannot declare victory.
     */
    fun isReached(trendKg: Double, targetKg: Double, direction: GoalDirection): Boolean =
        when (direction) {
            GoalDirection.LOSE -> trendKg <= targetKg
            GoalDirection.GAIN -> trendKg >= targetKg
            GoalDirection.MAINTAIN -> false
        }

    /** Weeks to the target at the current rate, or null when it is not heading there. */
    fun weeksToTarget(currentKg: Double, targetKg: Double, weeklyChangeKg: Double): Double? {
        if (weeklyChangeKg == 0.0) return null
        val weeks = (targetKg - currentKg) / weeklyChangeKg
        return if (weeks > 0) weeks else null
    }

    /** Ignore differences smaller than this; they are inside the noise of a scale. */
    private const val MIN_STEP_KG = 0.5
}
