package nl.flwe.kcalwidget.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.ui.components.StatRow
import nl.flwe.kcalwidget.ui.settings.SettingsScaffold
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val OVER = Color(0xFFB3261E)
private val UNDER = Color(0xFF1B5E20)
private val TREND = Color(0xFF3F51B5)

private val DAY_LABEL = DateTimeFormatter.ofPattern("d MMM")

/**
 * The evidence behind every other number in the app.
 *
 * Calibration claims your intake or burn is off by some amount; without somewhere to see
 * the days it drew that from, the claim is unfalsifiable.
 */
@Composable
fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history = state.history

    // Ninety days of aggregates is too heavy to read on every resume, so it is fetched
    // when this screen is actually opened.
    LaunchedEffect(Unit) { viewModel.loadHistory() }

    SettingsScaffold("History", onBack) {
        if (history == null || history.rows.isEmpty()) {
            item {
                SectionCard("History") {
                    Text(
                        // "Nothing recorded" and "still reading" look identical to a user
                        // unless they are told apart.
                        text = if (state.historyLoading) {
                            "Reading your history…"
                        } else {
                            "Nothing to show yet. Come back once a few complete days have " +
                                "been recorded."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@SettingsScaffold
        }

        item { SummaryCard(history) }
        item { NetChartCard(history) }
        item { WeightChartCard(history) }
        item { ExportCard(history) }
        item {
            SectionCard("Days") {
                Explainer("Newest first. Net is intake minus burn, so negative is a deficit.")
            }
        }
        val recent = history.rows.reversed().take(60)
        items(recent.size) { index -> DayRowCard(recent[index]) }
    }
}

@Composable
private fun SummaryCard(history: History) {
    val complete = history.rows.filter { it.netKcal != null }
    val meanNet = complete.mapNotNull { it.netKcal }.average().takeIf { complete.isNotEmpty() }
    SectionCard("Summary") {
        StatRow("Days recorded", history.rows.size.toString())
        StatRow("Days with food logged", history.daysWithIntake.toString())
        if (meanNet != null) {
            StatRow("Average net", "${meanNet.roundToInt()} kcal/day")
        }
        history.trend.currentTrendKg?.let {
            StatRow("Trend weight", "${"%.1f".format(it)} kg")
        }
        history.trend.weeklyChangeKg?.let {
            StatRow("Trend rate", "${if (it >= 0) "+" else ""}${"%.2f".format(it)} kg/week")
        }
        if (history.bankedAdjustmentKcal != 0.0) {
            StatRow("Banked this week", "${history.bankedAdjustmentKcal.roundToInt()} kcal")
        }
    }
}

@Composable
private fun NetChartCard(history: History) {
    val nets = history.rows.takeLast(30).map { it.netKcal }
    SectionCard("Net balance, last 30 days") {
        if (nets.none { it != null }) {
            Text("No complete days yet.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val maxMagnitude = nets.filterNotNull().maxOfOrNull { abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / nets.size
            val midY = size.height / 2
            drawLine(
                color = Color.Gray,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f,
            )
            nets.forEachIndexed { index, net ->
                if (net == null) return@forEachIndexed
                val height = (abs(net) / maxMagnitude * (midY * 0.9)).toFloat()
                val left = index * slot + slot * 0.2f
                val width = slot * 0.6f
                // Above the line is a surplus, below is a deficit.
                val top = if (net > 0) midY - height else midY
                drawRect(
                    color = if (net > 0) OVER else UNDER,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(width, height),
                )
            }
        }
        Explainer("Green below the line is a deficit; red above it is a surplus.")
    }
}

@Composable
private fun WeightChartCard(history: History) {
    val points = history.trend.points
    SectionCard("Weight") {
        if (points.size < 2) {
            Text("Two weigh-ins are needed to draw a line.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val all = points.flatMap { listOf(it.rawKg, it.trendKg) }
        val min = all.min()
        val max = all.max()
        val span = (max - min).coerceAtLeast(0.5)
        val firstDay = points.first().date.toEpochDay()
        val lastDay = points.last().date.toEpochDay()
        val daySpan = (lastDay - firstDay).coerceAtLeast(1)

        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            fun x(day: Long) = ((day - firstDay).toFloat() / daySpan) * size.width
            fun y(kg: Double) = (1f - ((kg - min) / span).toFloat()) * size.height * 0.9f +
                size.height * 0.05f

            points.forEach { point ->
                drawCircle(
                    color = Color.Gray,
                    radius = 3f,
                    center = Offset(x(point.date.toEpochDay()), y(point.rawKg)),
                )
            }
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                drawLine(
                    color = TREND,
                    start = Offset(x(a.date.toEpochDay()), y(a.trendKg)),
                    end = Offset(x(b.date.toEpochDay()), y(b.trendKg)),
                    strokeWidth = 4f,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.1f kg".format(min), style = MaterialTheme.typography.bodySmall)
            Text("%.1f kg".format(max), style = MaterialTheme.typography.bodySmall)
        }
        Explainer("Grey dots are what the scale said; the line is the smoothed trend.")
    }
}

@Composable
private fun ExportCard(history: History) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(toCsv(history).toByteArray())
                }
            }
        }
    }
    SectionCard("Export") {
        Explainer("Every day as a CSV row, for your own spreadsheet.")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { launcher.launch("kcal-balance-history.csv") }) {
            Text("Export CSV")
        }
    }
}

@Composable
private fun DayRowCard(row: DayRow) {
    SectionCard(row.date.format(DAY_LABEL)) {
        StatRow("Eaten", row.intakeKcal?.let { "${it.roundToInt()} kcal" } ?: "—")
        StatRow("Burned", row.burnKcal?.let { "${it.roundToInt()} kcal" } ?: "—")
        row.netKcal?.let { net ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("Net", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    text = "${if (net > 0) "+" else ""}${net.roundToInt()} kcal",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (net > 0) OVER else UNDER,
                )
            }
        }
        row.weightKg?.let { StatRow("Weighed", "${"%.1f".format(it)} kg") }
    }
}

private fun toCsv(history: History): String {
    val trendByDate = history.trend.points.associate { it.date to it.trendKg }
    return buildString {
        appendLine("date,intake_kcal,burn_kcal,net_kcal,weight_kg,trend_kg")
        history.rows.forEach { row ->
            append(row.date)
            append(',').append(row.intakeKcal?.roundToInt() ?: "")
            append(',').append(row.burnKcal?.roundToInt() ?: "")
            append(',').append(row.netKcal?.roundToInt() ?: "")
            append(',').append(row.weightKg?.let { "%.2f".format(it) } ?: "")
            append(',').append(trendByDate[row.date]?.let { "%.2f".format(it) } ?: "")
            appendLine()
        }
    }
}
