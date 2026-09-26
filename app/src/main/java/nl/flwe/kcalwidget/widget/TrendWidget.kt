package nl.flwe.kcalwidget.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import nl.flwe.kcalwidget.widget.chart.TrendChart
import nl.flwe.kcalwidget.widget.ui.MessageState
import nl.flwe.kcalwidget.widget.ui.Stat
import nl.flwe.kcalwidget.widget.ui.TextStat
import nl.flwe.kcalwidget.widget.ui.WidgetHeader
import nl.flwe.kcalwidget.widget.ui.WidgetShell
import kotlin.math.roundToInt

/**
 * Is the deficit actually landing?
 *
 * Daily net bars with the smoothed weight line over them. A fortnight of deficit bars
 * under a flat weight line is the picture calibration turns into a number.
 */
class TrendWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cachedAt = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
            .let { it[WidgetKeys.UPDATED_AT] ?: 0L }
        if (System.currentTimeMillis() - cachedAt > RefreshWorker.MIN_AGE_MS) {
            RefreshWorker.refreshIfStale(context)
        }

        val night = context.isNightMode()
        provideContent {
            GlanceTheme {
                val model = currentState<Preferences>().toWidgetModel()
                val size = LocalSize.current
                val density = LocalContext.current.resources.displayMetrics.density

                WidgetShell {
                    if (model.status != WidgetStatus.OK) {
                        MessageState(model.status)
                        return@WidgetShell
                    }
                    WidgetHeader(model, compact = false, title = "Last two weeks")
                    Spacer(GlanceModifier.height(6.dp))

                    if (model.netSeries.isEmpty() && model.weightSeries.isEmpty()) {
                        Text(
                            text = "Not enough history yet to draw a trend.",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                        )
                    } else {
                        val chartHeightDp = (size.height.value - 70f).coerceIn(48f, 160f)
                        val bitmap = TrendChart.render(
                            widthPx = ((size.width.value - 28f) * density).roundToInt(),
                            heightPx = (chartHeightDp * density).roundToInt(),
                            netSeries = model.netSeries,
                            weightSeries = model.weightSeries,
                            targetNetKcal = model.goalDeltaKcal,
                            night = night,
                        )
                        Image(
                            provider = ImageProvider(bitmap),
                            contentDescription = "Net balance and weight over the last two weeks",
                            contentScale = ContentScale.FillBounds,
                            modifier = GlanceModifier.fillMaxWidth().height(chartHeightDp.dp),
                        )
                    }

                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = if (model.goalDeltaKcal != 0.0) {
                            "Bars: daily net against your " +
                                "${model.goalDeltaKcal.roundToInt()} kcal goal (dashed). " +
                                "Line: weight."
                        } else {
                            "Bars: daily net against the zero line. Line: weight."
                        },
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                        ),
                    )

                    Spacer(GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Stat("Left today", model.remaining, GlanceModifier.defaultWeight())
                        TextStat(
                            label = "Weight",
                            value = model.trendWeightKg?.let { "%.1f".format(it) } ?: "—",
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        TextStat(
                            label = "Per week",
                            value = model.weeklyTrendKg
                                ?.let { "${if (it >= 0) "+" else ""}${"%.2f".format(it)}" }
                                ?: "—",
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                }
            }
        }
    }
}

class TrendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrendWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.enqueuePeriodic(context)
    }
}
