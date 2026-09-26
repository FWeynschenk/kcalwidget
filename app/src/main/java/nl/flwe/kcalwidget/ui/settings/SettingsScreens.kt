package nl.flwe.kcalwidget.ui.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.settings.Sex
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.NavRow
import nl.flwe.kcalwidget.ui.components.NumberField
import nl.flwe.kcalwidget.ui.components.parseDecimal
import nl.flwe.kcalwidget.ui.components.parseWholeNumber
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.ui.nav.Routes
import kotlin.math.abs
import kotlin.math.roundToInt

/** One frame for every settings page, so back behaves the same everywhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SettingsIndexScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    SettingsScaffold("Settings", onBack) {
        item {
            NavRow("History", "Every day, and your weight trend") {
                onNavigate(SettingsRoutes.HISTORY)
            }
        }
        item {
            NavRow("Goal", "Rate of change and your minimum intake") {
                onNavigate(SettingsRoutes.GOAL)
            }
        }
        item {
            NavRow("Body", "Sex, age, height and fallback weight") { onNavigate(SettingsRoutes.BODY) }
        }
        item {
            NavRow("Data sources", "Which app feeds each calculation") {
                onNavigate(SettingsRoutes.SOURCES)
            }
        }
        item {
            NavRow("Calculation", "Burn source preference and when a day starts") {
                onNavigate(SettingsRoutes.CALCULATION)
            }
        }
        item {
            NavRow("Calibration", "Check the numbers against your actual weight") {
                onNavigate(SettingsRoutes.CALIBRATION)
            }
        }
        item {
            NavRow("Widgets", "Which widgets to add, and the weigh-in nudge") {
                onNavigate(SettingsRoutes.WIDGETS)
            }
        }
        item {
            NavRow("Reminders", "Weigh-in interval and how you are told") {
                onNavigate(SettingsRoutes.REMINDERS)
            }
        }
        item { NavRow("About", "What the numbers mean") { onNavigate(SettingsRoutes.ABOUT) } }
        item {
            NavRow("Run setup again", "Walk through everything from the start") {
                onNavigate(Routes.WIZARD)
            }
        }
    }
}

@Composable
fun BodyScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val body = state.settings.body

    SettingsScaffold("Body", onBack) {
        item {
            SectionCard("Measurements") {
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
                    parseWholeNumber(text)?.let { year ->
                        viewModel.updateSettings { it.copy(body = it.body.copy(birthYear = year)) }
                    }
                }
                NumberField("Height (cm)", body.heightCm.toString()) { text ->
                    parseWholeNumber(text)?.let { cm ->
                        viewModel.updateSettings { it.copy(body = it.body.copy(heightCm = cm)) }
                    }
                }
                NumberField(
                    label = "Weight fallback (kg)",
                    value = "%.1f".format(body.fallbackWeightKg),
                    decimal = true,
                ) { text ->
                    parseDecimal(text)?.let { kg ->
                        viewModel.updateSettings {
                            it.copy(body = it.body.copy(fallbackWeightKg = kg))
                        }
                    }
                }
                Explainer(
                    "Height, age and sex feed the Mifflin-St Jeor resting rate. Weight comes " +
                        "from Health Connect when available; the fallback is used otherwise."
                )
            }
        }
    }
}

@Composable
fun CalculationScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val calc = state.settings.calculation

    SettingsScaffold("Calculation", onBack) {
        item {
            SectionCard("Burn") {
                ToggleRow(
                    title = "Prefer tracker total",
                    subtitle = "Use TotalCaloriesBurned when a device writes it, instead of " +
                        "adding active calories to the estimated resting rate.",
                    checked = calc.preferHcTotal,
                ) { checked ->
                    viewModel.updateSettings {
                        it.copy(calculation = it.calculation.copy(preferHcTotal = checked))
                    }
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Prefer measured resting rate",
                    subtitle = "Use a BasalMetabolicRateRecord when one exists, rather than " +
                        "the Mifflin-St Jeor estimate from your body settings.",
                    checked = calc.preferHcBasal,
                ) { checked ->
                    viewModel.updateSettings {
                        it.copy(calculation = it.calculation.copy(preferHcBasal = checked))
                    }
                }
            }
        }
        item {
            SectionCard("When a day starts") {
                Text(
                    text = if (calc.dayStartHour == 0) {
                        "Midnight"
                    } else {
                        "%02d:00".format(calc.dayStartHour)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = calc.dayStartHour.toFloat(),
                    onValueChange = { value ->
                        viewModel.updateSettings {
                            it.copy(
                                calculation = it.calculation.copy(dayStartHour = value.roundToInt())
                            )
                        }
                    },
                    valueRange = 0f..8f,
                    steps = 7,
                )
                Explainer(
                    "Set this later than midnight to keep a late dinner on the day you ate " +
                        "it. Changing it makes the app relearn your typical day, because the " +
                        "learned curve is measured from whenever your day starts."
                )
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SettingsScaffold("About", onBack) {
        item {
            SectionCard("How the projection works") {
                Explainer(
                    "Rather than assuming you spend the rest of the day at rest, the app " +
                        "starts from your typical full day and hands weight over to today's " +
                        "own burn as the day is observed. Early in the day the number is " +
                        "mostly your usual day; by evening it is almost entirely today."
                )
            }
        }
        item {
            SectionCard("Privacy") {
                Explainer(
                    "Everything is read from Health Connect and stays on this phone. The app " +
                        "never writes health data and has no network access."
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
