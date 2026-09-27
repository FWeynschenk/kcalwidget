package nl.flwe.kcalwidget.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.settings.GoalDirection
import nl.flwe.kcalwidget.data.settings.GoalMode
import nl.flwe.kcalwidget.data.weight.Bmi
import nl.flwe.kcalwidget.data.weight.WeightGoal
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.weight.WeightForecast
import nl.flwe.kcalwidget.data.weight.weeksBetween
import nl.flwe.kcalwidget.ui.MainViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import nl.flwe.kcalwidget.ui.components.ChoiceRow
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.NumberField
import nl.flwe.kcalwidget.ui.components.parseDecimal
import nl.flwe.kcalwidget.ui.components.parseWholeNumber
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.ui.components.StatRow
import kotlin.math.abs
import kotlin.math.roundToInt

private val WARN = Color(0xFFB3261E)

@Composable
fun GoalScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val goal = state.settings.goal
    val heightCm = state.settings.body.heightCm
    val trendKg = state.history?.trend?.currentTrendKg
        ?: state.energy?.weightKg
        ?: state.settings.body.fallbackWeightKg

    // The forecast needs past days, and those are only read automatically when banking
    // is on. Without this the card is permanently empty for everyone else.
    LaunchedEffect(Unit) { viewModel.loadHistory() }

    SettingsScaffold("Goal", onBack) {
        item { WhereYouAreCard(trendKg, heightCm, state.history?.trend?.weeklyChangeKg) }

        item {
            SectionCard("What are you aiming at?") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalMode.entries.forEach { mode ->
                        FilterChip(
                            selected = goal.mode == mode,
                            onClick = {
                                viewModel.updateSettings { it.copy(goal = it.goal.copy(mode = mode)) }
                            },
                            label = {
                                Text(if (mode == GoalMode.RATE) "A rate" else "A target weight")
                            },
                        )
                    }
                }
            }
        }

        if (goal.mode == GoalMode.TARGET_WEIGHT) {
            item { TargetWeightCard(viewModel, trendKg, heightCm) }
        }

        item { ForecastCard(state.history, state.settings) }
        item { RateCard(viewModel, trendKg) }

        item {
            SectionCard("Weekly banking") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Budget across the week",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = goal.useWeeklyBanking,
                        onCheckedChange = { on ->
                            viewModel.updateSettings {
                                it.copy(goal = it.goal.copy(useWeeklyBanking = on))
                            }
                        },
                    )
                }
                Explainer(
                    "With this on, a big day is paid for over the following week: the past " +
                        "six days' overspend comes off today's budget, capped at 700 kcal so " +
                        "one heavy day cannot swallow the week. It only ever tightens. A week " +
                        "that comes in under is reported as spare rather than added on, so a " +
                        "good run does not quietly raise the bar the next day."
                )
            }
        }

        item {
            SectionCard("Minimum intake") {
                NumberField("Never budget below (kcal)", goal.minIntakeFloorKcal.toString()) { text ->
                    parseWholeNumber(text)?.let { kcal ->
                        viewModel.updateSettings {
                            it.copy(goal = it.goal.copy(minIntakeFloorKcal = kcal))
                        }
                    }
                }
                Explainer(
                    "On a low-burn day an aggressive goal can push the budget very low. The " +
                        "budget is never shown below this figure, and the app says when the " +
                        "floor is what you are seeing."
                )
            }
        }
    }
}

@Composable
private fun WhereYouAreCard(trendKg: Double, heightCm: Int, weeklyTrend: Double?) {
    val bmi = Bmi.value(trendKg, heightCm)
    SectionCard("Where you are") {
        StatRow("Trend weight", "${"%.1f".format(trendKg)} kg")
        StatRow("BMI", "%.1f".format(bmi))
        StatRow("Band", Bmi.bandFor(bmi).label)
        if (weeklyTrend != null) {
            StatRow(
                "Actual rate",
                "${if (weeklyTrend >= 0) "+" else ""}${"%.2f".format(weeklyTrend)} kg/week",
            )
        }
        Spacer(Modifier.height(8.dp))
        Explainer(
            "Trend weight is your smoothed weight, not the last thing the scale said. " +
                "BMI ignores build and muscle, so treat the band as a rough signpost rather " +
                "than a verdict."
        )
    }
}

