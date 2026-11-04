package app.departures.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.departures.core.Board
import app.departures.core.BoardCodec
import app.departures.core.Direction
import app.departures.core.LocationFix
import app.departures.core.ManualOverride
import app.departures.core.Route
import app.departures.core.Stop
import app.departures.core.StopCodec

/**
 * Everything the app remembers, in one private SharedPreferences file: your Home and Work stops,
 * the stop of each station-board widget, cached departures, the manual override and the last
 * location fix. It stays on this device and is excluded from backups.
 */
class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("departures", Context.MODE_PRIVATE)

    // --- Commute widget -------------------------------------------------------------------

    /** Your Home and Work stops, or null until you set them up in the app. */
    var route: Route?
        get() = StopCodec.decodeRoute(prefs.getString(KEY_ROUTE, null))
        set(value) = prefs.edit {
            if (value == null) remove(KEY_ROUTE) else putString(KEY_ROUTE, StopCodec.encodeRoute(value))
        }

    fun board(direction: Direction): Board? =
        BoardCodec.decode(prefs.getString(boardKey(direction), null))?.takeIf { it.direction == direction }

    fun saveBoard(board: Board) {
        val direction = board.direction ?: return
        prefs.edit { putString(boardKey(direction), BoardCodec.encode(board)) }
    }

    var override: ManualOverride?
        get() {
            val dir = prefs.getString(KEY_OVERRIDE_DIR, null) ?: return null
            val until = prefs.getLong(KEY_OVERRIDE_UNTIL, 0)
            return runCatching { ManualOverride(Direction.valueOf(dir), until) }.getOrNull()
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_OVERRIDE_DIR); remove(KEY_OVERRIDE_UNTIL)
            } else {
                putString(KEY_OVERRIDE_DIR, value.direction.name); putLong(KEY_OVERRIDE_UNTIL, value.untilEpochMs)
            }
        }

    /** Last coarse location fix. Kept only on this device and only as long as it is useful. */
    var lastFix: LocationFix?
        get() {
            if (!prefs.contains(KEY_FIX_TIME)) return null
            return LocationFix(
                Double.fromBits(prefs.getLong(KEY_FIX_LAT, 0)),
                Double.fromBits(prefs.getLong(KEY_FIX_LON, 0)),
                prefs.getLong(KEY_FIX_TIME, 0),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_FIX_LAT); remove(KEY_FIX_LON); remove(KEY_FIX_TIME)
            } else {
                putLong(KEY_FIX_LAT, value.latitude.toRawBits())
                putLong(KEY_FIX_LON, value.longitude.toRawBits())
                putLong(KEY_FIX_TIME, value.timeEpochMs)
            }
        }

    /** Time of the last failed commute fetch, or 0 if the last fetch succeeded. */
    var lastFailureAt: Long
        get() = prefs.getLong(KEY_FAILURE, 0)
        set(value) = prefs.edit { putLong(KEY_FAILURE, value) }

    // --- Station-board widgets --------------------------------------------------------------

    fun stationStop(widgetId: Int): Stop? = StopCodec.decodeStop(prefs.getString("station_stop_$widgetId", null))

    fun setStationStop(widgetId: Int, stop: Stop?) = prefs.edit {
        if (stop == null) remove("station_stop_$widgetId") else putString("station_stop_$widgetId", StopCodec.encodeStop(stop))
    }

    fun stationBoard(stopId: String): Board? =
        BoardCodec.decode(prefs.getString("station_board_$stopId", null))?.takeIf { it.fromStopId == stopId }

    fun saveStationBoard(board: Board) = prefs.edit { putString("station_board_${board.fromStopId}", BoardCodec.encode(board)) }

    fun stationFailureAt(stopId: String): Long = prefs.getLong("station_failure_$stopId", 0)

    fun setStationFailureAt(stopId: String, at: Long) = prefs.edit { putLong("station_failure_$stopId", at) }

    private fun boardKey(direction: Direction) = "board_${direction.name}"

    private companion object {
        const val KEY_ROUTE = "route"
        const val KEY_OVERRIDE_DIR = "override_dir"
        const val KEY_OVERRIDE_UNTIL = "override_until"
        const val KEY_FIX_LAT = "fix_lat"
        const val KEY_FIX_LON = "fix_lon"
        const val KEY_FIX_TIME = "fix_time"
        const val KEY_FAILURE = "last_failure"
    }
}
