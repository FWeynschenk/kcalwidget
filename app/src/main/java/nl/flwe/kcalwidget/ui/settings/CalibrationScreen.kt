package nl.flwe.kcalwidget.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.health.connect.client.PermissionController
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.data.history.HistoryDiagnostics
import nl.flwe.kcalwidget.data.settings.LoggingAccuracy
import nl.flwe.kcalwidget.data.weight.Calibration
import nl.flwe.kcalwidget.data.weight.CalibrationResult
import nl.flwe.kcalwidget.data.weight.DivergencePoint
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.ChartLegend
import nl.flwe.kcalwidget.ui.components.ChoiceRow
import nl.flwe.kcalwidget.ui.components.DateAxis
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.ui.components.StatRow
import nl.flwe.kcalwidget.ui.components.VerticalAxis
import kotlin.math.abs
import kotlin.math.roundToInt

private val PREDICTED = Color(0xFF3F51B5)
private val ACTUAL = Color(0xFF1B5E20)

@Composable
fun CalibrationScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = state.calibration
    val calibration = state.settings.calibration

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.load() }

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    SettingsScaffold("Calibration", onBack) {
        if (!state.hasHistoryPermission) {
            item {
                SectionCard("Needs older data") {
                    Text(
                        "Health Connect only hands over the last 30 days unless an app asks " +
                            "for more. Calibration compares weeks of days against your weight " +
                            "trend, so it works far better with the full history.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            permissionLauncher.launch(setOf(HealthRepository.HISTORY_PERMISSION))
                        }
                    }) { Text("Allow reading older data") }
                }
            }
        }
        item {
            SectionCard("What this does") {
                Explainer(
                    "Over a few weeks, your weight change and your energy balance have to " +
                        "agree. Where they do not, one of the inputs is off — and the scale " +
                        "is the only ground truth available to check them against."
                )
            }
        }

        item {
            when {
                state.historyLoading && result == null ->
                    SectionCard("Status") { Text("Reading your history…") }

                result is CalibrationResult.NotEnoughData ->
                    SectionCard("Not yet") {
                        Text(
                            Calibration.describeGap(result.gap),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                result is CalibrationResult.Agrees ->
                    SectionCard("Everything agrees") {
                        Text(
                            "Your weight is tracking the numbers to within " +
                                "${abs(result.kcalPerDay).roundToInt()} kcal a day, which is " +
                                "inside the noise of a bathroom scale. Nothing to correct.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                result is CalibrationResult.Bias ->
                    BiasCard(result, state.settings.goal.travelDirection)

                else -> SectionCard("Status") { Text("Open this screen to run a check.") }
            }
        }

        if (result is CalibrationResult.Bias) {
            state.history?.let { history ->
                item { DivergenceCard(Calibration.divergence(history)) }
            }
        }

        if (result is CalibrationResult.Bias) {
            // The question only makes sense once there is a discrepancy to explain, so it
            // is asked here rather than buried in setup before it means anything.
            item { AccuracyCard(viewModel, calibration.loggingAccuracy) }
            item { ApplyCard(viewModel, result, state.settings.features.autoCalibration) }
        }

        item {
            DiagnosticsCard(
                diagnostics = state.history?.diagnostics,
                loading = state.historyLoading,
                error = state.historyError,
            )
        }

        item {
            SectionCard("Current correction") {
                StatRow("Burn", "x${"%.3f".format(calibration.expenditureFactor)}")
                StatRow("Intake", "x${"%.3f".format(calibration.intakeFactor)}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Apply automatically",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.settings.features.autoCalibration,
                        onCheckedChange = { on -> viewModel.setAutoCalibration(on) },
                    )
                }
                Explainer(
                    "The check runs once a day in the background, and again whenever you " +
                        "open this screen. With this switch off the app only tells you what " +
                        "it found. With it on, the correction is folded into your budget and " +
                        "kept up to date, moving a quarter of the way each day so it never " +
                        "jumps."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { viewModel.resetCalibration() }) {
                    Text("Reset to raw numbers")
                }
            }
        }
    }
}

/**
 * The disagreement, drawn.
 *
 * One line is where the calories say your weight should have gone, anchored to where you
 * actually were at the start of the window. The other is what the scale said. The gap
 * between them at the right-hand edge is the number above, accumulated.
 */
@Composable
private fun DivergenceCard(series: List<DivergencePoint>) {
    SectionCard("Predicted against actual") {
        if (series.size < 2) {
            Text(
                "Not enough days in the window to draw it yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        val actuals = series.mapNotNull { it.actualKg }
        val all = series.map { it.expectedKg } + actuals
        val min = all.min()
        val max = all.max()
        val span = (max - min).coerceAtLeast(0.5)

        val chartHeight = 160.dp
        VerticalAxis(
            top = "%.1f kg".format(max),
            bottom = "%.1f kg".format(min),
            chartHeight = chartHeight,
            below = { DateAxis(series.first().date, series.last().date) },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
                fun x(index: Int) = index.toFloat() / (series.size - 1) * size.width
                fun y(kg: Double) = (1f - ((kg - min) / span).toFloat()) * size.height * 0.88f +
                    size.height * 0.06f

                // Predicted: a continuous line, because every day contributes to it.
                for (i in 0 until series.size - 1) {
                    drawLine(
                        color = PREDICTED,
                        start = Offset(x(i), y(series[i].expectedKg)),
                        end = Offset(x(i + 1), y(series[i + 1].expectedKg)),
                        strokeWidth = 4f,
                    )
                }

                // Actual: only where it was measured, joined between weigh-ins.
                val measured = series.withIndex().filter { it.value.actualKg != null }
                for (i in 0 until measured.size - 1) {
                    val (ai, a) = measured[i]
                    val (bi, b) = measured[i + 1]
                    drawLine(
                        color = ACTUAL,
                        start = Offset(x(ai), y(a.actualKg!!)),
                        end = Offset(x(bi), y(b.actualKg!!)),
                        strokeWidth = 5f,
                    )
                }
                measured.forEach { (index, point) ->
                    drawCircle(
                        color = ACTUAL,
                        radius = 5f,
                        center = Offset(x(index), y(point.actualKg!!)),
                    )
                }
            }
        }
        ChartLegend(
            listOf(
                PREDICTED to "Predicted by calories",
                ACTUAL to "Measured weight",
            )
        )
        Spacer(Modifier.height(8.dp))
        Explainer(
            "Blue is what your food and burn numbers predict. Green is your smoothed " +
                "weight. They start together; how far apart they end is the difference " +
                "being measured."
        )
    }
}

private fun Double.perWeek(): String =
    "${if (this >= 0) "+" else ""}${"%.2f".format(this)} kg/week"

/**
 * What the last read actually saw.
 *
 * Health Connect fails quietly and in several different ways, and "no history" reads the
 * same whether the permission was refused, the provider threw, or there genuinely is no
 * data. This says which.
 */
@Composable
private fun DiagnosticsCard(
    diagnostics: HistoryDiagnostics?,
    loading: Boolean,
    error: String?,
) {
    SectionCard("What was read") {
        if (diagnostics == null) {
            Text(
                text = when {
                    loading -> "Reading\u2026"
                    error != null -> "The read did not finish."
                    else -> "Nothing has been read yet."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall)
            }
            return@SectionCard
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        StatRow("Asked for", "${diagnostics.requestedDays} days")
        StatRow("Allowed to read", "${diagnostics.allowedDays} days")
        StatRow("Older-data permission", if (diagnostics.hasHistoryPermission) "yes" else "no")
        StatRow("Days with food", diagnostics.intakeDays.toString())
        StatRow("Days with burn", diagnostics.burnDays.toString())
        StatRow("Weigh-ins found", diagnostics.weighInCount.toString())

        if (diagnostics.weightSample.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Most recent weights", style = MaterialTheme.typography.titleSmall)
            diagnostics.weightSample.reversed().forEach { (date, kg) ->
                StatRow(date.toString(), "${"%.2f".format(kg)} kg")
            }
        }

        if (diagnostics.timings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Timings", style = MaterialTheme.typography.titleSmall)
            diagnostics.timings.forEach { timing ->
                Text(timing, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (diagnostics.errors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Errors", style = MaterialTheme.typography.titleSmall)
            diagnostics.errors.forEach { error ->
                Text(error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BiasCard(
    result: CalibrationResult.Bias,
    direction: nl.flwe.kcalwidget.data.settings.GoalDirection,
) {
    SectionCard("Found a difference") {
        Text(
            text = "${abs(result.kcalPerDay).roundToInt()} kcal/day",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = Calibration.explain(result.kcalPerDay, direction),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        StatRow("Your weight is doing", result.observedWeeklyKg.perWeek())
        StatRow("The numbers predict", result.expectedWeeklyKg.perWeek())
        Spacer(Modifier.height(12.dp))
        StatRow("Blamed on burn", "${result.expenditureShareKcal.roundToInt()} kcal/day")
        StatRow("Blamed on food logging", "${result.intakeShareKcal.roundToInt()} kcal/day")
        StatRow("Days compared", result.windowDays.toString())
        StatRow("Weigh-ins", result.weighIns.toString())
        StatRow("Days with food logged", "${(result.intakeCoverage * 100).roundToInt()}%")
        Spacer(Modifier.height(8.dp))
        Explainer(
            "The split follows how much you say you trust your own logging. By default " +
                "most of it lands on burn, because a tracker's estimate is the softer number."
        )
    }
}

@Composable
private fun AccuracyCard(viewModel: MainViewModel, current: LoggingAccuracy?) {
    SectionCard("How accurate is your food logging?") {
        Explainer(
            "This decides how the difference is split. Answer honestly — nothing here is " +
                "a judgement, it just changes which number gets corrected."
        )
        Spacer(Modifier.height(8.dp))
        LoggingAccuracy.entries.forEach { accuracy ->
            ChoiceRow(
                label = accuracy.describe(),
                selected = current == accuracy,
            ) { viewModel.setLoggingAccuracy(accuracy) }
        }
        if (current == null) {
            Spacer(Modifier.height(8.dp))
            Explainer("Until you answer, the app assumes your logging is good and blames burn.")
        }
    }
}

@Composable
private fun ApplyCard(
    viewModel: MainViewModel,
    result: CalibrationResult.Bias,
    autoOn: Boolean,
) {
    SectionCard("Apply it") {
        StatRow("Burn would become", "x${"%.3f".format(result.suggestedExpenditureFactor)}")
        StatRow("Intake would become", "x${"%.3f".format(result.suggestedIntakeFactor)}")
        Spacer(Modifier.height(8.dp))
        Explainer(
            "Corrections are capped at ${(Calibration.MAX_FACTOR_DRIFT * 100).roundToInt()}% " +
                "and eased in ${(Calibration.EASING * 100).roundToInt()}% at a time, so your " +
                "budget never jumps because of one fortnight's data."
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { viewModel.applyCalibration(result) }) {
            Text(if (autoOn) "Update the correction" else "Apply and turn on")
        }
    }
}

private fun LoggingAccuracy.describe(): String = when (this) {
    LoggingAccuracy.WEIGHED -> "Virtually all of it, weighed"
    LoggingAccuracy.MOSTLY -> "Mostly accurate"
    LoggingAccuracy.BEST_EFFORT -> "I do my best"
    LoggingAccuracy.ROUGH -> "Rough estimates"
}
