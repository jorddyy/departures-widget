package app.departures.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import app.departures.R
import app.departures.core.Departure
import app.departures.core.Format
import app.departures.core.RefreshPolicy
import app.departures.data.Commute
import app.departures.data.Snapshot
import app.departures.ui.MainActivity
import app.departures.ui.Texts

/**
 * Draws all widgets from cached data. Cheap enough to call often: no network, no location.
 *
 * Time remaining is shown in whole minutes ("5 min"), redrawn by a non-waking exact alarm at the
 * moment the minute changes. That needs the "Alarms & reminders" permission (off by default on
 * Android 14+). Without it the widgets fall back to a count-down [android.widget.Chronometer]
 * that the launcher ticks every second by itself, so the time shown is never stale.
 */
object WidgetUpdater {

    /** Redraws the commute and station-board widgets and schedules the next redraw. */
    fun renderAll(context: Context, refreshing: Boolean = false, now: Long = System.currentTimeMillis()) {
        val minutes = canUseExactAlarms(context)
        val next = listOfNotNull(
            renderCommute(context, refreshing, now, minutes),
            StationWidgetUpdater.render(context, refreshing, now, minutes),
        ).minOrNull()
        if (next == null) cancelTick(context) else scheduleTick(context, next, now, exact = minutes)
    }

