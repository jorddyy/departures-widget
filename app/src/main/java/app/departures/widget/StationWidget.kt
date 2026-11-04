package app.departures.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import app.departures.data.Stations
import app.departures.work.RefreshWorker

/**
 * Station-board widget. Its stop is chosen in [app.departures.ui.StopPickerActivity] when the
 * widget is added (and again from the widget's settings). Drawing and refreshing are shared
 * with the commute widget.
 */
class StationWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        RefreshWorker.schedulePeriodic(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RefreshWorker.schedulePeriodic(context)
        WidgetUpdater.renderAll(context)
        RefreshWorker.enqueue(context, userInitiated = false)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.renderAll(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val stations = Stations(context)
        ids.forEach { stations.setStop(it, null) }
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.renderAll(context) // cancels the redraw alarm if no widgets remain
        RefreshWorker.cancelIfUnused(context)
    }
}
