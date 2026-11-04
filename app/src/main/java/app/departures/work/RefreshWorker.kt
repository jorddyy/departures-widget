package app.departures.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.departures.data.Commute
import app.departures.data.Stations
import app.departures.widget.StationWidgetUpdater
import app.departures.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * Fetches departures if needed and redraws the widgets. Runs off the main thread, outside the
 * short broadcast window, and only when a network is available.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userInitiated = inputData.getBoolean(KEY_USER, false)
        val context = applicationContext
        if (WidgetUpdater.hasCommuteWidgets(context)) Commute(context).refresh(userInitiated = userInitiated)
        Stations(context).refresh(StationWidgetUpdater.stops(context), userInitiated)
        WidgetUpdater.renderAll(context)
        return Result.success()
    }

    companion object {
        private const val KEY_USER = "user"
        private const val ONE_SHOT = "refresh"
        private const val PERIODIC = "refresh-periodic"

        /** Ask for a refresh soon. A user request replaces any pending automatic one. */
        fun enqueue(context: Context, userInitiated: Boolean) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(KEY_USER to userInitiated))
                .build()
            val policy = if (userInitiated) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, policy, request)
        }

        /**
         * Safety net that keeps the widget from going stale if the render chain is interrupted.
         * It never wakes the radio while the screen is off (see [Commute.refresh]).
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Stops background refreshes once the last widget of either kind is removed. */
        fun cancelIfUnused(context: Context) {
            if (WidgetUpdater.hasCommuteWidgets(context) || StationWidgetUpdater.ids(context).isNotEmpty()) return
            cancelAll(context)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(ONE_SHOT)
                cancelUniqueWork(PERIODIC)
            }
        }
    }
}
