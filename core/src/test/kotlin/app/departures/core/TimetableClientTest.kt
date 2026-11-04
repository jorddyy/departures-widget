package app.departures.core

import app.departures.core.TestData.MARKET
import app.departures.core.TestData.ROUTE
import app.departures.core.TestData.STATION
import app.departures.core.TestData.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TimetableClientTest {

    @Test
    fun `stationboard URL carries only the stop id`() {
        val client = TimetableClient(baseUrl = "https://x")
        assertEquals("https://x/stationboard?id=8500001&limit=40", client.stationboardUrl(MARKET))
        assertEquals("https://x/stationboard?id=8500002&limit=15", client.stationboardUrl(STATION, 15))
    }

    @Test
    fun `commute board starts at the right stop and is stamped`() {
        val requested = mutableListOf<String>()
        val client = TimetableClient(baseUrl = "https://x", http = { requested += it; fixture("market.json") })
        val board = client.departures(ROUTE, Direction.TO_WORK, 42L)
        assertEquals("https://x/stationboard?id=8500001&limit=40", requested.single())
        assertEquals(Direction.TO_WORK, board.direction)
        assertEquals("8500001", board.fromStopId)
        assertEquals(42L, board.fetchedAtEpochMs)
        assertEquals(3, board.departures.size)
    }

    @Test
    fun `fetches further pages until enough direct departures are found`() {
        // Page 1: one direct departure, last row at 08:20. Page 2: two more.
        fun row(t: String, to: String) =
            """{"number":"7","category":"T","to":"$to","stop":{"departure":"2026-11-03T$t:00+0100"}}"""
        val page1 = """{"stationboard":[${row("08:10", "Townsville, Station")},${row("08:20", "Townsville, Harbour")}]}"""
        val page2 = """{"stationboard":[${row("08:21", "Townsville, Station")},${row("08:40", "Townsville, Station")}]}"""
        val requested = mutableListOf<String>()
        val client = TimetableClient(baseUrl = "https://x", http = { url ->
            requested += url
            if (url.contains("datetime")) page2 else page1
        })
        val board = client.departures(ROUTE, Direction.TO_WORK, 0)
        assertEquals(3, board.departures.size)
        assertEquals(2, requested.size)
        // The second page starts a minute after the last row of the first, in Swiss time.
        assertTrue(requested[1].endsWith("&datetime=2026-11-03+08%3A21"))
    }

    @Test
    fun `stops paging when the timetable runs out`() {
        val empty = """{"stationboard":[]}"""
        val requested = mutableListOf<String>()
        val board = TimetableClient(http = { requested += it; empty }).departures(ROUTE, Direction.TO_WORK, 0)
        assertTrue(board.departures.isEmpty())
        assertEquals(1, requested.size)
    }

    @Test
    fun `never fetches more than the page limit`() {
        var calls = 0
        var minute = 0
        TimetableClient(http = {
            calls++
            minute += 10
            """{"stationboard":[{"number":"9","to":"Elsewhere","stop":{"departure":"2026-11-03T08:${minute}:00+0100"}}]}"""
        }).departures(ROUTE, Direction.TO_WORK, 0)
        assertEquals(TimetableClient.MAX_PAGES, calls)
    }

    @Test
    fun `station board keeps everything and has no direction`() {
        val board = TimetableClient(http = { fixture("market.json") }).stationBoard(MARKET, 1L)
        assertEquals(null, board.direction)
        assertEquals(5, board.departures.size)
    }

    @Test
    fun `stop search keeps only real stations`() {
        val requested = mutableListOf<String>()
        val client = TimetableClient(baseUrl = "https://x", http = { requested += it; fixture("locations.json") })
        val stops = client.searchStops(" Market ")
        assertEquals("https://x/locations?type=station&query=Market", requested.single())
        assertEquals(listOf(Stop("8500001", "Townsville, Market", 47.001, 7.002)), stops)
        assertEquals("Market", stops.single().label)
    }

    @Test
    fun `very short searches don't hit the network`() {
        val client = TimetableClient(http = { throw AssertionError("no request expected") })
        assertTrue(client.searchStops("a").isEmpty())
    }

    @Test(expected = IOException::class)
    fun `network errors propagate`() {
        TimetableClient(http = { throw IOException("offline") }).departures(ROUTE, Direction.TO_WORK, 0)
    }
}
