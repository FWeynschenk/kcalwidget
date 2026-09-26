package nl.flwe.kcalwidget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Labels the vertical range of a chart: highest value at the top, lowest at the bottom,
 * beside the plot rather than under it.
 *
 * A min and a max laid out left to right under a time series reads as "then" and "now",
 * which is the one thing they are not. Every chart in the app went through that mistake
 * once, so the fix lives here rather than in each screen.
 */
@Composable
fun VerticalAxis(
    /**
     * Each label and where it sits, 0 at the top of the plot and 1 at the bottom. Given as
     * positions rather than top/middle/bottom because an axis that does not straddle zero
     * evenly would otherwise print "0" at the vertical centre, where zero is not.
     */
    labels: List<Pair<Float, String>>,
    chartHeight: Dp,
    /** Rendered directly under the plot, inside the same column, so it lines up exactly. */
    below: @Composable () -> Unit = {},
    chart: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.height(chartHeight).padding(end = 8.dp)) {
            spacedLabels(labels).forEach { (fraction, text) ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = chartHeight * fraction.coerceIn(0f, 1f) - LABEL_HALF_HEIGHT),
                )
            }
        }
        // The gutter is as wide as its widest label, so guessing an inset for the date
        // row would put it a few dp out on every device. It goes in this column instead.
        Column(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.height(chartHeight)) { chart() }
            below()
        }
    }
}

/** Nudges a label up by half a line so it centres on its value rather than hanging below it. */
private val LABEL_HALF_HEIGHT = 8.dp

/** Two labels closer together than this would overprint each other. */
private const val MIN_LABEL_GAP = 0.09f

/**
 * Drops labels that would land on top of one another, earlier entries winning.
 *
 * A month of nothing but deficits puts the largest surplus a hair above zero, and "+136"
 * printed across "0" is worse than no maximum at all. Callers pass the reference value
 * first so it is the one that survives.
 */
internal fun spacedLabels(labels: List<Pair<Float, String>>): List<Pair<Float, String>> {
    val kept = mutableListOf<Pair<Float, String>>()
    labels.forEach { candidate ->
        if (kept.none { abs(it.first - candidate.first) < MIN_LABEL_GAP }) kept += candidate
    }
    return kept
}

/** Oldest on the left, newest on the right, which is the direction the charts are drawn. */
@Composable
fun DateAxis(first: LocalDate, last: LocalDate) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(DAY_LABEL.format(first), style = MaterialTheme.typography.labelSmall)
        Text(DAY_LABEL.format(last), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * A chart you can scrub: drag or tap anywhere across it to pick a point, with a readout
 * of whatever was picked.
 *
 * Every chart in the app answers a shape question well and a "what was it on Tuesday"
 * question not at all, and the underlying numbers are already loaded -- they were simply
 * not reachable. One implementation rather than three, because the hit-testing and the
 * gesture handling are the fiddly parts and there is no reason to get them right
 * repeatedly.
 *
 * [xFractions] is where each point sits across the width, 0..1, one entry per point. That
 * is what makes this work for both evenly spaced series and the weight chart, whose points
 * are placed by date and so are not evenly spaced at all.
 */
@Composable
fun ScrubbableChart(
    xFractions: List<Float>,
    chartHeight: Dp,
    /** Axis labels and where each one sits vertically, 0 at the top and 1 at the bottom. */
    axisLabels: List<Pair<Float, String>>,
    markerColor: Color,
    below: @Composable () -> Unit = {},
    readout: @Composable (index: Int) -> Unit,
    chart: DrawScope.(selected: Int) -> Unit,
) {
    if (xFractions.isEmpty()) return

    // Opening on the newest point means there is always something in the readout; an
    // empty one would just be a second thing to figure out.
    var selected by remember(xFractions.size) { mutableIntStateOf(xFractions.lastIndex) }
    val nearest: (Float, Int) -> Unit = { x, width ->
        selected = nearestIndex(xFractions, if (width <= 0) 0f else x / width)
    }

    readout(selected.coerceIn(xFractions.indices))
    Spacer(Modifier.height(6.dp))

    VerticalAxis(labels = axisLabels, chartHeight = chartHeight, below = below) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                // Two blocks rather than one: a tap never travels far enough to trip the
                // drag slop, so a single drag detector would ignore it.
                .pointerInput(xFractions) {
                    detectTapGestures { nearest(it.x, size.width) }
                }
                .pointerInput(xFractions) {
                    detectHorizontalDragGestures(
                        onDragStart = { nearest(it.x, size.width) },
                    ) { change, _ ->
                        nearest(change.position.x, size.width)
                        // Claimed, so the list underneath does not also scroll sideways.
                        change.consume()
                    }
                },
        ) {
            val index = selected.coerceIn(xFractions.indices)
            chart(index)
            drawLine(
                color = markerColor,
                start = Offset(xFractions[index] * size.width, 0f),
                end = Offset(xFractions[index] * size.width, size.height),
                strokeWidth = 2f,
            )
        }
    }
}

/**
 * Which point a touch at [fraction] of the way across the chart is pointing at.
 *
 * Nearest by position rather than by bucket: the weight chart places its points by date,
 * so they are not evenly spaced and a divide-into-n-slots approach would pick the wrong
 * day whenever weigh-ins are irregular, which is always.
 */
