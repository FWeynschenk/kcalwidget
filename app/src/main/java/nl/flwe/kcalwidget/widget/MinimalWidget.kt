package nl.flwe.kcalwidget.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.state.PreferencesGlanceStateDefinition
import nl.flwe.kcalwidget.widget.ui.Headline
import nl.flwe.kcalwidget.widget.ui.MessageState
import nl.flwe.kcalwidget.widget.ui.WidgetShell

/**
 * One number, for a crowded home screen.
 *
 * No nudge and no advice line: at 2x1 there is no room, and a truncated warning is worse
 * than none.
 */
class MinimalWidget : GlanceAppWidget() {

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
                WidgetShell {
                    if (model.status != WidgetStatus.OK) {
                        MessageState(model.status)
                    } else {
                        Headline(model, night, fontSize = 26)
                    }
                }
            }
        }
    }
}

class MinimalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MinimalWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.enqueuePeriodic(context)
    }
}
