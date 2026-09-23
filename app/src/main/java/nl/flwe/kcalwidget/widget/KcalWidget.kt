package nl.flwe.kcalwidget.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import nl.flwe.kcalwidget.widget.ui.Headline
import nl.flwe.kcalwidget.widget.ui.MessageState
import nl.flwe.kcalwidget.widget.ui.Stat
import nl.flwe.kcalwidget.widget.ui.WidgetHeader
import nl.flwe.kcalwidget.widget.ui.WidgetShell
import nl.flwe.kcalwidget.widget.ui.WeighInNudge
import androidx.glance.layout.Row
import kotlin.math.roundToInt

/**
 * The default widget: what is left, how the day is going, and why.
 *
 * Renders only the cache written by [WidgetRepository]; see [WidgetState] for the shared
 * model the whole family reads.
 */
class KcalWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Render the cache immediately, and top it up in the background if it is stale.
        // The launcher composes the widget when it comes into view, so a short threshold
        // here is what makes the numbers current when the home screen is actually seen.
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
                val size = LocalSize.current
                WidgetShell {
                    if (model.status != WidgetStatus.OK) {
                        MessageState(model.status)
                    } else {
                        WidgetHeader(model, compact = size.height < MEDIUM.height)
                        Headline(model, night)
                        if (size.height >= MEDIUM.height) {
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
                                Stat("Out", model.projectedBurn, GlanceModifier.defaultWeight())
                                if (size.width >= LARGE.width) {
                                    Stat("Goal", model.budget, GlanceModifier.defaultWeight())
                                }
                            }
                            Spacer(GlanceModifier.height(6.dp))
                            if (model.weighInDue) {
                                WeighInNudge(model)
                            } else {
                                Text(
                                    text = advice(model),
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
    }

    companion object {
        private val SMALL = DpSize(120.dp, 48.dp)
        private val MEDIUM = DpSize(180.dp, 120.dp)
        private val LARGE = DpSize(280.dp, 120.dp)
    }
}

private fun advice(model: WidgetModel): String = when {
    model.isOver && model.walkMinutes > 0 ->
        "Move ${model.moveKcal.roundToInt()} kcal, about ${model.walkMinutes} min brisk walk"

    !model.hasNutrition -> "No food logged today, burn from ${burnSourceLabel(model.burnSource)}"
    model.calibrationApplied -> "Calibrated from your weight trend"
    else -> "Burn from ${burnSourceLabel(model.burnSource)}"
}
