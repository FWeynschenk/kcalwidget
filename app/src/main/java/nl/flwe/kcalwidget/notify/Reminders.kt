package nl.flwe.kcalwidget.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.data.history.HistoryRepository
import nl.flwe.kcalwidget.widget.WidgetRepository
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.SettingsRepository
import nl.flwe.kcalwidget.data.weight.Calibration
import nl.flwe.kcalwidget.data.weight.CalibrationResult
import java.util.concurrent.TimeUnit

/** Pure decisions about when to say something, kept out of the worker so they are testable. */
object Reminders {

    /** A reminder is not worth repeating more often than this, however overdue it gets. */
    const val MIN_GAP_MS = 20 * 60 * 60 * 1000L

    fun weighInNotificationDue(
        daysSinceWeighIn: Int?,
        settings: AppSettings,
        lastNotifiedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!settings.features.weighInNotification) return false
        if (now - lastNotifiedAt < MIN_GAP_MS) return false
        val days = daysSinceWeighIn ?: return true
        return days >= settings.features.weighInIntervalDays
    }

    /**
     * Whether the goal has just been reached and not yet celebrated.
     *
     * Judged on the trend, so a single light morning cannot trigger it, and only once per
     * target — [AppSettings.goal].goalAchievedAt is cleared when a new target is chosen.
     */
    fun goalJustReached(trendKg: Double?, settings: AppSettings): Boolean {
        if (settings.goal.targetWeightKg == null) return false
        if (settings.goal.goalAchievedAt != null) return false
        val trend = trendKg ?: return false
        return settings.goal.isReached(trend)
    }
}

/**
 * Runs once a day to decide whether anything is worth saying.
 *
 * Both notifications are opt-in and independent of the widget nudge, which is on by
 * default and costs nothing because it only appears where you are already looking.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        check()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    private suspend fun check() {
        val context = applicationContext
        val repo = SettingsRepository(context)
        val settings = repo.settings.first()
        val health = HealthRepository(context)
        if (!health.hasRequiredPermissions()) return

        val snapshot = health.readToday(settings) ?: return
        val daysSince = snapshot.lastWeighInAt
            ?.let { java.time.Duration.between(it, java.time.Instant.now()).toDays().toInt() }

        if (Reminders.weighInNotificationDue(daysSince, settings, settings.reminders.lastWeighInNotifiedAt)) {
            Notifications.postWeighIn(
                context,
                daysSince,
                settings.features.weighInIntervalDays,
            )
            repo.update {
                it.copy(
                    reminders = it.reminders.copy(
                        lastWeighInNotifiedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        // Both the goal check and the recalibration need the smoothed trend, so the
        // history is read once here. Daily that is affordable; on a widget refresh it
        // would not be.
        val history = runCatching { HistoryRepository(context, health).load(settings) }
            .getOrNull()
            ?: return

        if (settings.goal.targetWeightKg != null && settings.goal.goalAchievedAt == null) {
            if (Reminders.goalJustReached(history.trend.currentTrendKg, settings)) {
                repo.update {
                    it.copy(goal = it.goal.copy(goalAchievedAt = System.currentTimeMillis()))
                }
                Notifications.postGoalReached(context, settings.goal.targetWeightKg)
            }
        }

        recheckCalibration(context, repo, settings, history)
    }

    /**
     * Re-measures the numbers against the scale, and nudges the correction if the user has
     * asked for that.
     *
     * Without this, "apply automatically" would only mean "keep applying whatever was
     * measured the one time someone opened the screen". The measurement is always refreshed
     * so the home screen's prompt stays honest, even when correction is switched off.
     */
    private suspend fun recheckCalibration(
        context: Context,
        repo: SettingsRepository,
        settings: nl.flwe.kcalwidget.data.settings.AppSettings,
        history: nl.flwe.kcalwidget.data.history.History,
    ) {
        val result = Calibration.analyse(history, settings.calibration)
        val factors = Calibration.nextFactors(
            current = settings.calibration,
            result = result,
            autoEnabled = settings.features.autoCalibration,
        )
        val bias = (result as? CalibrationResult.Bias)?.kcalPerDay

        repo.update {
            it.copy(
                calibration = it.calibration.copy(
                    lastBiasKcal = bias,
                    lastComputedAt = System.currentTimeMillis(),
                    expenditureFactor = factors.expenditure,
                    intakeFactor = factors.intake,
                )
            )
        }
        // A changed correction changes the budget, so the widget should not keep showing
        // yesterday's.
        runCatching { WidgetRepository.refresh(context) }
    }

    companion object {
        private const val NAME = "kcal-daily-reminders"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
