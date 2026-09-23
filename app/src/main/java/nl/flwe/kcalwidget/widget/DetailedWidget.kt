package nl.flwe.kcalwidget.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import nl.flwe.kcalwidget.widget.ui.Headline
import nl.flwe.kcalwidget.widget.ui.MessageState
import nl.flwe.kcalwidget.widget.ui.Stat
import nl.flwe.kcalwidget.widget.ui.TextStat
import nl.flwe.kcalwidget.widget.ui.WeighInNudge
import nl.flwe.kcalwidget.widget.ui.WidgetHeader
import nl.flwe.kcalwidget.widget.ui.WidgetShell
import kotlin.math.roundToInt

/**
 * The whole picture, for a dedicated widget page.
 *
 * Shows burned-so-far alongside the projection, so the gap between what the tracker has
 * actually recorded and what the model expects by midnight is visible rather than implied.
 */
class DetailedWidget : GlanceAppWidget() {

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
                val accent = model.accentPair.resolve(night)
                WidgetShell {
                    if (model.status != WidgetStatus.OK) {
                        MessageState(model.status)
                        return@WidgetShell
                    }
                    WidgetHeader(model, compact = false)
                    Headline(model, night)
                    Spacer(GlanceModifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = model.progress,
                        modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                        color = accent,
                        backgroundColor = GlanceTheme.colors.secondaryContainer,
                    )
                    Spacer(GlanceModifier.height(10.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Stat("In", model.intake, GlanceModifier.defaultWeight())
                        Stat("Burned", model.burnedSoFar, GlanceModifier.defaultWeight())
                        Stat("By midnight", model.projectedBurn, GlanceModifier.defaultWeight())
                        Stat("Goal", model.budget, GlanceModifier.defaultWeight())
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Stat("Typical", model.typicalDay, GlanceModifier.defaultWeight())
                        TextStat(
                            label = "Today's data",
                            value = "${(model.confidence * 100).roundToInt()}%",
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        TextStat(
                            label = "Weight",
                            value = model.trendWeightKg?.let { "%.1f".format(it) }
                                ?: "%.1f".format(model.weightKg),
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
                    Spacer(GlanceModifier.height(8.dp))
                    if (model.weighInDue) {
                        WeighInNudge(model)
                    } else {
                        Text(
                            text = detail(model),
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun detail(model: WidgetModel): String = when {
    model.isOver && model.walkMinutes > 0 ->
        "Move ${model.moveKcal.roundToInt()} kcal, about ${model.walkMinutes} min brisk walk"

    !model.hasNutrition -> "No food logged today"
    model.calibrationApplied -> "Burn from ${burnSourceLabel(model.burnSource)}, calibrated"
    else -> "Burn from ${burnSourceLabel(model.burnSource)}"
}

class DetailedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DetailedWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.enqueuePeriodic(context)
    }
}
