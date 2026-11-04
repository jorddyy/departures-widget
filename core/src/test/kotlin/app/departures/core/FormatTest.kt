package app.departures.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    private val now = StationboardParser.parseTime("2026-11-03T08:00:00+0100")!!

    private fun dep(secondsFromNow: Long, delay: Int? = null) =
        Departure("7", "Townsville, Station", now, now + secondsFromNow * 1000, delay, delay != null)

    @Test
    fun countdown() {
        assertEquals("now", Format.countdown(now, dep(30)))
        assertEquals("now", Format.countdown(now, dep(-10)))
        assertEquals("1 min", Format.countdown(now, dep(60)))
        assertEquals("4 min", Format.countdown(now, dep(299)))
        assertEquals("1 h 05", Format.countdown(now, dep(65 * 60)))
    }

    @Test
    fun `clock uses Swiss time`() {
        assertEquals("08:00", Format.clock(now))
        assertEquals(8, Format.localHour(now))
        // Summer time
        assertEquals("08:00", Format.clock(StationboardParser.parseTime("2026-07-01T08:00:00+0200")!!))
    }

    @Test
    fun delay() {
        assertEquals("+3", Format.delay(dep(0, 3)))
        assertEquals("−1", Format.delay(dep(0, -1)))
        assertNull(Format.delay(dep(0, 0)))
        assertNull(Format.delay(dep(0, null)))
    }

    @Test
    fun `destination drops the town only when it is the stop's own town`() {
        assertEquals("Bel-Air", Format.destination("Genève, Bel-Air", "Genève, Cornavin"))
        assertEquals("Meyrin, Gravière", Format.destination("Meyrin, Gravière", "Genève, Cornavin"))
        assertEquals("Townsville", Format.destination("Townsville", "Townsville, Market"))
        assertEquals("Bern, Bahnhof", Format.destination("Bern, Bahnhof", null))
    }

    @Test
    fun `API date time is Swiss local time`() {
        assertEquals("2026-11-03 08:00", Format.apiDateTime(now))
    }
}
