package nl.flwe.kcalwidget.ui.home

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.BurnSource
import nl.flwe.kcalwidget.data.DayEnergy
import nl.flwe.kcalwidget.data.HealthAvailability
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.ui.components.StatRow
import nl.flwe.kcalwidget.widget.KcalWidgetReceiver
import kotlin.math.abs
import kotlin.math.roundToInt

/** Below this, a calibrated figure and the logged one are the same number to a reader. */
private const val MIN_VISIBLE_ADJUSTMENT_KCAL = 10.0

private val GOOD = Color(0xFF1B5E20)
private val BAD = Color(0xFFB3261E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenCalibration: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.load() }

    // Permissions can be revoked from the Health Connect app while we are backgrounded.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kcal Balance") },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) {
                // The default UiState says "unsupported"; showing that before the first
                // read lands would accuse a perfectly healthy device of being broken.
                item {
                    SectionCard("Today") {
                        Text("Reading Health Connect…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (state.readFailed) {
                item {
                    SectionCard("Could not read Health Connect") {
                        Text(
                            "The read took too long and was given up on. This usually means " +
                                "Health Connect is busy rather than broken.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { viewModel.load() }) { Text("Try again") }
                    }
                }
            } else if (state.availability != HealthAvailability.AVAILABLE) {
                item { HealthConnectMissingCard(state.availability) }
            } else if (!state.hasPermissions) {
                item {
                    SectionCard("Health Connect access") {
                        Text(
                            "Kcal Balance reads today's nutrition, calories burned, steps, " +
                                "basal rate and weight. It never writes anything back.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            permissionLauncher.launch(HealthRepository.REQUIRED_PERMISSIONS)
                        }) { Text("Grant access") }
                    }
                }
            } else {
                item { TodayCard(state.energy) }
                val bias = state.settings.calibration.lastBiasKcal
                if (bias != null && !state.settings.features.autoCalibration) {
                    item {
                        SectionCard("Your weight disagrees") {
                            Text(
                                "Over the last few weeks you have been ${
                                    if (bias > 0) "gaining more" else "losing more"
                                } than these numbers predict, by about " +
                                    "${abs(bias).roundToInt()} kcal a day.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onOpenCalibration) { Text("Look into it") }
                        }
                    }
                }
                if (!state.hasBackgroundPermission) {
                    item {
                        SectionCard("Background updates") {
                            Text(
                                "Without background read access the widget only refreshes while " +
                                    "the app is open or when you tap its refresh button.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = {
                                runCatching {
                                    permissionLauncher.launch(
                                        setOf(HealthRepository.BACKGROUND_PERMISSION)
                                    )
                                }
                            }) { Text("Allow background reads") }
                        }
                    }
                }
            }
            item { WidgetCard() }
        }
    }
}

/**
 * The budget, written out as the sum it actually is.
 *
 * A single "Budget: 2180" cannot be argued with, and with banking on it moves by hundreds
 * of kcal for reasons that happened days ago. Each term gets its own line so the number
 * can be checked rather than believed.
 */
@Composable
private fun BudgetBreakdown(energy: DayEnergy) {
    val goal = energy.goalDeltaKcal.roundToInt()
    val carry = energy.bankedAdjustmentKcal.roundToInt()

    StatRow(
        label = if (goal == 0) "Goal (maintain)" else "Goal",
        value = "${signed(goal)} kcal",
    )
    if (energy.bankingApplied) {
        StatRow("Carried from the past week", "${signed(carry)} kcal")
    }
    StatRow("Budget", "${energy.budgetKcal.roundToInt()} kcal")
}

private fun signed(kcal: Int) = if (kcal > 0) "+$kcal" else kcal.toString()

