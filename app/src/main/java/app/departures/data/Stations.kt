package app.departures.data

import android.content.Context
import android.os.PowerManager
import app.departures.core.Board
import app.departures.core.RefreshPolicy
import app.departures.core.Stop
import app.departures.core.TimetableClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Departures for the station-board widgets: one stop per widget, every line and direction. */
class Stations(
    context: Context,
    private val store: Store = Store(context),
    private val client: TimetableClient = TimetableClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val power = context.getSystemService(PowerManager::class.java)

    fun stop(widgetId: Int): Stop? = store.stationStop(widgetId)

    fun board(stop: Stop): Board? = store.stationBoard(stop.id)

    fun isOffline(stop: Stop): Boolean = store.stationFailureAt(stop.id) > (board(stop)?.fetchedAtEpochMs ?: 0)

    fun needsFetch(stops: Collection<Stop>, now: Long = clock()): Boolean =
        stops.any { RefreshPolicy.shouldFetch(now, board(it), null, it.id, MIN_UPCOMING) }

    /**
     * Fetches boards for [stops] that need it. Automatic refreshes follow the same rules as the
     * commute widget: only while the screen is on, never more often than needed.
     */
    suspend fun refresh(stops: Collection<Stop>, userInitiated: Boolean) = withContext(Dispatchers.IO) {
        val now = clock()
        val automaticAllowed = userInitiated || power?.isInteractive != false
        for (stop in stops.distinctBy { it.id }) {
            val backingOff = now - store.stationFailureAt(stop.id) < Commute.RETRY_BACKOFF_MS
            val fetch = userInitiated ||
                (automaticAllowed && !backingOff && RefreshPolicy.shouldFetch(now, board(stop), null, stop.id, MIN_UPCOMING))
            if (!fetch) continue
            try {
                store.saveStationBoard(client.stationBoard(stop, now))
                store.setStationFailureAt(stop.id, 0)
            } catch (_: IOException) {
                store.setStationFailureAt(stop.id, now)
            } catch (_: RuntimeException) {
                store.setStationFailureAt(stop.id, now)
            }
        }
    }

    fun setStop(widgetId: Int, stop: Stop?) = store.setStationStop(widgetId, stop)

    private companion object {
        /** The tallest station board shows seven rows. */
        const val MIN_UPCOMING = 7
    }
}
