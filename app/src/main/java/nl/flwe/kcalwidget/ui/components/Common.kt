package nl.flwe.kcalwidget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    top: String,
    bottom: String,
    middle: String? = null,
    chartHeight: Dp,
    /** Rendered directly under the plot, inside the same column, so it lines up exactly. */
    below: @Composable () -> Unit = {},
    chart: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.height(chartHeight).padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(top, style = MaterialTheme.typography.labelSmall)
            if (middle != null) Text(middle, style = MaterialTheme.typography.labelSmall)
            Text(bottom, style = MaterialTheme.typography.labelSmall)
        }
        // The gutter is as wide as its widest label, so guessing an inset for the date
        // row would put it a few dp out on every device. It goes in this column instead.
        Column(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.height(chartHeight)) { chart() }
            below()
        }
    }
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
fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    val state = remember(label) { mutableStateOf(value) }
    OutlinedTextField(
        value = state.value,
        onValueChange = {
            state.value = it
            onValue(it)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun Explainer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}
