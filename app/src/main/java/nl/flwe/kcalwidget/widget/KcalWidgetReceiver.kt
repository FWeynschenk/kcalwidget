package nl.flwe.kcalwidget.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters

class KcalWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = KcalWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.enqueuePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshWorker.cancelPeriodic(context)
    }
}

/**
 * The refresh button on the widget.
 *
 * This hands the work to WorkManager rather than doing it here. An ActionCallback runs on
 * a broadcast receiver's budget, and a Health Connect read is far too slow for that: doing
 * it inline gets the whole process killed for a background ANR, which takes any open
 * screen down with it.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        RefreshWorker.refreshNow(context)
    }
}
