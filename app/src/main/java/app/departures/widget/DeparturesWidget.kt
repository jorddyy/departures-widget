package app.departures.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import app.departures.data.Commute
import app.departures.data.Stations
import app.departures.work.RefreshWorker

/**
 * Commute widget: next departures between your Home and Work stops, in the direction you're
 * likely heading. Drawing is done by [WidgetUpdater] from cached data; network work is delegated
 * to [RefreshWorker] so the broadcast returns immediately.
 *
 * This receiver also handles the actions shared by both widget types (redraw alarm, refresh).
 */
class DeparturesWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        RefreshWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.renderAll(context) // cancels the redraw alarm if no widgets remain
        RefreshWorker.cancelIfUnused(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Called when a widget is added, after reboot and after app updates.
        RefreshWorker.schedulePeriodic(context)
        WidgetUpdater.renderAll(context)
        RefreshWorker.enqueue(context, userInitiated = false)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        // Resized: pick the compact or full layout again (Android 11 and older).
        WidgetUpdater.renderAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                // "Alarms & reminders" was granted: switch to the whole-minute display now.
                WidgetUpdater.renderAll(context)
            }
            ACTION_TICK -> {
                WidgetUpdater.renderAll(context)
                val stops = StationWidgetUpdater.stops(context)
                if (Commute(context).needsFetch() || Stations(context).needsFetch(stops)) {
                    RefreshWorker.enqueue(context, userInitiated = false)
                }
            }
            ACTION_REFRESH -> {
                WidgetUpdater.renderAll(context, refreshing = true)
                RefreshWorker.enqueue(context, userInitiated = true)
            }
            ACTION_SWAP -> {
                Commute(context).toggleDirection()
                WidgetUpdater.renderAll(context)
                RefreshWorker.enqueue(context, userInitiated = false)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_TICK = "app.departures.TICK"
        const val ACTION_REFRESH = "app.departures.REFRESH"
        const val ACTION_SWAP = "app.departures.SWAP"
    }
}
