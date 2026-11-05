package app.departures.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import app.departures.R
import app.departures.core.Board
import app.departures.core.Departure
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
        intArrayOf(R.id.r1, R.id.r1_line, R.id.r1_dest, R.id.r1_time, R.id.r1_delay, R.id.r1_count, R.id.r1_min, R.id.r1_line_bg, R.id.r1_platform),
        intArrayOf(R.id.r2, R.id.r2_line, R.id.r2_dest, R.id.r2_time, R.id.r2_delay, R.id.r2_count, R.id.r2_min, R.id.r2_line_bg, R.id.r2_platform),
        intArrayOf(R.id.r3, R.id.r3_line, R.id.r3_dest, R.id.r3_time, R.id.r3_delay, R.id.r3_count, R.id.r3_min, R.id.r3_line_bg, R.id.r3_platform),
        intArrayOf(R.id.r4, R.id.r4_line, R.id.r4_dest, R.id.r4_time, R.id.r4_delay, R.id.r4_count, R.id.r4_min, R.id.r4_line_bg, R.id.r4_platform),
        intArrayOf(R.id.r5, R.id.r5_line, R.id.r5_dest, R.id.r5_time, R.id.r5_delay, R.id.r5_count, R.id.r5_min, R.id.r5_line_bg, R.id.r5_platform),
        intArrayOf(R.id.r6, R.id.r6_line, R.id.r6_dest, R.id.r6_time, R.id.r6_delay, R.id.r6_count, R.id.r6_min, R.id.r6_line_bg, R.id.r6_platform),
        intArrayOf(R.id.r7, R.id.r7_line, R.id.r7_dest, R.id.r7_time, R.id.r7_delay, R.id.r7_count, R.id.r7_min, R.id.r7_line_bg, R.id.r7_platform),
    )

    /**
     * Widget height (dp) needed for each row count; the launcher picks the largest that fits.
     * About 45dp for the header and padding plus 26dp per row. That is more than a row measures,
     * because One UI reports a widget taller than it draws it; at these thresholds the last
     * row is never cut off.
     */
    private val SIZES = listOf(2 to 40f, 3 to 123f, 4 to 149f, 5 to 175f, 6 to 201f, 7 to 227f)

    /** Countdowns in this many top rows drive the minute redraw (the rest scroll into view later). */
    private const val LIST_TICK_ROWS = 12

    /** At most this many departures in the scrollable list. */
    internal const val LIST_MAX_ROWS = 40

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
                // Android 12+: one layout with a scrollable list of every fetched departure.
                buildList(context, id, stop, board, offline, refreshing, now, minutes)
            } else {
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                val rows = SIZES.lastOrNull { it.second <= height }?.first ?: SIZES.first().first
                build(context, id, stop, board, offline, refreshing, now, rows, minutes)
            }
            manager.updateAppWidget(id, views)
            // Redraw when a countdown near the top of the board changes.
            val shown = board?.upcoming(now).orEmpty().take(LIST_TICK_ROWS)
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

    /** Header (stop name and status) and the tap action, shared by both layouts. */
    private fun bindHeader(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        stop: Stop?,
        board: Board?,
        offline: Boolean,
        refreshing: Boolean,
    ) {
        // Tap: refresh now. Long-press → settings (Android's widget menu) changes the stop.
        // Without a stop, a tap opens the stop search for this widget instead.
        views.setOnClickPendingIntent(
            android.R.id.background,
            if (stop == null) {
                choosePickerIntent(context, widgetId)
            } else {
                WidgetUpdater.broadcast(context, DeparturesWidget.ACTION_REFRESH, 100 + widgetId, widgetId)
            },
        )
        views.setTextViewText(R.id.title, stop?.name ?: context.getString(R.string.station_widget_name))
        val fetched = board?.fetchedAtEpochMs
        views.setTextViewText(
            R.id.subtitle,
            when {
                stop == null -> ""
                refreshing -> context.getString(R.string.updating_short)
                offline -> context.getString(R.string.offline_short)
                fetched != null -> context.getString(R.string.updated_at, Format.clock(fetched))
                else -> ""
            },
        )
    }

    /** Fills one departure row; [ids] are row, line, dest, time, delay, count, min, line_bg, platform. */
    private fun bindStationRow(
        context: Context,
        views: RemoteViews,
        ids: IntArray,
        d: Departure?,
        now: Long,
        minutes: Boolean,
        stopName: String,
        showPlatform: Boolean,
    ) {
        WidgetUpdater.bindRow(context, views, d, now, ids[0], ids[3], ids[4], ids[5], ids[6], minutes, stopName)
        if (d == null) return
        views.setTextViewText(ids[1], d.line)
        WidgetUpdater.tintBadge(context, views, ids[7], ids[1], LineColors.of(d.operator, d.line))
        views.setTextViewText(ids[2], Format.destination(d.destination, stopName))
        val platform = d.platform?.trim().orEmpty()
        views.setTextViewText(ids[8], platform)
        views.setContentDescription(ids[8], context.getString(R.string.platform, platform))
        views.setViewVisibility(
            ids[8],
            when {
                !showPlatform -> View.GONE
                platform.isEmpty() -> View.INVISIBLE // keep the column aligned
                else -> View.VISIBLE
            },
        )
    }

    private val LIST_ROW_IDS = intArrayOf(
        R.id.row, R.id.row_line, R.id.row_dest, R.id.row_time, R.id.row_delay,
        R.id.row_count, R.id.row_min, R.id.row_line_bg, R.id.row_platform,
    )

    /** One departure as a list item of the scrollable board. */
    internal fun listRow(context: Context, d: Departure, stopName: String, now: Long, minutes: Boolean, showPlatform: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.station_row)
        bindStationRow(context, views, LIST_ROW_IDS, d, now, minutes, stopName, showPlatform)
        // A tap on a row refreshes too (fills in the list's click template).
        views.setOnClickFillInIntent(R.id.row, Intent())
        return views
    }

    /** Android 12+: the whole board as a scrollable list. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    internal fun buildList(
        context: Context,
        widgetId: Int,
        stop: Stop?,
        board: Board?,
        offline: Boolean,
        refreshing: Boolean,
        now: Long,
        minutes: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_station_list)
        bindHeader(context, views, widgetId, stop, board, offline, refreshing)
        val upcoming = listDepartures(stop, board, now)
        val showPlatform = upcoming.any { !it.platform.isNullOrBlank() }
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        if (stop != null) {
            for (d in upcoming) items.addItem(stableId(d), listRow(context, d, stop.name, now, minutes, showPlatform))
        }
        views.setRemoteAdapter(R.id.list, items.build())
        views.setPendingIntentTemplate(
            R.id.list,
            PendingIntent.getBroadcast(
                context,
                200 + widgetId,
                Intent(context, DeparturesWidget::class.java)
                    .setAction(DeparturesWidget.ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                // Mutable: list items fill in this template (required for collection clicks).
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val empty = if (stop == null) {
            context.getString(R.string.station_setup_needed)
        } else {
            Texts.empty(context, upcoming.isNotEmpty(), board != null, offline)
        }
        views.setViewVisibility(R.id.list, if (empty == null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.empty, if (empty == null) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.empty, empty)
        return views
    }

    /** The departures the scrollable board lists: upcoming ones, at most [LIST_MAX_ROWS]. */
    internal fun listDepartures(stop: Stop?, board: Board?, now: Long): List<Departure> =
        if (stop == null) emptyList() else board?.upcoming(now).orEmpty().take(LIST_MAX_ROWS)

    /** Same departure → same id across redraws, so the list keeps its scroll position. */
    private fun stableId(d: Departure): Long =
        (d.scheduledEpochMs / 60_000) * 1_000_003L + (d.line + "|" + d.destination).hashCode()

    /** Android 8–11: fixed rows, as many as the widget's height allows. */
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
        bindHeader(context, views, widgetId, stop, board, offline, refreshing)
        if (stop == null) {
            ROWS.forEach { views.setViewVisibility(it[0], View.GONE) }
            views.setViewVisibility(R.id.empty, View.VISIBLE)
            views.setTextViewText(R.id.empty, context.getString(R.string.station_setup_needed))
            return views
        }
        val upcoming = board?.upcoming(now).orEmpty()
        // The platform column appears only when a shown departure has a platform.
        val showPlatform = upcoming.take(rows).any { !it.platform.isNullOrBlank() }
        ROWS.forEachIndexed { i, ids ->
            bindStationRow(context, views, ids, upcoming.getOrNull(i)?.takeIf { i < rows }, now, minutes, stop.name, showPlatform)
        }
        val empty = Texts.empty(context, upcoming.isNotEmpty(), board != null, offline)
        views.setViewVisibility(R.id.empty, if (empty == null) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.empty, empty)
        return views
    }
}
