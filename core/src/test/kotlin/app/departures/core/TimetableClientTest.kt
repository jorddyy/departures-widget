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
