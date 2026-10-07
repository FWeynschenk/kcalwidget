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
import nl.flwe.kcalwidget.data.history.CarryState
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
private val WINDOW_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private const val MIN_VISIBLE_ADJUSTMENT_KCAL = 10.0

private val ON_TARGET = Color(0xFF00695C)
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
    // Only the carry that actually moved the budget belongs in the sum. Listing a
    // withheld credit here makes the column stop adding up, which defeats the point of
    // breaking the budget out in the first place; the spare gets its own line below.
    if (energy.bankingApplied && !energy.hasWeeklySpare && carry != 0) {
        StatRow("Carried from the past week", "${signed(carry)} kcal")
    }
    // Without this the column stops adding up whenever the floor bites: the three rows
    // above come to one number and Budget shows another, with only prose to bridge them.
    if (energy.intakeFloorApplied) {
        StatRow("Your goal would give", "${energy.goalBudgetKcal.roundToInt()} kcal")
        StatRow("Minimum intake", "${energy.budgetKcal.roundToInt()} kcal")
    }
    StatRow("Budget", "${energy.budgetKcal.roundToInt()} kcal")
    if (energy.hasWeeklySpare) {
        Spacer(Modifier.height(4.dp))
        StatRow(
            label = "Spare this week, not in today's budget",
            value = "${signed(energy.weeklySpareKcal.roundToInt())} kcal",
        )
    }
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
        val onTarget = energy.isOnTarget
        Text(
            text = if (onTarget) "On target" else "${abs(energy.remainingKcal).roundToInt()} kcal",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = when {
                onTarget -> ON_TARGET
                over -> BAD
                else -> GOOD
            },
        )
        Text(
            text = when {
                // Inside the band the distance is still worth knowing; it is the verdict
                // that is not. Naming a 12 kcal shortfall as being over budget is a
                // judgement the arithmetic cannot support.
                onTarget -> "within ${energy.onTargetBandKcal} kcal of your budget" +
                    if (abs(energy.remainingKcal) >= 1) {
                        ", ${abs(energy.remainingKcal).roundToInt()} to spare".takeIf { !over }
                            ?: ", ${abs(energy.remainingKcal).roundToInt()} past it"
                    } else {
                        ""
                    }
                over -> "over budget"
                else -> "left to eat"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { energy.intakeFraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        if (over && !onTarget) {
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
        val burnAdjustment = abs(energy.burnedSoFarKcal - energy.rawBurnedSoFarKcal)
        if (energy.calibrationApplied && burnAdjustment >= MIN_VISIBLE_ADJUSTMENT_KCAL) {
            StatRow("Burned so far (calibrated)", "${energy.burnedSoFarKcal.roundToInt()} kcal")
            StatRow("Burned so far (as tracked)", "${energy.rawBurnedSoFarKcal.roundToInt()} kcal")
        } else {
            StatRow("Burned so far", "${energy.burnedSoFarKcal.roundToInt()} kcal")
        }
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
        if (energy.calibrationApplied) {
            // The factors themselves, so every corrected figure above can be checked
            // rather than taken on faith.
            StatRow(
                label = "Counted at",
                value = "burn x${"%.2f".format(energy.burnFactor)}, " +
                    "food x${"%.2f".format(energy.intakeFactor)}",
            )
        }
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
                "Calibration is on, so every figure here is counted at the factors above, " +
                    "which is what your weight trend says your numbers really are. Where " +
                    "that changes a figure enough to matter, what your apps reported is " +
                    "shown beneath it."
            )
        }
        if (energy.bankingApplied) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                buildString {
                    append("Weekly banking is on. ")
                    append(
                        when {
                            energy.hasWeeklySpare ->
                                "The past six days came in under your goal, so the week has " +
                                    "${energy.weeklySpareKcal.roundToInt()} kcal spare. " +
                                    "Today's budget does not include it: it stays on the " +
                                    "daily goal, and the spare is yours to spend " +
                                    "deliberately rather than by drifting into it."
                            energy.bankedAdjustmentKcal < -1 ->
                                "Those days came in over your goal, so today is paying " +
                                    "${(-energy.bankedAdjustmentKcal).roundToInt()} kcal of " +
                                    "it back and the budget is lower than the daily goal alone."
                            else ->
                                "The week has come out even so far, so the budget is the " +
                                    "daily goal on its own."
                        }
                    )
                    append(" History shows the day-by-day figures behind that carry.")
                }
            )
        }
        if (energy.carryState == CarryState.STALE) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                "The past week could not be read just now, so the budget is using the " +
                    "last carry it managed to read. It will correct itself on the next " +
                    "successful read."
            )
        }
        if (energy.carryState == CarryState.UNAVAILABLE) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                "Weekly banking is on, but the past week could not be read and nothing " +
                    "recent was remembered, so today's budget is running on the daily goal " +
                    "alone. It is not a claim that the week came out even."
            )
        }
        if (energy.intakeFloorApplied) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                "Your goal would put the budget below your minimum intake, so the minimum " +
                    "is being used instead. Today's deficit is smaller than the goal asks for."
            )
        }
        if (energy.readErrors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                "A read came back with an error, so a figure above may be missing rather " +
                    "than zero: ${energy.readErrors.joinToString("; ")}"
            )
        }
        if (!energy.hasNutritionData) {
            Spacer(Modifier.height(8.dp))
            Explainer(
                buildString {
                    append("No food logged in this window")
                    val from = energy.windowStart
                    val to = energy.windowEnd
                    if (from != null && to != null) {
                        append(" (${WINDOW_TIME.format(from)} to ${WINDOW_TIME.format(to)})")
                    }
                    append(", so intake reads as zero. ")
                    val before = energy.intakeBeforeBoundaryKcal
                    if (before != null && before > 1.0) {
                        // The just-after-midnight case: the day is minutes old and the
                        // evening's food now belongs to yesterday. Correct, and baffling
                        // unless it is said.
                        append("You logged ${before.roundToInt()} kcal in the hours before ")
                        append("the day rolled over, which counts towards yesterday. ")
                        append("A later day start, under Calculation, moves the boundary ")
                        append("past your evening.")
                    } else {
                        append("Anything logged against an earlier time counts towards ")
                        append("that day, not this one.")
                    }
                }
            )
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