internal fun nearestIndex(xFractions: List<Float>, fraction: Float): Int {
    if (xFractions.isEmpty()) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return xFractions.indices.minByOrNull { abs(xFractions[it] - clamped) } ?: 0
}

/**
 * The header above a scrubbed chart: which point is selected, and what it was.
 *
 * Laid out so the values keep their position as the marker moves. A readout that reflows
 * on every drag is unreadable while dragging, which is the only time it is on screen.
 */
@Composable
fun ChartReadout(title: String, values: List<Pair<String, String>>) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        values.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * How much history a chart shows.
 *
 * The full ninety days are already in memory, so this is purely a matter of what is drawn:
 * a quarter's worth of bars is four pixels wide each and answers nothing about last week.
 */
enum class ChartRange(val days: Int, val label: String) {
    WEEK(7, "7 days"),
    MONTH(30, "30 days"),
    QUARTER(90, "90 days"),
}

/**
 * Picks the span a chart covers. Ranges longer than the data are left out rather than
 * offered and then silently clamped.
 */
@Composable
fun RangeSelector(selected: ChartRange, available: Int, onSelect: (ChartRange) -> Unit) {
    // Always keep the shortest span and whatever is currently chosen, so the chips can
    // never disagree with what the chart is actually showing.
    val offered = ChartRange.entries.filter {
        it == ChartRange.entries.first() || it == selected || it.days <= available
    }
    if (offered.size < 2) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        offered.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(range.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/** Formats a date for a chart readout. */
fun chartDate(date: LocalDate): String = READOUT_DATE.format(date)

private val READOUT_DATE = DateTimeFormatter.ofPattern("EEE d MMM")

/** A colour swatch and what it means, so a two-series chart can be read without guessing. */
@Composable
fun ChartLegend(entries: List<Pair<Color, String>>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        entries.forEach { (colour, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 5.dp)
                        .size(10.dp)
                        .background(colour, CircleShape),
                )
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val DAY_LABEL = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** A tappable row that leads somewhere, used for the settings index. */
@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A numeric field that keeps its own text while being typed into.
 *
 * The committed value round-trips through DataStore, which would otherwise fight the
 * cursor; the field owns the text and only pushes parseable values outwards.
 */
@Composable
fun NumberField(
    label: String,
    value: String,
    /** True for a field that takes a fraction, which needs a separator key on the keypad. */
    decimal: Boolean = false,
    onValue: (String) -> Unit,
) {
    val state = remember(label) { mutableStateOf(value) }
    // Re-sync when the value is changed from somewhere else -- tapping a preset -- but not
    // from our own keystrokes, which would fight the cursor on every character. Comparing
    // the parsed numbers rather than the text means "85," mid-typing is left alone.
    LaunchedEffect(value) {
        if (parseDecimal(value) != parseDecimal(state.value)) state.value = value
    }
    OutlinedTextField(
        value = state.value,
        onValueChange = {
            state.value = it
            onValue(it)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * Reads a number the way the person in front of the phone would write it.
 *
 * toDoubleOrNull accepts a full stop and nothing else, so on a Dutch or German keypad --
 * which offers a comma, and whose own formatting produces one -- every weight typed in
 * was silently discarded, and the target-weight field could not even parse the value it
 * had just displayed.
 *
 * With both separators present the last one is the decimal point, which is true in every
 * convention. One separator, appearing once, is a decimal point. Repeated separators are
 * digit grouping.
 */
fun parseDecimal(text: String): Double? {
    val cleaned = text.trim().filterNot { it == ' ' || it == ' ' || it == ' ' }
    if (cleaned.isEmpty()) return null

    val lastComma = cleaned.lastIndexOf(',')
    val lastDot = cleaned.lastIndexOf('.')
    val normalised = when {
        lastComma >= 0 && lastDot >= 0 -> {
            val decimalAt = maxOf(lastComma, lastDot)
            cleaned.mapIndexed { i, c ->
                when {
                    i == decimalAt -> '.'
                    c == ',' || c == '.' -> null
                    else -> c
                }
            }.filterNotNull().joinToString("")
        }
        cleaned.count { it == ',' } == 1 -> cleaned.replace(',', '.')
        cleaned.count { it == '.' } == 1 -> cleaned
        else -> cleaned.filterNot { it == ',' || it == '.' }
    }
    return normalised.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** A lone separator with exactly three digits behind it, repeated or not: "1.200". */
private val GROUPED_DIGITS = Regex("""^-?\d{1,3}([.,]\d{3})+$""")

/**
 * [parseDecimal] for a field that cannot hold a fraction.
 *
 * "1.200" is genuinely ambiguous in general -- twelve hundred to a Dutch reader, one and
 * a fifth to an English one -- but not here: the field takes whole kilocalories, so the
 * reading that involves a fraction is the one the user cannot have meant. A separator
 * with fewer than three digits behind it is still a decimal point, and gets rounded.
 */
fun parseWholeNumber(text: String): Int? {
    val cleaned = text.trim().filterNot { it == ' ' || it == ' ' || it == ' ' }
    if (GROUPED_DIGITS.matches(cleaned)) {
        return cleaned.filterNot { it == '.' || it == ',' }.toIntOrNull()
    }
    return parseDecimal(text)
        ?.takeIf { it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble() }
        ?.roundToInt()
}

@Composable
fun Explainer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}
