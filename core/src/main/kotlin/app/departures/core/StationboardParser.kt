package app.departures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parses responses from the transport.opendata.ch `/v1/stationboard` endpoint and keeps only
 * departures that reach the destination stop without changing.
 */
object StationboardParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // The API uses "+0100" style offsets, which ISO_OFFSET_DATE_TIME does not accept.
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[XXX][XX]")

    /** Local transport, shown by line number only ("18" rather than "T 18"). */
    private val NUMBER_ONLY = setOf("T", "TRAM", "B", "BUS", "M", "NFB", "NFT", "NFO", "BAT", "FUN", "KB", "EXB")

    /** Departures in the board that reach [to] directly, soonest first. */
    fun parse(body: String, to: Stop): List<Departure> {
        val response = json.decodeFromString(StationboardResponse.serializer(), body)
        return response.stationboard
            .asSequence()
            .filter { it.goesTowards(to) }
            .mapNotNull { it.toDeparture() }
            .sortedBy { it.expectedEpochMs }
            .toList()
    }

    /** Every departure in the board, soonest first (for the station board widget). */
    fun parseAll(body: String): List<Departure> =
        json.decodeFromString(StationboardResponse.serializer(), body).stationboard
            .mapNotNull { it.toDeparture() }
            .sortedBy { it.expectedEpochMs }

    /**
     * True if this journey reaches [target] after the current stop. Journeys that turn back or
     * end before it (short runs late at night, for example) are dropped.
     */
    private fun Journey.goesTowards(target: Stop): Boolean {
        if (to.equals(target.name, ignoreCase = true)) return true
        // passList[0] is the stop we are departing from; look only at later stops.
        return passList.drop(1).any { it.station?.matches(target) == true }
    }

    private fun Station.matches(stop: Stop): Boolean =
        (id != null && id == stop.id) || name.equals(stop.name, ignoreCase = true)

    /** "7" for a tram or bus, "S1" for an S-Bahn, "IR 15" for a train. */
    internal fun Journey.lineLabel(): String {
        val cat = category?.trim().orEmpty()
        val num = number?.trim().orEmpty()
        return when {
            num.isEmpty() -> name?.trim().orEmpty().ifEmpty { cat }
            cat.isEmpty() || cat.uppercase() in NUMBER_ONLY -> num
            num.startsWith(cat) -> num
            cat == "S" || cat == "SN" -> "$cat$num"
            else -> "$cat $num"
        }
    }

    private fun Journey.toDeparture(): Departure? {
        val s = stop ?: return null
        val scheduledMs = s.departureTimestamp?.times(1000) ?: parseTime(s.departure) ?: return null
        val prognosisMs = parseTime(s.prognosis?.departure)
        val delay = s.delay ?: prognosisMs?.let { ((it - scheduledMs) / 60_000).toInt() }
        val expected = prognosisMs ?: (scheduledMs + (s.delay ?: 0) * 60_000L)
        return Departure(
            line = lineLabel(),
            destination = to.orEmpty(),
            scheduledEpochMs = scheduledMs,
            expectedEpochMs = expected,
            delayMinutes = delay,
            realtime = prognosisMs != null || s.delay != null,
            platform = s.prognosis?.platform ?: s.platform,
        )
    }

    internal fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value, timeFormat).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    @Serializable
    internal data class StationboardResponse(val stationboard: List<Journey> = emptyList())

    @Serializable
    internal data class Journey(
        val stop: StopTime? = null,
        val name: String? = null,
        val category: String? = null,
        val number: String? = null,
        val to: String? = null,
        val passList: List<PassStop> = emptyList(),
    )

    @Serializable
    internal data class StopTime(
        val departure: String? = null,
        val departureTimestamp: Long? = null,
        val delay: Int? = null,
        val platform: String? = null,
        val prognosis: Prognosis? = null,
    )

    @Serializable
    internal data class Prognosis(val departure: String? = null, val platform: String? = null)

    @Serializable
    internal data class PassStop(val station: Station? = null)

    @Serializable
    internal data class Station(val id: String? = null, val name: String? = null)
}
