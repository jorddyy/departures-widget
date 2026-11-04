package app.departures.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Text helpers shared by the widget and the detail screen. */
object Format {
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE)
    private val apiDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE)

    /** The timetable API's `datetime` parameter, in Swiss local time. */
    fun apiDateTime(epochMs: Long): String = apiDateTime.format(Instant.ofEpochMilli(epochMs))

    fun clock(epochMs: Long): String = clock.format(Instant.ofEpochMilli(epochMs))

    /** Whole minutes until departure, rounded down, never negative. */
    fun minutesUntil(nowEpochMs: Long, departure: Departure): Long =
        ((departure.expectedEpochMs - nowEpochMs) / 60_000).coerceAtLeast(0)

    /** "now", "1 min", "12 min", "1 h 05". */
    fun countdown(nowEpochMs: Long, departure: Departure): String {
        val m = minutesUntil(nowEpochMs, departure)
        return when {
            m < 1 -> "now"
            m < 60 -> "$m min"
            else -> "${m / 60} h %02d".format(m % 60)
        }
    }

    /** "+3" for a late tram, "−1" for an early one, null when on time or unknown. */
    fun delay(departure: Departure): String? {
        val d = departure.delayMinutes ?: return null
        return when {
            d > 0 -> "+$d"
            d < 0 -> "−${-d}"
            else -> null
        }
    }

    /**
     * The destination to show at [stopName]: without the town when it's the same town as the
     * stop ("Genève, Bel-Air" at "Genève, Cornavin" → "Bel-Air"), in full otherwise, so regional
     * lines don't all read "Gare".
     */
    fun destination(destination: String, stopName: String?): String {
        val town = destination.substringBefore(", ", "")
        if (town.isEmpty() || stopName == null) return destination
        return if (stopName.substringBefore(", ", "") == town) destination.substringAfter(", ") else destination
    }

    fun localHour(epochMs: Long): Int = Instant.ofEpochMilli(epochMs).atZone(ZONE).hour
}
