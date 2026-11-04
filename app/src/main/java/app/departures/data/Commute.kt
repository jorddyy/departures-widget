package app.departures.data

import android.content.Context
import android.os.PowerManager
import app.departures.core.Board
import app.departures.core.Direction
import app.departures.core.DirectionResolver
import app.departures.core.Format
import app.departures.core.ManualOverride
import app.departures.core.Reason
import app.departures.core.RefreshPolicy
import app.departures.core.Resolution
import app.departures.core.Route
import app.departures.core.TimetableClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the commute widget and the detail screen display. */
data class Snapshot(
    /** Your Home and Work stops; null until set up, in which case nothing else is meaningful. */
    val route: Route?,
    val resolution: Resolution,
    val board: Board?,
    /** True when the most recent fetch failed, so the board may be out of date. */
    val offline: Boolean,
    val overrideUntil: Long?,
)

/**
 * The single entry point for "which way, and when is the next departure?". Used by the commute
 * widget, the background worker and the detail screen.
 */
class Commute(
    context: Context,
    private val store: Store = Store(context),
    private val location: LocationSource = LocationSource(context),
    private val client: TimetableClient = TimetableClient(),
    private val resolver: DirectionResolver = DirectionResolver(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val power = context.getSystemService(PowerManager::class.java)

    val route: Route? get() = store.route

    fun snapshot(now: Long = clock()): Snapshot {
        val route = store.route
            ?: return Snapshot(null, Resolution(Direction.TO_WORK, Reason.TIME_OF_DAY), null, false, null)
        val override = store.override?.takeIf { it.untilEpochMs > now }
        val resolution = resolver.resolve(route, now, Format.localHour(now), override, store.lastFix)
        val board = board(route, resolution.direction)
        return Snapshot(
            route,
            resolution,
            board,
            offline = store.lastFailureAt > (board?.fetchedAtEpochMs ?: 0),
            overrideUntil = override?.untilEpochMs,
        )
    }

    /** Cached departures for [direction], if they are for your current stops. */
    fun board(direction: Direction): Board? = store.route?.let { board(it, direction) }

    private fun board(route: Route, direction: Direction): Board? =
        store.board(direction)?.takeIf { it.fromStopId == route.from(direction).id }

    /** True if an automatic refresh would hit the network right now. */
    fun needsFetch(now: Long = clock()): Boolean {
        val s = snapshot(now)
        val route = s.route ?: return false
        val wanted = s.resolution.direction
        return RefreshPolicy.shouldFetch(now, s.board, wanted, route.from(wanted).id)
    }

    /**
     * Updates location (if permitted) and fetches departures when the cache needs it.
     *
     * @param userInitiated the user asked for this (tap, app open). Bypasses the "screen is on"
     *   and back-off checks that keep automatic refreshes cheap.
     * @param inForeground the app is visible, so foreground location permission suffices.
     */
    suspend fun refresh(userInitiated: Boolean, inForeground: Boolean = false): Snapshot = withContext(Dispatchers.IO) {
        val now = clock()
        val route = store.route ?: return@withContext snapshot(now)
        val automaticAllowed = userInitiated || power?.isInteractive != false
        if (automaticAllowed && location.canUse(inForeground)) {
            val fix = location.fix(now, maxAgeMs = LOCATION_MAX_AGE_MS)
            if (fix != null) store.lastFix = fix
        }

        val wanted = snapshot(now).resolution.direction
        val fromId = route.from(wanted).id
        val backingOff = now - store.lastFailureAt < RETRY_BACKOFF_MS
        val fetch = userInitiated ||
            (automaticAllowed && !backingOff && RefreshPolicy.shouldFetch(now, board(route, wanted), wanted, fromId))
        if (fetch) {
            try {
                store.saveBoard(client.departures(route, wanted, now))
                store.lastFailureAt = 0
            } catch (_: IOException) {
                store.lastFailureAt = now
            } catch (_: RuntimeException) {
                // Malformed response: treat like an outage rather than crashing the widget.
                store.lastFailureAt = now
            }
        }
        snapshot(clock())
    }

    /** Save new Home and Work stops. Any manual direction choice is cleared. */
    fun setRoute(route: Route) {
        store.route = route
        store.override = null
        store.lastFailureAt = 0
    }

    /** Flip the direction for a while. Flipping back to what auto would show clears the override. */
    fun toggleDirection(now: Long = clock()) {
        if (store.route == null) return
        val current = snapshot(now).resolution.direction
        store.override = null
        val auto = snapshot(now).resolution.direction
        val target = current.opposite
        store.override = if (target == auto) null else ManualOverride(target, now + OVERRIDE_MS)
    }

    /** Pin a direction for a while, or pass null to return to automatic. */
    fun setDirection(direction: Direction?, now: Long = clock()) {
        store.override = direction?.let { ManualOverride(it, now + OVERRIDE_MS) }
    }

    companion object {
        /** A manual choice lasts long enough for one trip, then automatic detection resumes. */
        const val OVERRIDE_MS = 2 * 60 * 60_000L
        /** Don't re-ask for location more often than this. */
        const val LOCATION_MAX_AGE_MS = 10 * 60_000L
        /** After a failed fetch, automatic refreshes wait this long before trying again. */
        const val RETRY_BACKOFF_MS = 60_000L
    }
}
