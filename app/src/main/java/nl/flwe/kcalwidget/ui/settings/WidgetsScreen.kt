package nl.flwe.kcalwidget.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.widget.DetailedWidgetReceiver
import nl.flwe.kcalwidget.widget.KcalWidgetReceiver
import nl.flwe.kcalwidget.widget.MinimalWidgetReceiver
import nl.flwe.kcalwidget.widget.TrendWidgetReceiver
import kotlin.math.roundToInt

@Composable
fun WidgetsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val features = state.settings.features

    SettingsScaffold("Widgets", onBack) {
        item {
            SectionCard("Add to your home screen") {
                Explainer("All four read the same numbers; they differ in how much they show.")
                Spacer(Modifier.height(12.dp))
                PinRow("Balance", "Remaining, progress and the day's totals") {
                    pin(context, KcalWidgetReceiver::class.java)
                }
                PinRow("Minimal", "Just the number, for a crowded screen") {
                    pin(context, MinimalWidgetReceiver::class.java)
                }
                PinRow("Detailed", "Burn so far, projection, confidence and weight") {
                    pin(context, DetailedWidgetReceiver::class.java)
                }
                PinRow("Trend", "Two weeks of balance with your weight line") {
                    pin(context, TrendWidgetReceiver::class.java)
                }
            }
        }

        item {
            SectionCard("Weigh-in nudge") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Show on widgets",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = features.widgetWeighInNudge,
                        onCheckedChange = { on ->
                            viewModel.updateSettings {
                                it.copy(features = it.features.copy(widgetWeighInNudge = on))
                            }
                        },
                    )
                }
                Explainer(
                    "Appears on Balance, Detailed and Trend once a weigh-in is overdue. " +
                        "Minimal has no room for it."
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Remind after ${features.weighInIntervalDays} days",
                    style = MaterialTheme.typography.titleSmall,
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
                    "Calibration needs a weight from inside the window it is measuring, so " +
                        "a long gap does not just delay the nudge — it pauses the check."
                )
            }
        }
    }
}

@Composable
private fun PinRow(title: String, subtitle: String, onPin: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onPin) { Text("Add") }
    }
    Spacer(Modifier.height(8.dp))
}

private fun pin(context: Context, receiver: Class<*>) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
    }
}
