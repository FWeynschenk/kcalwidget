package nl.flwe.kcalwidget.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.notify.Notifications
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.SectionCard
import kotlin.math.roundToInt

@Composable
fun RemindersScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val features = state.settings.features

    // Only ever asked for when the notification is actually switched on.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.updateSettings {
            it.copy(features = it.features.copy(weighInNotification = granted))
        }
    }

    SettingsScaffold("Reminders", onBack) {
        item {
            SectionCard("Weigh in every") {
                Text(
                    "${features.weighInIntervalDays} days",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = features.weighInIntervalDays.toFloat(),
                    onValueChange = { value ->
                        viewModel.updateSettings {
                            it.copy(
                                features = it.features.copy(
                                    weighInIntervalDays = value.roundToInt().coerceIn(1, 30)
                                )
                            )
                        }
                    },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Explainer(
                    "Calibration compares your weight against the days either side of it, so " +
                        "it needs a weigh-in from inside the window it is measuring. A long " +
                        "gap does not just delay the nudge, it pauses the check."
                )
            }
        }

        item {
            SectionCard("How to remind you") {
                ToggleRow(
                    title = "On the widgets",
                    subtitle = "A line on Balance, Detailed and Trend once it is overdue. " +
                        "Needs no permission and costs nothing.",
                    checked = features.widgetWeighInNudge,
                ) { on ->
                    viewModel.updateSettings {
                        it.copy(features = it.features.copy(widgetWeighInNudge = on))
                    }
                }
                Spacer(Modifier.height(16.dp))
                ToggleRow(
                    title = "As a notification",
                    subtitle = "Off unless you ask for it. Silenceable on its own in Android " +
                        "settings, separately from goal milestones.",
                    checked = features.weighInNotification,
                ) { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !Notifications.canPost(context)
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.updateSettings {
                            it.copy(features = it.features.copy(weighInNotification = on))
                        }
                    }
                }
                if (features.weighInNotification && !Notifications.canPost(context)) {
                    Spacer(Modifier.height(8.dp))
                    Explainer(
                        "Notifications are blocked for this app in Android settings, so " +
                            "nothing will arrive until they are turned back on there."
                    )
                }
            }
        }

        item {
            SectionCard("Goal milestones") {
                Explainer(
                    "Reaching a target weight always notifies, on its own channel, so you can " +
                        "silence weigh-in nudges without silencing the one message worth " +
                        "hearing. It is judged on your trend weight, not a single reading."
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
