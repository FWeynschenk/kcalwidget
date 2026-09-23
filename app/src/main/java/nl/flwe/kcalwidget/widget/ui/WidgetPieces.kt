package nl.flwe.kcalwidget.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import nl.flwe.kcalwidget.MainActivity
import nl.flwe.kcalwidget.R
import nl.flwe.kcalwidget.widget.RefreshAction
import nl.flwe.kcalwidget.widget.WidgetModel
import nl.flwe.kcalwidget.widget.WidgetStatus
import nl.flwe.kcalwidget.widget.messageFor
import nl.flwe.kcalwidget.widget.resolve
import kotlin.math.abs
import kotlin.math.roundToInt

/** The card every widget sits in: background, rounding, padding, tap-to-open. */
@Composable
fun WidgetShell(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        content = content,
    )
}

@Composable
internal fun WidgetHeader(model: WidgetModel, compact: Boolean, title: String? = null) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = title ?: if (model.isOver) "Over budget" else "Left today",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        if (!compact) {
            Text(
                text = model.updatedAtLabel(),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.size(6.dp))
        }
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Refresh",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier
                .size(18.dp)
                .clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

@Composable
internal fun Headline(model: WidgetModel, night: Boolean, fontSize: Int = 30) {
    val accent = model.accentPair.resolve(night)
    Row(verticalAlignment = Alignment.Vertical.Bottom) {
        Text(
            text = abs(model.remaining).roundToInt().toString(),
            style = TextStyle(
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            ),
        )
        Spacer(GlanceModifier.size(4.dp))
        Text(
            text = "kcal",
            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.padding(bottom = 3.dp),
        )
    }
}

@Composable
fun Stat(label: String, value: Double, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
        Text(
            text = value.roundToInt().toString(),
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurface,
            ),
        )
    }
}

/** A text stat, for values that are not plain kcal. */
@Composable
fun TextStat(label: String, value: String, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurface,
            ),
        )
    }
}

/**
 * The weigh-in reminder, on the widgets with room for it.
 *
 * Calibration needs a recent weight to say anything, so the nudge escalates rather than
 * repeating itself: it is the difference between a suggestion and an explanation.
 */
@Composable
internal fun WeighInNudge(model: WidgetModel) {
    val days = model.daysSinceWeighIn
    val message = when {
        days == null -> "Weigh in to start tracking your trend"
        days >= 14 -> "$days days since a weigh-in — calibration is paused"
        else -> "$days days since a weigh-in"
    }
    Text(
        text = message,
        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
    )
}

@Composable
fun MessageState(status: WidgetStatus) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.Start,
    ) {
        Text(
            text = "Kcal Balance",
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurface,
            ),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = messageFor(status),
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}
