package app.departures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Serialises a [Board] for the on-device cache. Corrupt or outdated entries decode to null. */
object BoardCodec {
    private const val VERSION = 2
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(board: Board): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            v = VERSION,
            direction = board.direction?.name,
            fetchedAt = board.fetchedAtEpochMs,
            from = board.fromStopId,
            departures = board.departures.map {
                StoredDeparture(it.line, it.destination, it.scheduledEpochMs, it.expectedEpochMs, it.delayMinutes, it.realtime, it.platform, it.operator)
            },
        ),
    )

    fun decode(text: String?): Board? {
        if (text.isNullOrEmpty()) return null
        return try {
            val s = json.decodeFromString(Stored.serializer(), text)
            if (s.v != VERSION) return null
            Board(
                direction = s.direction?.let(Direction::valueOf),
                departures = s.departures.map {
                    Departure(it.line, it.destination, it.scheduled, it.expected, it.delay, it.realtime, it.platform, it.operator)
                },
                fetchedAtEpochMs = s.fetchedAt,
                fromStopId = s.from,
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Serializable
    private data class Stored(
        val v: Int,
        val direction: String? = null,
        val fetchedAt: Long,
        val from: String = "",
        val departures: List<StoredDeparture>,
    )

    @Serializable
    private data class StoredDeparture(
        val line: String,
        val destination: String,
        val scheduled: Long,
        val expected: Long,
        val delay: Int? = null,
        val realtime: Boolean = false,
        val platform: String? = null,
        val operator: String? = null,
    )
}

/** Serialises the user's stops for on-device storage. Bad input decodes to null. */
object StopCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRoute(route: Route): String = json.encodeToString(Route.serializer(), route)
    fun decodeRoute(text: String?): Route? = decode(text) { json.decodeFromString(Route.serializer(), it) }

    fun encodeStop(stop: Stop): String = json.encodeToString(Stop.serializer(), stop)
    fun decodeStop(text: String?): Stop? = decode(text) { json.decodeFromString(Stop.serializer(), it) }

    private fun <T> decode(text: String?, block: (String) -> T): T? {
        if (text.isNullOrEmpty()) return null
        return try {
            block(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
