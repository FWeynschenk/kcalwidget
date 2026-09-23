package nl.flwe.kcalwidget.ui.wizard

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.HealthAvailability
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.data.settings.GoalDirection
import nl.flwe.kcalwidget.data.settings.GoalMode
import nl.flwe.kcalwidget.data.settings.HealthMetric
import nl.flwe.kcalwidget.data.settings.LoggingAccuracy
import nl.flwe.kcalwidget.data.settings.Sex
import nl.flwe.kcalwidget.data.sources.SourceDiscovery
import nl.flwe.kcalwidget.data.weight.Bmi
import nl.flwe.kcalwidget.data.weight.Calibration
import nl.flwe.kcalwidget.data.weight.CalibrationResult
import nl.flwe.kcalwidget.data.weight.WeightGoal
import nl.flwe.kcalwidget.notify.Notifications
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.ChoiceRow
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.NumberField
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.ui.components.StatRow
import nl.flwe.kcalwidget.widget.KcalWidgetReceiver
import nl.flwe.kcalwidget.widget.MinimalWidgetReceiver
import nl.flwe.kcalwidget.widget.TrendWidgetReceiver
import kotlin.math.abs
import kotlin.math.roundToInt

private const val STEP_COUNT = 9

/**
 * First-run setup.
 *
 * Every step is also reachable from Settings afterwards; the wizard exists so none of it
 * has to be found by poking. It can be re-run at any time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(viewModel: MainViewModel, onFinish: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.load() }

    LaunchedEffect(step) {
        when (step) {
            2 -> viewModel.discoverSources()
            // Most people arrive with months of history already in Health Connect, so this
            // usually has a real answer on day one rather than a promise about later.
            7 -> viewModel.loadHistory()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setting up") },
                actions = {
                    TextButton(onClick = {
                        viewModel.updateSettings { it.copy(onboardingCompleted = true) }
                        onFinish()
                    }) { Text("Skip") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { (step + 1f) / STEP_COUNT },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    0 -> item { WelcomeStep() }
                    1 -> item {
                        PermissionStep(state.availability, state.hasPermissions,
                            state.hasBackgroundPermission) { permissions ->
                            runCatching { permissionLauncher.launch(permissions) }
                        }
                    }
                    2 -> item { SourcesStep(viewModel) }
                    3 -> item { BodyStep(viewModel) }
                    4 -> item { GoalStep(viewModel) }
                    5 -> item { RemindersStep(viewModel) }
                    6 -> item { WidgetsStep() }
                    7 -> item { CalibrationStep(viewModel) }
                    else -> item { DoneStep() }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = { if (step > 0) step-- }, enabled = step > 0) {
                    Text("Back")
                }
                Button(
                    onClick = {
                        if (step < STEP_COUNT - 1) {
                            step++
                        } else {
                            viewModel.updateSettings { it.copy(onboardingCompleted = true) }
                            onFinish()
                        }
                    },
                    // Everything downstream reads Health Connect, so there is nothing
                    // useful to configure until access is granted.
                    enabled = step != 1 || state.hasPermissions,
                ) { Text(if (step < STEP_COUNT - 1) "Next" else "Done") }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    SectionCard("Kcal Balance") {
        Explainer(
            "This shows what you have eaten, what you are likely to burn by midnight, and " +
                "how much room that leaves. Everything comes from Health Connect."
        )
        Spacer(Modifier.height(12.dp))
        Explainer(
            "It is read-only: the app never writes health data, has no network access, and " +
                "nothing leaves your phone. Setting up takes about a minute."
        )
    }
}

@Composable
private fun PermissionStep(
    availability: HealthAvailability,
    hasPermissions: Boolean,
    hasBackground: Boolean,
    request: (Set<String>) -> Unit,
) {
    SectionCard("Health Connect") {
        when {
            availability != HealthAvailability.AVAILABLE -> Explainer(
                "Health Connect is not available on this device, so there is nothing to read."
            )

            !hasPermissions -> {
                Explainer(
                    "The app needs to read today's food, calories burned, steps, resting rate " +
                        "and weight. It never writes anything back."
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { request(HealthRepository.REQUIRED_PERMISSIONS) }) {
                    Text("Grant access")
                }
            }

            else -> {
                Text("Access granted.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (!hasBackground) {
                    Explainer(
                        "One more, optional: background reads let the widget stay current " +
                            "when the app is closed. Without it, it updates when you open " +
                            "the app or tap refresh."
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { request(setOf(HealthRepository.BACKGROUND_PERMISSION)) }
                    ) { Text("Allow background reads") }
                } else {
                    Explainer("Background reads allowed; the widget will stay current.")
                }
            }
        }
    }
}

@Composable
private fun SourcesStep(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SectionCard("Where the numbers come from") {
        Explainer(
            "Health Connect adds up every app that writes a value, which double-counts when " +
                "two trackers log the same walk. Pick one for those."
        )
        Spacer(Modifier.height(12.dp))
        if (state.discoveringSources) {
            Text("Looking for apps…", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        if (state.sourceCatalog == null) {
            // Giving up is not the same as finding nothing, and the difference matters:
            // one needs a retry, the other needs no action at all.
            Text(
                "The scan timed out. Health Connect is probably busy.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { viewModel.discoverSources(forceRefresh = true) }) {
                Text("Try again")
            }
            Spacer(Modifier.height(8.dp))
            Explainer("You can skip this and set it later in Settings, under Data sources.")
            return@SectionCard
        }
        SourceDiscovery.SINGLE_WRITER_METRICS.forEach { metric ->
            val options = state.sourceCatalog?.optionsFor(metric).orEmpty()
            if (options.size < 2) return@forEach
            Text(
                SourceDiscovery.displayName(metric),
                style = MaterialTheme.typography.titleSmall,
            )
            val selected = state.settings.sources.packagesFor(metric)
            options.forEach { option ->
                ChoiceRow(
                    label = option.label +
                        (if (option.recommended) "  (recommended)" else "") +
                        "  ${option.daysWithData} days",
                    selected = selected == setOf(option.packageName),
                ) {
                    viewModel.updateSettings {
                        it.copy(sources = it.sources.with(metric, setOf(option.packageName)))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val anyConflict = SourceDiscovery.SINGLE_WRITER_METRICS.any {
            (state.sourceCatalog?.optionsFor(it)?.size ?: 0) >= 2
        }
        if (!anyConflict) {
            Explainer("Only one app writes each of these, so there is nothing to choose.")
        } else {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                viewModel.updateSettings { settings ->
                    var next = settings
                    SourceDiscovery.SINGLE_WRITER_METRICS.forEach { metric ->
                        val pick = state.sourceCatalog?.optionsFor(metric)
                            ?.firstOrNull { it.recommended }
                        if (pick != null) {
                            next = next.copy(
                                sources = next.sources.with(metric, setOf(pick.packageName))
                            )
                        }
                    }
                    next
                }
            }) { Text("Use recommended") }
        }
    }
}

@Composable
private fun BodyStep(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val body = state.settings.body
    SectionCard("About you") {
        Explainer("Used for your resting rate and BMI. Weight comes from Health Connect.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sex.entries.forEach { sex ->
                FilterChip(
                    selected = body.sex == sex,
                    onClick = {
                        viewModel.updateSettings { it.copy(body = it.body.copy(sex = sex)) }
                    },
                    label = { Text(if (sex == Sex.MALE) "Male" else "Female") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        NumberField("Birth year", body.birthYear.toString()) { text ->
            text.toIntOrNull()?.let { y ->
                viewModel.updateSettings { it.copy(body = it.body.copy(birthYear = y)) }
            }
        }
        NumberField("Height (cm)", body.heightCm.toString()) { text ->
            text.toIntOrNull()?.let { cm ->
                viewModel.updateSettings { it.copy(body = it.body.copy(heightCm = cm)) }
            }
        }
        state.energy?.let { StatRow("Weight from Health Connect", "${"%.1f".format(it.weightKg)} kg") }
    }
}

@Composable
private fun GoalStep(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val height = state.settings.body.heightCm
    val current = state.history?.trend?.currentTrendKg
        ?: state.energy?.weightKg
        ?: state.settings.body.fallbackWeightKg
    val presets = WeightGoal.presets(current, height)
    val goal = state.settings.goal

    SectionCard("Your goal") {
        StatRow("Now", "${"%.1f".format(current)} kg, BMI ${"%.1f".format(Bmi.value(current, height))}")
        Spacer(Modifier.height(8.dp))
        if (presets.isEmpty()) {
            Explainer("You are already in the healthy range. Set a rate if you want to move.")
        } else {
            Explainer("Milestones for your height. Pick one and the rate follows.")
            presets.forEach { preset ->
                ChoiceRow(
                    label = "${preset.label} — ${"%.1f".format(preset.targetKg)} kg",
                    selected = goal.targetWeightKg?.let { abs(it - preset.targetKg) < 0.05 } == true,
                ) {
                    val direction = if (preset.targetKg < current) {
                        GoalDirection.LOSE
                    } else {
                        GoalDirection.GAIN
                    }
                    viewModel.updateSettings {
                        it.copy(
                            goal = it.goal.copy(
                                mode = GoalMode.TARGET_WEIGHT,
                                targetWeightKg = preset.targetKg,
                                weeklyChangeKg = WeightGoal.suggestedWeeklyRate(current, direction),
                                goalAchievedAt = null,
                            )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${"%.2f".format(goal.weeklyChangeKg)} kg/week, " +
                "${goal.dailyEnergyDelta.roundToInt()} kcal/day",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = goal.weeklyChangeKg.toFloat(),
            onValueChange = { v ->
                viewModel.updateSettings {
                    it.copy(goal = it.goal.copy(weeklyChangeKg = (v * 20f).roundToInt() / 20.0))
                }
            },
            valueRange = -1f..0.5f,
            steps = 29,
        )
        if (WeightGoal.isAggressive(goal.weeklyChangeKg, current)) {
            Explainer(
                "That is over 1% of your body weight a week — hard to hold, and it tends to " +
                    "cost muscle as well as fat."
            )
        }
    }
}

@Composable
private fun RemindersStep(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val features = state.settings.features
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.updateSettings {
            it.copy(features = it.features.copy(weighInNotification = granted))
        }
    }

    SectionCard("Weighing in") {
        Explainer(
            "The app checks its own numbers against your weight, so it needs a weigh-in now " +
                "and then."
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Remind me every ${features.weighInIntervalDays} days",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = features.weighInIntervalDays.toFloat(),
            onValueChange = { v ->
                viewModel.updateSettings {
                    it.copy(
                        features = it.features.copy(
                            weighInIntervalDays = v.roundToInt().coerceIn(1, 30)
                        )
                    )
                }
            },
            valueRange = 1f..30f,
            steps = 28,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Notify me", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Off by default. The widgets will show a line either way.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = features.weighInNotification,
                onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !Notifications.canPost(context)
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.updateSettings {
                            it.copy(features = it.features.copy(weighInNotification = on))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun WidgetsStep() {
    val context = LocalContext.current
    SectionCard("Add a widget") {
        Explainer("Four to choose from; you can add more later from Settings.")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { pin(context, KcalWidgetReceiver::class.java) }) {
            Text("Balance — the usual one")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pin(context, MinimalWidgetReceiver::class.java) }) {
            Text("Minimal — just the number")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pin(context, TrendWidgetReceiver::class.java) }) {
            Text("Trend — two weeks at a glance")
        }
    }
}

/**
 * Calibration, configured rather than merely promised.
 *
 * Anyone installing this has usually been logging for months, so the history is already
 * in Health Connect and the check has a real answer straight away. When it does not, the
 * step says precisely what is missing instead of hand-waving at "later".
 */
