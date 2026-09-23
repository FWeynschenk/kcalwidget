package nl.flwe.kcalwidget.notify

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import nl.flwe.kcalwidget.MainActivity

/**
 * The app's two notification channels.
 *
 * They are separate so a weigh-in nudge can be silenced in Android's settings without also
 * silencing the one message you would actually want: that you reached your goal.
 */
object Notifications {

    const val CHANNEL_WEIGH_IN = "weigh_in"
    const val CHANNEL_GOAL = "goal"

    private const val ID_WEIGH_IN = 1001
    private const val ID_GOAL = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WEIGH_IN,
                "Weigh-in reminders",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "A nudge when a weigh-in is due, so calibration keeps working."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GOAL,
                "Goal milestones",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "When you reach a weight goal."
            }
        )
    }

    /** Whether a notification would actually be shown, rather than silently dropped. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun postWeighIn(context: Context, daysSince: Int?, intervalDays: Int) {
        if (!canPost(context)) return
        val text = when {
            daysSince == null -> "Weigh in to start tracking your trend."
            daysSince >= intervalDays * 2 ->
                "$daysSince days since your last weigh-in. Calibration is paused until there " +
                    "is a recent weight to compare against."

            else -> "$daysSince days since your last weigh-in."
        }
        post(context, CHANNEL_WEIGH_IN, ID_WEIGH_IN, "Time to weigh in", text)
    }

    fun postGoalReached(context: Context, targetKg: Double) {
        if (!canPost(context)) return
        post(
            context,
            CHANNEL_GOAL,
            ID_GOAL,
            "You reached your goal",
            "Your trend weight has reached ${"%.1f".format(targetKg)} kg. Open the app to " +
                "pick your next milestone.",
        )
    }

    // Every caller goes through canPost() first, which lint cannot follow; the notify call
    // is wrapped anyway, so a revoked permission is a no-op rather than a crash.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = android.app.PendingIntent.getActivity(
            context,
            id,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
