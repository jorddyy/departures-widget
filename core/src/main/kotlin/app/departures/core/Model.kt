package app.departures.core

import kotlinx.serialization.Serializable

/** A public transport stop, as returned by the timetable API's location search. */
@Serializable
data class Stop(
    /** Station id in the Swiss national timetable. */
    val id: String,
    /** Official name, e.g. "Bern, Bahnhof". */
    val name: String,
    val latitude: Double,
    val longitude: Double,
) {
    /** Short name for display: the part after the town, e.g. "Bahnhof". */
    val label: String get() = name.substringAfter(", ", name).ifBlank { name }
}

/** The user's two commute stops. Kept only on the phone. */
@Serializable
data class Route(val home: Stop, val work: Stop) {
    fun from(direction: Direction): Stop = if (direction == Direction.TO_WORK) home else work
    fun to(direction: Direction): Stop = if (direction == Direction.TO_WORK) work else home
}

/** Which way you are travelling. */
enum class Direction {
    TO_WORK,
    TO_HOME;

    val opposite: Direction get() = if (this == TO_WORK) TO_HOME else TO_WORK
}

/**
 * One departure.
 *
 * @property line the line as shown to passengers, e.g. "18" or "IR 15".
 * @property scheduledEpochMs timetable departure time.
 * @property expectedEpochMs best estimate of the real departure time (scheduled + live delay).
 * @property delayMinutes live delay in minutes, or null when there is no live data.
 * @property realtime true when the API supplied a live forecast for this departure.
 */
data class Departure(
    val line: String,
    val destination: String,
    val scheduledEpochMs: Long,
    val expectedEpochMs: Long,
    val delayMinutes: Int?,
    val realtime: Boolean,
    val platform: String? = null,
)

/**
 * Departures from [fromStopId] as fetched at [fetchedAtEpochMs]. For the commute widget,
 * [direction] is set and only departures reaching the other stop are kept; for a station board
 * it is null and every departure is kept.
 */
data class Board(
    val direction: Direction?,
    val departures: List<Departure>,
    val fetchedAtEpochMs: Long,
    val fromStopId: String = "",
) {
    /** Departures that have not left yet at [nowEpochMs], allowing a short grace period. */
    fun upcoming(nowEpochMs: Long, graceMs: Long = 30_000): List<Departure> =
        departures.filter { it.expectedEpochMs + graceMs > nowEpochMs }
}
