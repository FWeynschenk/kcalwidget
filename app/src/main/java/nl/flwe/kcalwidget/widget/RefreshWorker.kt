package nl.flwe.kcalwidget.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps the widget's cached numbers fresh.
 *
 * Health Connect has no push notification for new data, so this polls on WorkManager's
 * 15 minute floor. Reading while the app is backgrounded needs the background read
 * permission; without it the periodic run simply keeps the last values and the widget
 * catches up the next time the app is opened or the refresh button is tapped.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        WidgetRepository.refresh(applicationContext, inputData.getLong(KEY_MIN_AGE_MS, 0L))
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val PERIODIC_NAME = "kcal-widget-periodic-refresh"
        private const val ONE_SHOT_NAME = "kcal-widget-refresh-now"
        private const val KEY_MIN_AGE_MS = "min_age_ms"

        /**
         * Opportunistic refreshes happen whenever the launcher composes the widget, which
         * can be many times a minute. Skip the read when the cache is still this fresh.
         */
        const val MIN_AGE_MS = 2 * 60 * 1000L

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        fun refreshNow(context: Context) {
            enqueueOneShot(context, minAgeMs = 0L)
        }

        /**
         * Called when the launcher asks Glance to compose the widget. Health Connect has no
         * change broadcast and USER_PRESENT is not delivered to manifest receivers, so this
         * is the closest thing to "refresh when it is actually looked at".
         */
        fun refreshIfStale(context: Context) {
            enqueueOneShot(context, minAgeMs = MIN_AGE_MS)
        }

        private fun enqueueOneShot(context: Context, minAgeMs: Long) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(workDataOf(KEY_MIN_AGE_MS to minAgeMs))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