@Composable
private fun CalibrationStep(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = state.calibration

    SectionCard("Checking against your weight") {
        Explainer(
            "Over a few weeks your weight change and your energy balance have to agree. " +
                "Where they do not, one of the inputs is off, and your scale is the only " +
                "way to tell which."
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.historyLoading && result == null ->
                Text("Reading your history…", style = MaterialTheme.typography.bodyMedium)

            result is CalibrationResult.NotEnoughData -> {
                Text(
                    Calibration.describeGap(result.gap),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Explainer(
                    "Settings runs this again whenever you open Calibration, and will say " +
                        "what it is still waiting for."
                )
            }

            result is CalibrationResult.Agrees -> Text(
                "Your weight is tracking the numbers to within " +
                    "${abs(result.kcalPerDay).roundToInt()} kcal a day. Nothing to correct.",
                style = MaterialTheme.typography.bodyMedium,
            )

            result is CalibrationResult.Bias -> {
                Text(
                    "${abs(result.kcalPerDay).roundToInt()} kcal/day out",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = Calibration.explain(
                        result.kcalPerDay,
                        state.settings.goal.travelDirection,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                StatRow(
                    "Your weight is doing",
                    "${if (result.observedWeeklyKg >= 0) "+" else ""}" +
                        "${"%.2f".format(result.observedWeeklyKg)} kg/week",
                )
                StatRow(
                    "The numbers predict",
                    "${if (result.expectedWeeklyKg >= 0) "+" else ""}" +
                        "${"%.2f".format(result.expectedWeeklyKg)} kg/week",
                )
                StatRow("Measured over", "${result.windowDays} days, ${result.weighIns} weigh-ins")
                Spacer(Modifier.height(12.dp))
                Explainer(
                    "How accurate is your food logging? This decides whether the blame lands " +
                        "on the tracker or on the logging. Nothing here is a judgement."
                )
                LoggingAccuracy.entries.forEach { accuracy ->
                    ChoiceRow(
                        label = accuracy.describe(),
                        selected = state.settings.calibration.loggingAccuracy == accuracy,
                    ) { viewModel.setLoggingAccuracy(accuracy) }
                }
                Spacer(Modifier.height(8.dp))
                StatRow("Blamed on burn", "${result.expenditureShareKcal.roundToInt()} kcal/day")
                StatRow("Blamed on logging", "${result.intakeShareKcal.roundToInt()} kcal/day")
                Spacer(Modifier.height(12.dp))
                if (state.settings.features.autoCalibration) {
                    Text(
                        "Correction is on. You can turn it off in Settings at any time.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Button(onClick = { viewModel.applyCalibration(result) }) {
                        Text("Correct my numbers")
                    }
                    Spacer(Modifier.height(8.dp))
                    Explainer(
                        "Capped at 20% and eased in gradually. Leave it off and the app will " +
                            "simply keep telling you what it finds."
                    )
                }
            }

            else -> Text(
                "Could not check just now. Settings has it under Calibration.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun LoggingAccuracy.describe(): String = when (this) {
    LoggingAccuracy.WEIGHED -> "Virtually all of it, weighed"
    LoggingAccuracy.MOSTLY -> "Mostly accurate"
    LoggingAccuracy.BEST_EFFORT -> "I do my best"
    LoggingAccuracy.ROUGH -> "Rough estimates"
}

@Composable
private fun DoneStep() {
    SectionCard("That's it") {
        Explainer(
            "The widget refreshes when you unlock your phone, and every 15 minutes besides. " +
                "Tap its refresh icon any time you want it now."
        )
        Spacer(Modifier.height(12.dp))
        Explainer(
            "History shows every day's intake, burn and net with your weight line, and " +
                "exports to CSV. Settings also has a later day boundary if you eat past " +
                "midnight, weekly banking to budget across the week rather than per day, " +
                "and a minimum intake the budget will never drop below."
        )
        Spacer(Modifier.height(12.dp))
        Explainer("Everything here can be changed later, and this setup can be run again.")
    }
}

private fun pin(context: Context, receiver: Class<*>) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
    }
}
