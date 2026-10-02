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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.flwe.kcalwidget.data.history.BankedCarry
import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.history.HistoryRepository
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.weight.WeightForecast
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
private val FORECAST = Color(0xFF00897B)

/** About this many forecast marks, whatever the span being shown. */
private const val FORECAST_MARKS = 12L

private val DAY_LABEL = DateTimeFormatter.ofPattern("d MMM")

/** Enough to scroll through; beyond this it is a job for the CSV. */
private const val DAYS_LISTED = 60

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

    // Calibrated by default: the corrected figures are the ones the budget is enforced
    // with, so they are the ones that answer "how am I doing". The raw numbers are a
    // toggle away for anyone who wants to see what the apps actually reported.
    var calibrated by remember { mutableStateOf(true) }
    val basis = if (calibrated) state.settings else state.settings.withoutCalibration()

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

        if (state.settings.features.autoCalibration) {
            item { BasisCard(calibrated, state.settings) { calibrated = it } }
        }
        item { SummaryCard(history, basis) }
        item { NetChartCard(history, basis) }
        if (state.settings.goal.useWeeklyBanking) {
            item { CarryCard(history.carry, state.settings) }
        }
        item { WeightChartCard(history, state.settings) }
        item { ExportCard(history, state.settings) }
        item { DaysCard(history.rows.reversed().take(DAYS_LISTED), basis, calibrated) }
    }
}

/**
 * Which figures the screen shows.
 *
 * Only offered when calibration is actually on; without it the two are the same numbers
 * and the choice would be noise.
 */
@Composable
private fun BasisCard(calibrated: Boolean, settings: AppSettings, onChange: (Boolean) -> Unit) {
    SectionCard("Figures") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = calibrated,
                onClick = { onChange(true) },
                label = { Text("Calibrated", style = MaterialTheme.typography.labelMedium) },
            )
            FilterChip(
                selected = !calibrated,
                onClick = { onChange(false) },
                label = { Text("As logged", style = MaterialTheme.typography.labelMedium) },
            )
        }
        Explainer(
            if (calibrated) {
                "Burn counted at ${"%.2f".format(settings.calibration.expenditureFactor)} " +
                    "and food at ${"%.2f".format(settings.calibration.intakeFactor)} of what " +
                    "your apps recorded, which is how your budget is worked out. Switch to " +
                    "see the recorded numbers instead."
            } else {
                "Exactly what your apps recorded. Your budget is not worked out from these, " +
                    "so a day can look fine here and still take something off tomorrow."
            }
        )
    }
}

/**
 * Every day on one card, four columns wide.
 *
 * A card per day was sixty cards of four rows each: correct, and unreadable past the
 * first week. A table loses nothing that was being read.
 */
@Composable
private fun DaysCard(rows: List<DayRow>, basis: AppSettings, calibrated: Boolean) {
    SectionCard("Days") {
        Explainer(
            if (calibrated) {
                "Newest first, calibrated. Net is food minus burn, so negative is a deficit."
            } else {
                "Newest first, exactly as recorded. Net is food minus burn, so negative is " +
                    "a deficit."
            }
        )
        Spacer(Modifier.height(8.dp))
        DayGrid(null, "Food", "Burn", "Net", "kg", header = true)
        HorizontalDivider()
        rows.forEach { row ->
            val factorIntake = intakeFactor(basis)
            val factorBurn = burnFactor(basis)
            val net = HistoryRepository.calibratedNet(row, basis)
            DayGrid(
                date = DAY_LABEL.format(row.date),
                food = row.intakeKcal?.let { (it * factorIntake).roundToInt().toString() } ?: "—",
                burn = row.burnKcal?.let { (it * factorBurn).roundToInt().toString() } ?: "—",
                net = net?.let { signed(it) } ?: "—",
                weight = row.weightKg?.let { "%.1f".format(it) } ?: "",
                netColour = net?.let { if (it > 0) OVER else UNDER },
            )
        }
    }
}

