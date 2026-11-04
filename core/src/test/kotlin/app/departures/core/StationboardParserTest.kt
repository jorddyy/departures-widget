package app.departures.core

import app.departures.core.TestData.MARKET
import app.departures.core.TestData.STATION
import app.departures.core.TestData.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationboardParserTest {

    private fun at(iso: String): Long = StationboardParser.parseTime(iso)!!

    @Test
    fun `keeps only departures that reach the destination`() {
        val deps = StationboardParser.parse(fixture("market.json"), STATION)
        // Two trams passing Station, plus a train whose terminus is Station.
        assertEquals(listOf("7", "7", "IR 15"), deps.map { it.line })
    }

    @Test
    fun `drops short runs that end before the destination`() {
        val deps = StationboardParser.parse(fixture("station.json"), MARKET)
        assertEquals(listOf("Townsville, Harbour", "Townsville, Harbour"), deps.map { it.destination })
        assertEquals(listOf("7", "S1"), deps.map { it.line })
    }

    @Test
    fun `nothing matches a stop that is never reached`() {
        val nowhere = Stop("8599999", "Elsewhere, Square", 46.0, 6.0)
        assertTrue(StationboardParser.parse(fixture("market.json"), nowhere).isEmpty())
    }

    @Test
    fun `station board keeps every departure`() {
        assertEquals(5, StationboardParser.parseAll(fixture("market.json")).size)
    }

    @Test
    fun `uses live forecast and delay`() {
        val late = StationboardParser.parse(fixture("market.json"), STATION)[1]
        assertEquals(at("2026-11-03T08:31:00+0100"), late.scheduledEpochMs)
        assertEquals(at("2026-11-03T08:32:00+0100"), late.expectedEpochMs)
        assertEquals(1, late.delayMinutes)
        assertTrue(late.realtime)
    }

    @Test
    fun `on time with live data is realtime with zero delay`() {
        val first = StationboardParser.parse(fixture("market.json"), STATION)[0]
        assertEquals(0, first.delayMinutes)
        assertTrue(first.realtime)
        assertEquals(first.scheduledEpochMs, first.expectedEpochMs)
    }

    @Test
    fun `scheduled-only departure has no delay and is not realtime`() {
        val last = StationboardParser.parse(fixture("market.json"), STATION).last()
        assertNull(last.delayMinutes)
        assertFalse(last.realtime)
        assertEquals(last.scheduledEpochMs, last.expectedEpochMs)
    }

    @Test
    fun `results are sorted by expected time`() {
        val json = """
            {"stationboard":[
              {"number":"7","category":"T","to":"Townsville, Station","stop":{"departure":"2026-11-03T08:10:00+0100","delay":6,
                "prognosis":{"departure":"2026-11-03T08:16:00+0100"}}},
              {"number":"7","category":"T","to":"Townsville, Station","stop":{"departure":"2026-11-03T08:14:00+0100","delay":0,
                "prognosis":{"departure":"2026-11-03T08:14:00+0100"}}}
            ]}
        """.trimIndent()
        assertEquals(listOf(0, 6), StationboardParser.parse(json, STATION).map { it.delayMinutes })
    }

    @Test
    fun `delay without prognosis shifts expected time`() {
        val json = """{"stationboard":[{"number":"7","to":"Townsville, Station",
            "stop":{"departure":"2026-11-03T08:10:00+0100","delay":4,"prognosis":null}}]}"""
        val d = StationboardParser.parse(json, STATION).single()
        assertEquals(at("2026-11-03T08:14:00+0100"), d.expectedEpochMs)
        assertTrue(d.realtime)
    }

    @Test
    fun `tolerates empty, missing and unknown fields`() {
        assertTrue(StationboardParser.parse("{}", STATION).isEmpty())
        assertTrue(StationboardParser.parse("""{"stationboard":[],"extra":1}""", STATION).isEmpty())
        val noStop = """{"stationboard":[{"number":"7","to":"Townsville, Station"}]}"""
        assertTrue(StationboardParser.parse(noStop, STATION).isEmpty())
    }

    @Test
    fun `line labels follow passenger conventions`() {
        fun label(cat: String?, num: String?, name: String? = null) =
            StationboardParser.run { StationboardParser.Journey(category = cat, number = num, name = name).lineLabel() }
        assertEquals("12", label("T", "12"))
        assertEquals("10", label("B", "10"))
        assertEquals("S1", label("S", "1"))
        assertEquals("IR 15", label("IR", "15"))
        assertEquals("RE33", label("RE", "RE33"))
        assertEquals("T 12", label(null, null, "T 12"))
    }

    @Test
    fun `parses both offset styles`() {
        assertEquals(at("2026-11-03T08:10:00+0100"), at("2026-11-03T08:10:00+01:00"))
        assertNull(StationboardParser.parseTime("not a time"))
        assertNull(StationboardParser.parseTime(null))
    }
}
