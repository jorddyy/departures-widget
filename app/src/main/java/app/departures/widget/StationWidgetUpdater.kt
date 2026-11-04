package app.departures.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import app.departures.R
import app.departures.core.Board
import app.departures.core.Format
import app.departures.core.LineColors
import app.departures.core.RefreshPolicy
import app.departures.core.Stop
import app.departures.data.Stations
import app.departures.ui.StopPickerActivity
import app.departures.ui.Texts

/** Draws the station-board widgets: the next departures at one stop, every line and direction. */
object StationWidgetUpdater {

    private val ROWS = listOf(
        intArrayOf(R.id.r1, R.id.r1_line, R.id.r1_dest, R.id.r1_time, R.id.r1_delay, R.id.r1_count, R.id.r1_min, R.id.r1_line_bg),
        intArrayOf(R.id.r2, R.id.r2_line, R.id.r2_dest, R.id.r2_time, R.id.r2_delay, R.id.r2_count, R.id.r2_min, R.id.r2_line_bg),
        intArrayOf(R.id.r3, R.id.r3_line, R.id.r3_dest, R.id.r3_time, R.id.r3_delay, R.id.r3_count, R.id.r3_min, R.id.r3_line_bg),
        intArrayOf(R.id.r4, R.id.r4_line, R.id.r4_dest, R.id.r4_time, R.id.r4_delay, R.id.r4_count, R.id.r4_min, R.id.r4_line_bg),
        intArrayOf(R.id.r5, R.id.r5_line, R.id.r5_dest, R.id.r5_time, R.id.r5_delay, R.id.r5_count, R.id.r5_min, R.id.r5_line_bg),
        intArrayOf(R.id.r6, R.id.r6_line, R.id.r6_dest, R.id.r6_time, R.id.r6_delay, R.id.r6_count, R.id.r6_min, R.id.r6_line_bg),
        intArrayOf(R.id.r7, R.id.r7_line, R.id.r7_dest, R.id.r7_time, R.id.r7_delay, R.id.r7_count, R.id.r7_min, R.id.r7_line_bg),
    )

    /** Widget height (dp) needed for each row count; the launcher picks the largest that fits. */
    private val SIZES = listOf(2 to 40f, 3 to 90f, 4 to 120f, 5 to 150f, 7 to 200f)

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, StationWidget::class.java))

    /** Stops shown by any station widget. */
    fun stops(context: Context): List<Stop> {
        val stations = Stations(context)
        return ids(context).toList().mapNotNull { stations.stop(it) }.distinctBy { it.id }
    }

    /** Renders every station widget; returns when they next need redrawing, or null if there are none. */
    /** @param refreshingStopId the stop whose board you just tapped, shown as "updating…". */
    fun render(context: Context, refreshingStopId: String?, now: Long, minutes: Boolean): Long? {
        val ids = ids(context)
        if (ids.isEmpty()) return null
        val manager = AppWidgetManager.getInstance(context)
        val stations = Stations(context)
        var next = now + RefreshPolicy.MAX_RENDER_INTERVAL_MS
        for (id in ids) {
            val stop = stations.stop(id)
            val board = stop?.let { stations.board(it) }
            val offline = stop != null && stations.isOffline(stop)
            val refreshing = stop != null && stop.id == refreshingStopId
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(
                    SIZES.associate { (rows, height) ->
                        SizeF(180f, height) to build(context, id, stop, board, offline, refreshing, now, rows, minutes)
                    },
                )
            } else {
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                val rows = SIZES.lastOrNull { it.second <= height }?.first ?: SIZES.first().first
                build(context, id, stop, board, offline, refreshing, now, rows, minutes)
            }
            manager.updateAppWidget(id, views)
            val shown = board?.upcoming(now).orEmpty().take(SIZES.last().first)
            next = minOf(next, WidgetUpdater.nextTick(now, RefreshPolicy.nextRenderAt(now, board), shown, minutes))
        }
        return next
    }

    private fun choosePickerIntent(context: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        1000 + widgetId,
        Intent(context, StopPickerActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun build(
        context: Context,
        widgetId: Int,
        stop: Stop?,
        board: Board?,
        offline: Boolean,
        refreshing: Boolean,
        now: Long,
        rows: Int,
        minutes: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_station)
        // Tap: refresh now. Long-press → settings (Android's widget menu) changes the stop.
        views.setOnClickPendingIntent(
            android.R.id.background,
            WidgetUpdater.broadcast(context, DeparturesWidget.ACTION_REFRESH, 100 + widgetId, widgetId),
        )
        if (stop == null) {
            // No stop yet (or it was lost): a tap opens the stop search for this widget.
            views.setOnClickPendingIntent(android.R.id.background, choosePickerIntent(context, widgetId))
            views.setTextViewText(R.id.title, context.getString(R.string.station_widget_name))
            views.setTextViewText(R.id.subtitle, "")
            ROWS.forEach { views.setViewVisibility(it[0], View.GONE) }
            views.setViewVisibility(R.id.empty, View.VISIBLE)
            views.setTextViewText(R.id.empty, context.getString(R.string.station_setup_needed))
            return views
        }
        views.setTextViewText(R.id.title, stop.name)
        val fetched = board?.fetchedAtEpochMs
        views.setTextViewText(
            R.id.subtitle,
            when {
                refreshing -> context.getString(R.string.updating_short)
                offline -> context.getString(R.string.offline_short)
                fetched != null -> context.getString(R.string.updated_at, Format.clock(fetched))
                else -> ""
            },
        )
        val upcoming = board?.upcoming(now).orEmpty()
        ROWS.forEachIndexed { i, ids ->
            val d = upcoming.getOrNull(i)?.takeIf { i < rows }
            WidgetUpdater.bindRow(context, views, d, now, ids[0], ids[3], ids[4], ids[5], ids[6], minutes, stop.name)
            if (d != null) {
                views.setTextViewText(ids[1], d.line)
                WidgetUpdater.tintBadge(context, views, ids[7], ids[1], LineColors.of(d.operator, d.line))
                views.setTextViewText(ids[2], Texts.destinationWithPlatform(context, d, stop.name))
            }
        }
        val empty = Texts.empty(context, upcoming.isNotEmpty(), board != null, offline)
        views.setViewVisibility(R.id.empty, if (empty == null) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.empty, empty)
        return views
    }
}