    fun hasCommuteWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, DeparturesWidget::class.java)).isNotEmpty()

    /** True if the widgets may use exact (but still non-waking) alarms for the minute display. */
    fun canUseExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** Below this height the compact two-line layout is used (one row on a One UI home screen). */
    private const val FULL_MIN_H = 140f
    private const val FULL_MIN_W = 180f
    private const val COMPACT_MIN_H = 40f
    private const val COMPACT_MIN_W = 180f

    /** Renders the commute widgets; returns when they next need redrawing, or null if there are none. */
    private fun renderCommute(context: Context, refreshing: Boolean, now: Long, minutes: Boolean): Long? {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, DeparturesWidget::class.java))
        if (ids.isEmpty()) return null
        val snapshot = Commute(context).snapshot(now)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher picks the largest layout that fits each widget's current size.
            val responsive = RemoteViews(
                mapOf(
                    SizeF(COMPACT_MIN_W, COMPACT_MIN_H) to build(context, snapshot, refreshing, now, compact = true, minutes = minutes),
                    SizeF(FULL_MIN_W, FULL_MIN_H) to build(context, snapshot, refreshing, now, compact = false, minutes = minutes),
                ),
            )
            manager.updateAppWidget(ids, responsive)
        } else {
            for (id in ids) {
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                manager.updateAppWidget(id, build(context, snapshot, refreshing, now, compact = height < FULL_MIN_H, minutes = minutes))
            }
        }
        val shown = snapshot.board?.upcoming(now).orEmpty().take(2)
        return nextTick(now, RefreshPolicy.nextRenderAt(now, snapshot.board), shown, minutes)
    }

    /** The redraw time for a widget showing [shown]: at the next minute change when [minutes]. */
    internal fun nextTick(now: Long, nextRender: Long, shown: List<Departure>, minutes: Boolean): Long {
        if (!minutes) return nextRender
        return minOf(nextRender, RefreshPolicy.nextMinuteChangeAt(now, shown) ?: nextRender)
    }

    internal fun build(
        context: Context,
        s: Snapshot,
        refreshing: Boolean,
        now: Long,
        compact: Boolean,
        minutes: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, if (compact) R.layout.widget_compact else R.layout.widget_full)
        views.setOnClickPendingIntent(android.R.id.background, openApp(context))
        val route = s.route
        if (route == null) {
            // Not set up yet: say so, and let a tap open the app.
            views.setTextViewText(R.id.title, context.getString(R.string.app_name))
            views.setTextViewText(R.id.subtitle, "")
            views.setViewVisibility(R.id.badge, View.GONE)
            views.setViewVisibility(R.id.swap, View.GONE)
            views.setViewVisibility(R.id.row1, View.GONE)
            views.setViewVisibility(R.id.row2, View.GONE)
            views.setViewVisibility(R.id.empty, View.VISIBLE)
            views.setTextViewText(R.id.empty, context.getString(R.string.setup_needed))
            if (!compact) {
                views.setTextViewText(R.id.footer, "")
                views.setViewVisibility(R.id.refresh, View.GONE)
            }
            return views
        }

        val direction = s.resolution.direction
        val from = route.from(direction)
        val to = route.to(direction)
        if (compact) {
            views.setTextViewText(R.id.title, context.getString(R.string.route, from.label, to.label))
            views.setTextViewText(R.id.subtitle, Texts.shortStatus(context, s, refreshing))
        } else {
            views.setTextViewText(R.id.title, context.getString(R.string.towards, to.label))
            views.setTextViewText(R.id.subtitle, Texts.subtitle(context, s))
            views.setTextViewText(R.id.footer, Texts.footer(context, s, refreshing))
            views.setOnClickPendingIntent(R.id.refresh, broadcast(context, DeparturesWidget.ACTION_REFRESH, 2))
        }
        views.setContentDescription(R.id.swap, context.getString(R.string.show_direction, to.label, from.label))

        val upcoming = s.board?.upcoming(now).orEmpty().take(2)
        val lines = upcoming.map { it.line }.filter { it.isNotBlank() }.distinct().joinToString("/")
        views.setViewVisibility(R.id.badge, if (lines.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.badge, lines)

        bindRow(context, views, upcoming.getOrNull(0), now, R.id.row1, R.id.time1, R.id.delay1, R.id.count1, R.id.min1, minutes)
        bindRow(context, views, upcoming.getOrNull(1), now, R.id.row2, R.id.time2, R.id.delay2, R.id.count2, R.id.min2, minutes)

        val empty = when {
            upcoming.isNotEmpty() -> null
            s.board == null && s.offline -> context.getString(R.string.no_connection)
            s.board == null -> context.getString(R.string.loading)
            else -> context.getString(R.string.no_departures)
        }
        views.setViewVisibility(R.id.empty, if (empty == null) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.empty, empty)

        views.setOnClickPendingIntent(R.id.swap, broadcast(context, DeparturesWidget.ACTION_SWAP, 1))
        return views
    }

    /** Fills one departure into the given views; hides [row] when there is none. */
    internal fun bindRow(
        context: Context,
        views: RemoteViews,
        departure: Departure?,
        now: Long,
        row: Int,
        time: Int,
        delay: Int,
        count: Int,
        min: Int,
        minutes: Boolean,
    ) {
        if (departure == null) {
            views.setViewVisibility(row, View.GONE)
            return
        }
        views.setViewVisibility(row, View.VISIBLE)
        views.setTextViewText(time, Format.clock(departure.scheduledEpochMs))
        // Kept visible even when empty: in the commute layouts it is the flexible spacer.
        views.setTextViewText(delay, Format.delay(departure).orEmpty())
        val base = SystemClock.elapsedRealtime() + (departure.expectedEpochMs - now)
        views.setChronometer(count, base, context.getString(R.string.countdown_format), !minutes)
        views.setChronometerCountDown(count, true)
        views.setViewVisibility(count, if (minutes) View.GONE else View.VISIBLE)
        views.setViewVisibility(min, if (minutes) View.VISIBLE else View.GONE)
        views.setTextViewText(min, Format.countdown(now, departure))
        views.setContentDescription(row, Texts.describe(context, departure, now))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun broadcast(context: Context, action: String, code: Int, widgetId: Int? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, DeparturesWidget::class.java).setAction(action).apply {
                if (widgetId != null) putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Schedule the next redraw. The alarm is always non-waking: while the phone sleeps it simply
     * waits, and fires as soon as the screen comes back on. With [exact] it fires on time (for
     * the minute display, at most about once a minute while the screen is on); otherwise the
     * system may batch it a few minutes late.
     */
    private fun scheduleTick(context: Context, atEpochMs: Long, now: Long, exact: Boolean) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = SystemClock.elapsedRealtime() + (atEpochMs - now).coerceAtLeast(0) + 250
        val intent = broadcast(context, DeparturesWidget.ACTION_TICK, 3)
        if (exact) {
            try {
                alarm.setExact(AlarmManager.ELAPSED_REALTIME, trigger, intent)
                return
            } catch (_: SecurityException) {
                // Permission was revoked between the check and now: fall back to inexact.
            }
        }
        alarm.set(AlarmManager.ELAPSED_REALTIME, trigger, intent)
    }

    fun cancelTick(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(broadcast(context, DeparturesWidget.ACTION_TICK, 3))
    }
}