@Composable
private fun TargetWeightCard(viewModel: MainViewModel, trendKg: Double, heightCm: Int) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val goal = state.settings.goal
    val presets = WeightGoal.presets(trendKg, heightCm)
    val target = goal.targetWeightKg

    SectionCard("Target weight") {
        if (presets.isNotEmpty()) {
            Explainer("Milestones for your height, nearest first.")
            Spacer(Modifier.height(4.dp))
            presets.forEach { preset ->
                ChoiceRow(
                    label = "${preset.label} — ${"%.1f".format(preset.targetKg)} kg",
                    selected = target != null && abs(target - preset.targetKg) < 0.05,
                ) {
                    val direction = if (preset.targetKg < trendKg) {
                        GoalDirection.LOSE
                    } else if (preset.targetKg > trendKg) {
                        GoalDirection.GAIN
                    } else {
                        GoalDirection.MAINTAIN
                    }
                    viewModel.updateSettings {
                        it.copy(
                            goal = it.goal.copy(
                                targetWeightKg = preset.targetKg,
                                // A target above you with a losing rate would never arrive.
                                weeklyChangeKg = WeightGoal.suggestedWeeklyRate(trendKg, direction),
                                goalAchievedAt = null,
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        NumberField(
            label = "Or set your own (kg)",
            value = target?.let { "%.1f".format(it) } ?: "",
            decimal = true,
        ) { text ->
            parseDecimal(text)?.takeIf { it in 30.0..300.0 }?.let { kg ->
                viewModel.updateSettings {
                    it.copy(goal = it.goal.copy(targetWeightKg = kg, goalAchievedAt = null))
                }
            }
        }

        if (target != null) {
            val direction = goal.direction(trendKg) ?: GoalDirection.MAINTAIN
            val weeks = WeightGoal.weeksToTarget(trendKg, target, goal.weeklyChangeKg)
            Spacer(Modifier.height(8.dp))
            StatRow("To go", "${"%.1f".format(abs(target - trendKg))} kg")
            if (weeks != null) {
                StatRow("At your chosen rate", "about ${weeks.roundToInt()} weeks")
            } else if (direction != GoalDirection.MAINTAIN) {
                Text(
                    "Your rate is pointing away from this target.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WARN,
                )
            }
            if (WeightGoal.isBelowHealthy(target, heightCm)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This target is below the healthy BMI range for your height. That is " +
                        "your call to make, but it is worth talking to a doctor first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WARN,
                )
            }
            if (goal.isReached(trendKg)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "You have reached this target. Pick the next milestone above.",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/**
 * Where the weight is heading, as a band rather than a promise.
 *
 * Two methods, deliberately both shown: the scale fitted over recent weeks, which is
 * ground truth but cannot know about a change made this week, and the calorie balance,
 * which can but is only as good as the logging. When they agree the range is tight and
 * worth acting on; when they disagree the width is the useful part, and picking one to
 * display would be inventing confidence.
 */
@Composable
private fun ForecastCard(history: History?, settings: AppSettings) {
    val forecast = remember(history, settings) {
        history?.let {
            WeightForecast.from(it.trend, it.rows, settings, LocalDate.now())
        }
    }

    SectionCard("Where this is heading") {
        if (forecast == null) {
            Text(
                "A few weigh-ins are needed before anything can be predicted.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        Explainer(
            "Built from what has actually been happening, not from the rate you chose, so " +
                "it will differ from the figure above whenever the two are apart."
        )
        Spacer(Modifier.height(8.dp))
        listOf(4L, 12L).forEach { weeks ->
            val point = forecast.at(LocalDate.now().plusDays(weeks * 7))
            if (point != null) {
                StatRow(
                    label = "In $weeks weeks",
                    value = if (forecast.hasBand) {
                        "%.1f–%.1f kg".format(point.lowKg, point.highKg)
                    } else {
                        "%.1f kg".format(point.midKg)
                    },
                )
            }
        }

        val target = forecast.targetKg
        val range = forecast.targetRange
        Spacer(Modifier.height(8.dp))
        when {
            target == null -> Explainer(
                "Set a target weight above and this will say when you would reach it."
            )
            range == null -> Explainer(
                "At the moment nothing here reaches ${"%.1f".format(target)} kg: either the " +
                    "rate is too small to project, or it is pointing the other way."
            )
            else -> {
                val (first, last) = range
                StatRow(
                    label = "Reaching ${"%.1f".format(target)} kg",
                    value = if (first == last) {
                        FORECAST_DATE.format(first)
                    } else {
                        "${FORECAST_DATE.format(first)} – ${FORECAST_DATE.format(last)}"
                    },
                )
                Spacer(Modifier.height(8.dp))
                Explainer(
                    buildString {
                        append("That is about ${weeksBetween(LocalDate.now(), first)} weeks ")
                        append("at the faster of the two readings")
                        if (first != last) {
                            append(", ${weeksBetween(LocalDate.now(), last)} at the slower")
                        }
                        append(". ")
                        val gap = forecast.disagreementKgPerWeek
                        append(
                            when {
                                gap == null ->
                                    "Only one method has enough data so far, so there is no " +
                                        "range to compare against."
                                gap < 0.1 ->
                                    "Your scale and your calorie numbers agree closely, so " +
                                        "this is about as firm as a forecast gets."
                                gap < 0.3 ->
                                    "Your scale and your calorie numbers disagree a little, " +
                                        "which is normal."
                                else ->
                                    "Your scale and your calorie numbers disagree by " +
                                        "${"%.2f".format(gap)} kg a week, so treat the range " +
                                        "as wide. Calibration is what narrows it."
                            }
                        )
                    }
                )
            }
        }
    }
}

private val FORECAST_DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun RateCard(viewModel: MainViewModel, trendKg: Double) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val goal = state.settings.goal
    val perWeek = goal.weeklyChangeKg

    SectionCard("Rate of change") {
        Text(
            text = when {
                perWeek < 0 -> "Lose ${"%.2f".format(abs(perWeek))} kg per week"
                perWeek > 0 -> "Gain ${"%.2f".format(perWeek)} kg per week"
                else -> "Maintain weight"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "${goal.dailyEnergyDelta.roundToInt()} kcal per day against your burn",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = perWeek.toFloat(),
            onValueChange = { value ->
                val rounded = (value * 20f).roundToInt() / 20.0
                viewModel.updateSettings { it.copy(goal = it.goal.copy(weeklyChangeKg = rounded)) }
            },
            valueRange = -1f..0.5f,
            steps = 29,
        )
        if (WeightGoal.isAggressive(perWeek, trendKg)) {
            Text(
                "That is more than 1% of your body weight a week. Sustained, a rate this " +
                    "steep tends to cost muscle as well as fat and is hard to hold.",
                style = MaterialTheme.typography.bodyMedium,
                color = WARN,
            )
            Spacer(Modifier.height(4.dp))
        }
        OutlinedButton(onClick = {
            val direction = when {
                perWeek < 0 -> GoalDirection.LOSE
                perWeek > 0 -> GoalDirection.GAIN
                else -> GoalDirection.MAINTAIN
            }
            viewModel.updateSettings {
                it.copy(
                    goal = it.goal.copy(
                        weeklyChangeKg = WeightGoal.suggestedWeeklyRate(trendKg, direction)
                    )
                )
            }
        }) { Text("Use a sustainable rate") }
        Spacer(Modifier.height(4.dp))
        Explainer("Using ${Energetics.KCAL_PER_KG_FAT.roundToInt()} kcal per kg of body fat.")
    }
}