@Composable
private fun TodayCard(energy: DayEnergy?) {
    SectionCard("Today") {
        if (energy == null) {
            Text("No reading yet.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val over = energy.remainingKcal < 0
        Text(
            text = "${abs(energy.remainingKcal).roundToInt()} kcal",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (over) BAD else GOOD,
        )
        Text(
            text = if (over) "over budget" else "left to eat",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { energy.intakeFraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        if (over) {
            Text(
                text = "Burn ${energy.moveKcalToClear.roundToInt()} kcal to break even, " +
                    "about ${energy.walkMinutesToClear} min of brisk walking.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        // Two rows over a rounding difference is noise, not information.
        val intakeAdjustment = abs(energy.intakeKcal - energy.rawIntakeKcal)
        if (energy.calibrationApplied && intakeAdjustment >= MIN_VISIBLE_ADJUSTMENT_KCAL) {
            StatRow("Eaten (calibrated)", "${energy.intakeKcal.roundToInt()} kcal")
            StatRow("Eaten (as logged)", "${energy.rawIntakeKcal.roundToInt()} kcal")
        } else {
            StatRow("Eaten", "${energy.intakeKcal.roundToInt()} kcal")
        }
        StatRow(
            label = if (energy.calibrationApplied) "Burned so far (as tracked)" else "Burned so far",
            value = "${energy.burnedSoFarKcal.roundToInt()} kcal",
        )
        StatRow("Projected by midnight", "${energy.projectedBurnKcal.roundToInt()} kcal")
        BudgetBreakdown(energy)
        StatRow("Resting rate", "${energy.bmrPerDayKcal.roundToInt()} kcal/day")
        StatRow(
            label = if (energy.baselineDays > 0) {
                "Typical day (${energy.baselineDays}d)"
            } else {
                "Typical day (estimated)"
            },
            value = "${energy.typicalDayKcal.roundToInt()} kcal",
        )
        StatRow("Today's data weight", "${(energy.confidence * 100).roundToInt()}%")
        StatRow(
            label = "Against a typical day",
            value = "${if (energy.surplusVsTypicalKcal >= 0) "+" else ""}" +
                "${energy.surplusVsTypicalKcal.roundToInt()} kcal so far",
        )
        StatRow("Weight used", "${"%.1f".format(energy.weightKg)} kg")
        StatRow("Burn source", energy.source.describe())
        Spacer(Modifier.height(8.dp))
        Explainer(
            if (energy.baselineDays > 0) {
                "The projection starts from your typical day. Falling behind lowers it " +
                    "straight away; getting ahead is not counted in advance, so a walk " +
                    "earns you room as you do it rather than promising room it might take " +
                    "back later."
            } else {
                "Not enough history yet, so the typical day is estimated from your resting " +
                    "rate. It will be learned from your own days once a few have been logged."
            }
        )
        if (energy.calibrationApplied) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                "Calibration is on, so the typical day, projection, resting rate and budget " +
                    "are corrected by what your weight trend says your numbers really are. " +
                    "Burn so far and intake as logged are shown untouched, so you can still " +
                    "see what your apps reported."
            )
        }
        if (energy.bankingApplied) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                buildString {
                    append("Weekly banking is on, so the budget is the projection plus your ")
                    append("goal, plus whatever the past six days left over. ")
                    append(
                        when {
                            energy.bankedAdjustmentKcal > 1 ->
                                "Those days came in under, so today has " +
                                    "${energy.bankedAdjustmentKcal.roundToInt()} kcal more " +
                                    "than the goal alone would give."
                            energy.bankedAdjustmentKcal < -1 ->
                                "Those days came in over, so today is paying " +
                                    "${(-energy.bankedAdjustmentKcal).roundToInt()} kcal of " +
                                    "it back."
                            else ->
                                "The week has come out even so far, so the budget is the " +
                                    "goal on its own."
                        }
                    )
                    append(" History shows the day-by-day figures behind that carry.")
                }
            )
        }
        if (energy.intakeFloorApplied) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                "Your goal would put the budget below your minimum intake, so the minimum " +
                    "is being used instead. Today's deficit is smaller than the goal asks for."
            )
        }
        if (!energy.hasNutritionData) {
            Spacer(Modifier.height(8.dp))
            Explainer("No nutrition written to Health Connect today, so intake reads as zero.")
        }
    }
}

@Composable
private fun WidgetCard() {
    val context = LocalContext.current
    SectionCard("Widget") {
        Text(
            "Add the widget to your home screen. Tap it to open the app, tap the small " +
                "refresh icon to re-read Health Connect straight away.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { requestPinWidget(context) }) { Text("Add widget") }
    }
}

@Composable
private fun HealthConnectMissingCard(availability: HealthAvailability) {
    val context = LocalContext.current
    SectionCard("Health Connect") {
        Text(
            text = when (availability) {
                HealthAvailability.UPDATE_REQUIRED ->
                    "Health Connect needs an update before this app can read from it."

                else -> "Health Connect is not available on this device."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { openHealthConnectListing(context) }) { Text("Open in Play Store") }
    }
}

private fun BurnSource.describe(): String = when (this) {
    BurnSource.HC_TOTAL -> "tracker total"
    BurnSource.BMR_PLUS_ACTIVE -> "resting rate + active calories"
    BurnSource.BMR_PLUS_STEPS -> "resting rate + steps"
    BurnSource.BMR_ONLY -> "resting rate only"
}

private fun requestPinWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(
            ComponentName(context, KcalWidgetReceiver::class.java),
            null,
            null,
        )
    }
}

private fun openHealthConnectListing(context: Context) {
    val uri = "market://details?id=${HealthRepository.HEALTH_CONNECT_PACKAGE}" +
        "&url=healthconnect%3A%2F%2Fonboarding"
    val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
        putExtra("overlay", true)
        putExtra("callerId", context.packageName)
    }
    runCatching { context.startActivity(intent) }
}
