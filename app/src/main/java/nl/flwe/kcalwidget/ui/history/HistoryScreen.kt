package nl.flwe.kcalwidget.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.history.BankedCarry
import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.ui.MainViewModel
import nl.flwe.kcalwidget.ui.components.ChartLegend
import nl.flwe.kcalwidget.ui.components.ChartRange
import nl.flwe.kcalwidget.ui.components.ChartReadout
import nl.flwe.kcalwidget.ui.components.RangeSelector
import nl.flwe.kcalwidget.ui.components.ScrubbableChart
import nl.flwe.kcalwidget.ui.components.chartDate
import nl.flwe.kcalwidget.ui.components.DateAxis
import nl.flwe.kcalwidget.ui.components.Explainer
import nl.flwe.kcalwidget.ui.components.SectionCard
import nl.flwe.kcalwidget.ui.components.StatRow
import nl.flwe.kcalwidget.ui.settings.SettingsScaffold
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val OVER = Color(0xFFB3261E)
private val UNDER = Color(0xFF1B5E20)
private val TREND = Color(0xFF3F51B5)
private val TARGET = Color(0xFF8E24AA)
private val MARKER = Color(0xFF616161)

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
        item { NetChartCard(history, state.settings) }
        if (state.settings.goal.useWeeklyBanking) {
            item { CarryCard(history.carry) }
        }
        item { WeightChartCard(history) }
        item { ExportCard(history) }
        item {
            SectionCard("Days") {
                Explainer(
                    "Newest first. Net is intake minus burn, so negative is a deficit. " +
                        "These are the figures your apps recorded, before any calibration " +
                        "correction, which is why they can differ from today's budget."
                )
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
private fun NetChartCard(history: History, settings: AppSettings) {
    var range by remember { mutableStateOf(ChartRange.MONTH) }
    val shown = history.rows.takeLast(range.days)
    val nets = shown.map { it.netKcal }
    val target = settings.goal.dailyEnergyDelta
    // Gaining means clearing the line; every other goal means staying under it.
    val gaining = target > 0

    SectionCard("Net balance") {
        RangeSelector(range, history.rows.size) { range = it }

        if (nets.none { it != null }) {
            Text("No complete days in this range.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }

        // The axis covers what is actually there, plus zero and the target, rather than
        // running symmetrically around zero. A month of pure deficit under a symmetric
        // axis leaves the entire top half blank and squeezes every bar into the bottom.
        val values = nets.filterNotNull() + target + 0.0
        val high = values.max()
        val low = values.min()
        val axisSpan = (high - low).coerceAtLeast(1.0)
        // A little headroom so the largest bar is not flush against the edge.
        fun frac(kcal: Double) = (((high - kcal) / axisSpan).toFloat() * 0.92f) + 0.04f
        val chartHeight = 140.dp

        ScrubbableChart(
            xFractions = shown.indices.map { (it + 0.5f) / shown.size },
            chartHeight = chartHeight,
            // Zero first: it is the reference, so it is the label that survives if a
            // barely-positive maximum lands on top of it.
            axisLabels = buildList {
                add(frac(0.0) to "0")
                if (high > 0) add(frac(high) to "+${high.roundToInt()}")
                if (low < 0) add(frac(low) to "${low.roundToInt()}")
            },
            markerColor = MARKER,
            below = {
                if (shown.size >= 2) DateAxis(shown.first().date, shown.last().date)
            },
            readout = { index ->
                val day = shown[index]
                val net = day.netKcal
                ChartReadout(
                    title = chartDate(day.date),
                    values = buildList {
                        add("Eaten" to (day.intakeKcal?.let { "${it.roundToInt()} kcal" } ?: "not logged"))
                        add("Burned" to (day.burnKcal?.let { "${it.roundToInt()} kcal" } ?: "not recorded"))
                        if (net != null) {
                            add("Net" to "${signed(net)} kcal")
                            if (target != 0.0) {
                                val off = net - target
                                val met = if (gaining) net >= target else net <= target
                                add(
                                    "Against your goal" to
                                        "${abs(off).roundToInt()} kcal " +
                                        if (met) "past it" else "short"
                                )
                            }
                        }
                    },
                )
            },
        ) { selected ->
            val slot = size.width / nets.size
            // One mapping for the bars and the labels both, so they cannot drift apart.
            fun y(kcal: Double) = frac(kcal) * size.height
            val zeroY = y(0.0)

            drawLine(
                color = Color.Gray,
                start = Offset(0f, zeroY),
                end = Offset(size.width, zeroY),
                strokeWidth = 1f,
            )

            nets.forEachIndexed { index, net ->
                if (net == null) return@forEachIndexed
                val left = index * slot + slot * 0.2f
                val width = slot * 0.6f
                // Colour says whether the day met the goal, not merely which side of
                // zero it fell on. A 200 kcal deficit against a 500 kcal goal is a
                // miss, and it used to be drawn the same green as a day that hit it.
                val onTrack = if (gaining) net >= target else net <= target
                val top = minOf(y(net), zeroY)
                val bottom = maxOf(y(net), zeroY)
                drawRect(
                    color = if (onTrack) UNDER else OVER,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(width, bottom - top),
                    alpha = if (index == selected) 1f else 0.55f,
                )
            }

            if (target != 0.0) {
                val targetY = y(target)
                drawLine(
                    color = TARGET,
                    start = Offset(0f, targetY),
                    end = Offset(size.width, targetY),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                )
            }
        }

        if (target != 0.0) {
            ChartLegend(
                listOf(
                    UNDER to "Met the goal",
                    OVER to "Short of it",
                    TARGET to "Goal: ${target.roundToInt()} kcal/day",
                )
            )
            Spacer(Modifier.height(4.dp))
            Explainer(
                "Drag across the chart to read any day. The dashed line is what your goal " +
                    "asks for each day: ${target.roundToInt()} kcal. " +
                    if (gaining) {
                        "Bars reaching above it are days you ate enough to gain at your " +
                            "chosen rate; bars short of it are days you did not."
                    } else {
                        "Bars reaching below it are days you ran the deficit you wanted; " +
                            "bars that stop short are deficits too small to hit your rate."
                    }
            )
        } else {
            ChartLegend(listOf(UNDER to "Deficit", OVER to "Surplus"))
            Spacer(Modifier.height(4.dp))
            Explainer(
                "Drag across the chart to read any day. Your goal is to maintain, so the " +
                    "zero line is the target: bars either side of it are days you ate more " +
                    "or less than you burned."
            )
        }
    }
}

/**
 * Where the weekly carry came from, day by day.
 *
 * Banking moves today's budget by up to 700 kcal on the strength of days that are no
 * longer on screen anywhere else. The total on its own is an assertion; this is the
 * working behind it.
 */
@Composable
private fun CarryCard(carry: BankedCarry) {
    SectionCard("Carried into today") {
        if (carry.days.isEmpty()) {
            Text(
                "No complete days with food logged yet, so nothing is being carried.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        carry.days.forEach { day ->
            StatRow(
                label = DAY_LABEL.format(day.date),
                value = "${signed(day.kcal)} kcal",
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        StatRow("Carried into today", "${signed(carry.totalKcal)} kcal")
        Spacer(Modifier.height(8.dp))
        Explainer(
            buildString {
                append("Each day is its burn plus your goal, minus what you ate, so a ")
                append("positive day left something over. They add up to ")
                append("${signed(carry.rawTotalKcal)} kcal")
                if (carry.capped) {
                    // Without the sign this reads as a cap of +700 on a negative carry.
                    append(", capped to ${signed(carry.totalKcal)} ")
                    append("so one heavy week cannot swallow today")
                }
                append(". Calibration is applied here, so these figures can differ from ")
                append("the raw ones in the day list below.")
            }
        )
    }
}

private fun signed(value: Double, decimals: Int = 0): String {
    val sign = if (value >= 0) "+" else ""
    return if (decimals == 0) "$sign${value.roundToInt()}" else "$sign${"%.${decimals}f".format(value)}"
}

@Composable
private fun WeightChartCard(history: History) {
    var range by remember { mutableStateOf(ChartRange.QUARTER) }
    SectionCard("Weight") {
        val cutoff = LocalDate.now().minusDays(range.days.toLong())
        val points = history.trend.points.filter { !it.date.isBefore(cutoff) }
        // Offer a span only if there are weigh-ins old enough to fill it.
        val weighInSpan = history.trend.points.firstOrNull()
            ?.let { ChronoUnit.DAYS.between(it.date, LocalDate.now()).toInt() } ?: 0
        RangeSelector(range, weighInSpan) { range = it }

        if (points.size < 2) {
            Text(
                "Two weigh-ins in this range are needed to draw a line.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        val all = points.flatMap { listOf(it.rawKg, it.trendKg) }
        val min = all.min()
        val max = all.max()
        val span = (max - min).coerceAtLeast(0.5)
        val firstDay = points.first().date.toEpochDay()
        val lastDay = points.last().date.toEpochDay()
        val daySpan = (lastDay - firstDay).coerceAtLeast(1)
        fun frac(kg: Double) = (1f - ((kg - min) / span).toFloat()) * 0.9f + 0.05f
        val chartHeight = 140.dp

        ScrubbableChart(
            // Placed by date, not by position: weigh-ins are irregular, and spacing them
            // evenly would put the marker on the wrong day.
            xFractions = points.map { (it.date.toEpochDay() - firstDay).toFloat() / daySpan },
            chartHeight = chartHeight,
            axisLabels = listOf(
                frac(max) to "%.1f kg".format(max),
                frac(min) to "%.1f kg".format(min),
            ),
            markerColor = MARKER,
            below = { DateAxis(points.first().date, points.last().date) },
            readout = { index ->
                val point = points[index]
                ChartReadout(
                    title = chartDate(point.date),
                    values = listOf(
                        "On the scale" to "%.1f kg".format(point.rawKg),
                        "Smoothed trend" to "%.1f kg".format(point.trendKg),
                        "Since ${chartDate(points.first().date)}" to
                            "${signed(point.trendKg - points.first().trendKg, 1)} kg",
                    ),
                )
            },
        ) { selected ->
            fun x(day: Long) = ((day - firstDay).toFloat() / daySpan) * size.width
            fun y(kg: Double) = frac(kg) * size.height

            points.forEachIndexed { index, point ->
                drawCircle(
                    color = Color.Gray,
                    radius = if (index == selected) 6f else 3f,
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
            drawCircle(
                color = TREND,
                radius = 6f,
                center = Offset(
                    x(points[selected].date.toEpochDay()),
                    y(points[selected].trendKg),
                ),
            )
        }
        ChartLegend(listOf(Color.Gray to "Weigh-ins", TREND to "Smoothed trend"))
        Spacer(Modifier.height(4.dp))
        Explainer(
            "Drag across the chart to read any weigh-in. Grey dots are what the scale " +
                "said; the line is the smoothed trend."
        )
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

/**
 * Locale.ROOT throughout: the default locale writes "92,74" on a Dutch phone, and this is
 * comma-separated, so every weight column silently split in two and shifted the rest of
 * the row. A spreadsheet opens it without complaint, which is what makes it dangerous.
 */
internal fun toCsv(history: History): String {
    val trendByDate = history.trend.points.associate { it.date to it.trendKg }
    return buildString {
        appendLine("date,intake_kcal,burn_kcal,net_kcal,weight_kg,trend_kg")
        history.rows.forEach { row ->
            append(row.date)
            append(',').append(row.intakeKcal?.roundToInt() ?: "")
            append(',').append(row.burnKcal?.roundToInt() ?: "")
            append(',').append(row.netKcal?.roundToInt() ?: "")
            append(',').append(row.weightKg?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "")
            append(',').append(
                trendByDate[row.date]?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""
            )
            appendLine()
        }
    }
}
