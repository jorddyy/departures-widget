package app.departures.ui

import android.content.Context
import app.departures.core.Departure
import app.departures.core.Format
import app.departures.core.Reason
import app.departures.R
import app.departures.data.Snapshot

/** Shared wording for the widget and the detail screen. */
object Texts {

    fun reason(context: Context, s: Snapshot): String = when (s.resolution.reason) {
        Reason.NEAR_STOP -> context.getString(R.string.reason_near, s.route?.from(s.resolution.direction)?.label.orEmpty())
        Reason.MANUAL -> context.getString(R.string.reason_manual, Format.clock(s.overrideUntil ?: 0))
        Reason.AWAY -> context.getString(R.string.reason_away)
        Reason.TIME_OF_DAY -> context.getString(R.string.reason_time)
    }

    /** One- or two-word status for the compact widget: why this direction, or that data is stale. */
    fun shortStatus(context: Context, s: Snapshot, refreshing: Boolean): String = when {
        refreshing -> context.getString(R.string.updating_short)
        s.offline -> context.getString(R.string.offline_short)
        else -> when (s.resolution.reason) {
            Reason.NEAR_STOP -> context.getString(R.string.status_near)
            Reason.MANUAL -> context.getString(R.string.status_manual)
            Reason.AWAY -> context.getString(R.string.status_away)
            Reason.TIME_OF_DAY -> context.getString(R.string.status_time)
        }
    }

    fun subtitle(context: Context, s: Snapshot): String =
        context.getString(R.string.subtitle, s.route?.from(s.resolution.direction)?.label.orEmpty(), reason(context, s))

    fun footer(context: Context, s: Snapshot, refreshing: Boolean): String {
        val fetched = s.board?.fetchedAtEpochMs
        return when {
            refreshing -> context.getString(R.string.updating)
            fetched == null -> ""
            s.offline -> context.getString(R.string.offline_since, Format.clock(fetched))
            else -> context.getString(R.string.updated_at, Format.clock(fetched))
        }
    }

    /** Spoken description of a departure for screen readers. */
    fun describe(context: Context, d: Departure, now: Long): String {
        val delay = d.delayMinutes
        val delayText = when {
            !d.realtime || delay == null -> context.getString(R.string.a11y_scheduled)
            delay > 0 -> context.resources.getQuantityString(R.plurals.a11y_late, delay, delay)
            delay < 0 -> context.resources.getQuantityString(R.plurals.a11y_early, -delay, -delay)
            else -> context.getString(R.string.a11y_on_time)
        }
        return context.getString(
            R.string.a11y_departure,
            Format.clock(d.scheduledEpochMs),
            delayText,
            Format.countdown(now, d),
        )
    }
}
