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
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.ChoiceRow
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.NumberField
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
                    "With this on, a restrained few days pays for a big one. Today's budget " +
                        "carries in the past six days' surplus or deficit, capped at 700 kcal " +
                        "either way so one heavy day cannot swallow the whole week."
                )
            }
        }

        item {
            SectionCard("Minimum intake") {
                NumberField("Never budget below (kcal)", goal.minIntakeFloorKcal.toString()) { text ->
                    text.toIntOrNull()?.let { kcal ->
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

        NumberField("Or set your own (kg)", target?.let { "%.1f".format(it) } ?: "") { text ->
            text.toDoubleOrNull()?.takeIf { it in 30.0..300.0 }?.let { kg ->
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
                StatRow("At this rate", "about ${weeks.roundToInt()} weeks")
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
