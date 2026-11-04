package app.departures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to transport.opendata.ch, a free, key-less API backed by the Swiss national timetable
 * with real-time data. Departure requests carry only a stop id; stop searches carry only the
 * text you type. No location, account or device identifier ever leaves the phone.
 */
class TimetableClient(
    private val baseUrl: String = "https://transport.opendata.ch/v1",
    private val http: (String) -> String = ::httpGet,
) {
    /** Departures from the start of [direction] that reach its end directly. */
    @Throws(IOException::class)
    fun departures(route: Route, direction: Direction, nowEpochMs: Long): Board {
        val from = route.from(direction)
        val body = http(stationboardUrl(from))
        return Board(direction, StationboardParser.parse(body, route.to(direction)), nowEpochMs, from.id)
    }

    /** All upcoming departures at [stop], for the station board widget. */
    @Throws(IOException::class)
    fun stationBoard(stop: Stop, nowEpochMs: Long, limit: Int = BOARD_LIMIT): Board =
        Board(null, StationboardParser.parseAll(http(stationboardUrl(stop, limit))), nowEpochMs, stop.id)

    /** Stops matching [query], best match first. */
    @Throws(IOException::class)
    fun searchStops(query: String): List<Stop> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return parseLocations(http("$baseUrl/locations?type=station&query=${encode(q)}"))
    }

    fun stationboardUrl(from: Stop, limit: Int = LIMIT): String =
        "$baseUrl/stationboard?id=${encode(from.id)}&limit=$limit"

    companion object {
        /**
         * Rows to request. The limit counts every departure at the stop, in all directions and on
         * all lines, so it must be generous to keep a few that go your way.
         */
        const val LIMIT = 40

        /** The station board shows every departure, so fewer rows cover enough time. */
        const val BOARD_LIMIT = 15
        private const val TIMEOUT_MS = 10_000

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Parses `/v1/locations`, keeping only real stations (those with an id and coordinates). */
        fun parseLocations(body: String): List<Stop> =
            json.decodeFromString(LocationsResponse.serializer(), body).stations.mapNotNull { s ->
                val id = s.id ?: return@mapNotNull null
                val name = s.name ?: return@mapNotNull null
                val lat = s.coordinate?.x ?: return@mapNotNull null
                val lon = s.coordinate.y ?: return@mapNotNull null
                Stop(id, name, lat, lon)
            }

        fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "DeparturesWidget/1.0")
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }

    @Serializable
    private data class LocationsResponse(val stations: List<Location> = emptyList())

    // The API puts latitude in x and longitude in y.
    @Serializable
    private data class Location(val id: String? = null, val name: String? = null, val coordinate: Coordinate? = null)

    @Serializable
    private data class Coordinate(val x: Double? = null, val y: Double? = null)
}