@Composable
private fun DayGrid(
    date: String?,
    food: String,
    burn: String,
    net: String,
    weight: String,
    header: Boolean = false,
    netColour: Color? = null,
) {
    val style = if (header) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(date ?: "", style = style, modifier = Modifier.weight(1.5f))
        Text(food, style = style, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(burn, style = style, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(
            net,
            style = style,
            color = netColour ?: LocalContentColor.current,
            fontWeight = if (netColour != null) FontWeight.Medium else null,
            modifier = Modifier.weight(1.1f),
            textAlign = TextAlign.End,
        )
        Text(weight, style = style, modifier = Modifier.weight(0.9f), textAlign = TextAlign.End)
    }
}

@Composable
private fun SummaryCard(history: History, settings: AppSettings) {
    // Calibrated, like the chart above it and the budget it is compared against. An
    // average that quietly used different numbers from everything else would be the
    // same trap in summary form.
    val nets = history.rows.mapNotNull { HistoryRepository.calibratedNet(it, settings) }
    val meanNet = nets.average().takeIf { nets.isNotEmpty() }
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
    // Corrected, so a green bar and a positive line in the carry card mean the same
    // thing. The day list below stays raw, because that is a record of what the apps
    // said rather than a verdict on it.
    val nets = shown.map { HistoryRepository.calibratedNet(it, settings) }
    val target = settings.goal.dailyEnergyDelta
    // Gaining means clearing the line; every other goal means staying under it.
    val gaining = target > 0

    SectionCard("Net balance") {
        RangeSelector(range) { range = it }

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
                val net = nets[index]
                val rawNet = day.netKcal
                ChartReadout(
                    title = chartDate(day.date),
                    values = buildList {
                        add("Eaten" to (day.intakeKcal?.let { "${it.roundToInt()} kcal" } ?: "not logged"))
                        add("Burned" to (day.burnKcal?.let { "${it.roundToInt()} kcal" } ?: "not recorded"))
                        if (net != null) {
                            // Both, when they differ: the figure in the day list and the
                            // one the goal is actually judged against.
                            if (rawNet != null && abs(net - rawNet) >= 1.0) {
                                add("Net as logged" to "${signed(rawNet)} kcal")
                                add("Net as counted" to "${signed(net)} kcal")
                            } else {
                                add("Net" to "${signed(net)} kcal")
                            }
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
                "Drag across the chart to read any day. Bars use your calibrated figures, " +
                    "the same ones the budget and the weekly carry use, so a green bar " +
                    "always means that day left something over. The raw numbers your apps " +
                    "recorded are in the day list further down and can differ. " +
                    "The dashed line is what your goal asks for each day: " +
                    "${target.roundToInt()} kcal. " +
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
private fun CarryCard(carry: BankedCarry, settings: AppSettings) {
    SectionCard("Carried into today") {
        if (carry.days.isEmpty()) {
            Text(
                "No complete days with food logged yet, so nothing is being carried.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        StatRow("", "as logged → counted")
        carry.days.forEach { day ->
            // Three numbers per day, because one cannot be checked against anything. The
            // middle figure is the day after calibration; the last is after ageing and
            // the per-day limit, and is what actually reaches the budget.
            StatRow(
                label = DAY_LABEL.format(day.date),
                value = "${signed(day.asLoggedKcal)} → ${signed(day.kcal)} kcal",
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
                append("positive day left something over. The first figure uses the numbers ")
                append("your apps reported; the second is what the day actually contributes, ")
                append("after calibration")
                if (settings.features.autoCalibration) {
                    append(" (burn counted at ${"%.2f".format(settings.calibration.expenditureFactor)}")
                    append(" and intake at ${"%.2f".format(settings.calibration.intakeFactor)}")
                    append(" of what was recorded)")
                }
                append(", after older days are faded out, and after the per-day limit of ")
                append("${HistoryRepository.MAX_DAY_CONTRIBUTION_KCAL.roundToInt()} kcal. ")
                append("A negative total tightens today's ")
                append("budget; a positive one is reported as spare rather than spent, so a ")
                append("good week does not quietly raise the bar on the next day. They add ")
                append("up to ${signed(carry.rawTotalKcal)} kcal")
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

private fun burnFactor(settings: AppSettings) =
    if (settings.features.autoCalibration) settings.calibration.expenditureFactor else 1.0

private fun intakeFactor(settings: AppSettings) =
    if (settings.features.autoCalibration) settings.calibration.intakeFactor else 1.0

private fun signed(value: Double, decimals: Int = 0): String {
    val sign = if (value >= 0) "+" else ""
    return if (decimals == 0) "$sign${value.roundToInt()}" else "$sign${"%.${decimals}f".format(value)}"
}

@Composable
private fun WeightChartCard(history: History, settings: AppSettings) {
    var range by remember { mutableStateOf(ChartRange.QUARTER) }
    SectionCard("Weight") {
        val today = LocalDate.now()
        val cutoff = today.minusDays(range.days.toLong())
        val points = history.trend.points.filter { !it.date.isBefore(cutoff) }
        RangeSelector(range) { range = it }

        if (points.size < 2) {
            Text(
                "Two weigh-ins in this range are needed to draw a line.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        // Look forward as far as you are looking back. A fixed twelve-week horizon against
        // a seven-day range gave the history eight percent of the width, which is not a
        // chart of the last week in any useful sense.
        val horizon = range.days.toLong().coerceAtMost(WeightForecast.HORIZON_DAYS)
        val forecast = remember(history, settings, horizon) {
            WeightForecast.from(history.trend, history.rows, settings, today, horizon)
        }
        // Roughly a dozen marks whatever the span, so a short range is not one lonely
        // point and a long one is not a wall of them.
        val step = ((horizon + FORECAST_MARKS - 1) / FORECAST_MARKS).coerceAtLeast(1L)
        val future = forecast?.points.orEmpty().filter {
            ChronoUnit.DAYS.between(today, it.date).let { d -> d > 0 && d % step == 0L }
        }

        val all = points.flatMap { listOf(it.rawKg, it.centredKg) } +
            future.flatMap { listOf(it.lowKg, it.highKg) }
        val min = all.min()
        val max = all.max()
        val span = (max - min).coerceAtLeast(0.5)
        val firstDay = points.first().date.toEpochDay()
        val lastDay = (future.lastOrNull()?.date ?: points.last().date).toEpochDay()
        val daySpan = (lastDay - firstDay).coerceAtLeast(1)
        fun frac(kg: Double) = (1f - ((kg - min) / span).toFloat()) * 0.9f + 0.05f
        val chartHeight = 140.dp

        // Past and future in one series, so one marker walks the whole chart.
        val marks = points.map { it.date } + future.map { it.date }

        ScrubbableChart(
            // Placed by date, not by position: weigh-ins are irregular, and spacing them
            // evenly would put the marker on the wrong day.
            xFractions = marks.map { (it.toEpochDay() - firstDay).toFloat() / daySpan },
            chartHeight = chartHeight,
            axisLabels = listOf(
                frac(max) to "%.1f kg".format(max),
                frac(min) to "%.1f kg".format(min),
            ),
            markerColor = MARKER,
            below = { DateAxis(points.first().date, marks.last()) },
            readout = { index ->
                if (index < points.size) {
                    val point = points[index]
                    ChartReadout(
                        title = chartDate(point.date),
                        values = listOf(
                            "On the scale" to "%.1f kg".format(point.rawKg),
                            "Smoothed trend" to "%.1f kg".format(point.centredKg),
                            "Since ${chartDate(points.first().date)}" to
                                "${signed(point.centredKg - points.first().centredKg, 1)} kg",
                        ),
                    )
                } else {
                    val point = future[index - points.size]
                    val ahead = ChronoUnit.DAYS.between(today, point.date)
                    ChartReadout(
                        title = "${chartDate(point.date)} — predicted",
                        values = listOf(
                            "In" to if (ahead < 14) "$ahead days" else "${ahead / 7} weeks",
                            "Range" to "%.1f–%.1f kg".format(point.lowKg, point.highKg),
                            "Midpoint" to "%.1f kg".format(point.midKg),
                        ),
                    )
                }
            },
        ) { selected ->
            fun x(date: LocalDate) =
                ((date.toEpochDay() - firstDay).toFloat() / daySpan) * size.width
            fun y(kg: Double) = frac(kg) * size.height

            // The band first, so the lines sit on top of it.
            if (future.isNotEmpty()) {
                val last = points.last()
                val band = Path().apply {
                    moveTo(x(last.date), y(last.centredKg))
                    future.forEach { lineTo(x(it.date), y(it.highKg)) }
                    future.reversed().forEach { lineTo(x(it.date), y(it.lowKg)) }
                    close()
                }
                drawPath(band, color = FORECAST, alpha = 0.18f)

                var from = Offset(x(last.date), y(last.centredKg))
                future.forEach { point ->
                    val to = Offset(x(point.date), y(point.midKg))
                    drawLine(
                        color = FORECAST,
                        start = from,
                        end = to,
                        strokeWidth = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                    from = to
                }
            }

            points.forEachIndexed { index, point ->
                drawCircle(
                    color = Color.Gray,
                    radius = if (index == selected) 6f else 3f,
                    center = Offset(x(point.date), y(point.rawKg)),
                )
            }
            for (i in 0 until points.size - 1) {
                drawLine(
                    color = TREND,
                    start = Offset(x(points[i].date), y(points[i].centredKg)),
                    end = Offset(x(points[i + 1].date), y(points[i + 1].centredKg)),
                    strokeWidth = 4f,
                )
            }

            val marked = if (selected < points.size) {
                Offset(x(points[selected].date), y(points[selected].centredKg))
            } else {
                val point = future[selected - points.size]
                Offset(x(point.date), y(point.midKg))
            }
            drawCircle(
                color = if (selected < points.size) TREND else FORECAST,
                radius = 6f,
                center = marked,
            )
        }
        ChartLegend(
            buildList {
                add(Color.Gray to "Weigh-ins")
                add(TREND to "Smoothed trend")
                if (future.isNotEmpty()) add(FORECAST to "Predicted")
            }
        )
        Spacer(Modifier.height(4.dp))
        Explainer(
            if (future.isEmpty()) {
                "Drag across the chart to read any weigh-in. Grey dots are what the scale " +
                    "said; the line is the smoothed trend."
            } else {
                "Drag across the chart to read any weigh-in, or any week ahead. The shaded " +
                    "band is where your scale and your calorie numbers each say you are " +
                    "heading; its width is how much they disagree, not a margin of error."
            }
        )
    }
}

@Composable
private fun ExportCard(history: History, settings: AppSettings) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(toCsv(history, settings).toByteArray())
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

/**
 * Locale.ROOT throughout: the default locale writes "92,74" on a Dutch phone, and this is
 * comma-separated, so every weight column silently split in two and shifted the rest of
 * the row. A spreadsheet opens it without complaint, which is what makes it dangerous.
 */
internal fun toCsv(history: History, settings: AppSettings): String {
    val trendByDate = history.trend.points.associate { it.date to it.centredKg }
    val calibrating = settings.features.autoCalibration
    val burnFactor = if (calibrating) settings.calibration.expenditureFactor else 1.0
    val intakeFactor = if (calibrating) settings.calibration.intakeFactor else 1.0
    return buildString {
        appendLine(
            "date,intake_kcal,burn_kcal,net_kcal," +
                "intake_calibrated,burn_calibrated,net_calibrated,weight_kg,trend_kg"
        )
        history.rows.forEach { row ->
            append(row.date)
            append(',').append(row.intakeKcal?.roundToInt() ?: "")
            append(',').append(row.burnKcal?.roundToInt() ?: "")
            append(',').append(row.netKcal?.roundToInt() ?: "")
            // Both scales, because a spreadsheet cannot ask which one these were.
            append(',').append(row.intakeKcal?.let { (it * intakeFactor).roundToInt() } ?: "")
            append(',').append(row.burnKcal?.let { (it * burnFactor).roundToInt() } ?: "")
            append(',').append(
                HistoryRepository.calibratedNet(row, settings)?.roundToInt() ?: ""
            )
            append(',').append(row.weightKg?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "")
            append(',').append(
                trendByDate[row.date]?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""
            )
            appendLine()
        }
    }
}
