package app.departures.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardCodecTest {
    @Test
    fun `round trips a board`() {
        val board = Board(
            Direction.TO_HOME,
            listOf(
                Departure("7", "Townsville, Harbour", 1_000, 61_000, 1, true, "A"),
                Departure("7", "Townsville, Depot", 2_000, 2_000, null, false),
            ),
            fetchedAtEpochMs = 500,
            fromStopId = "8500001",
        )
        assertEquals(board, BoardCodec.decode(BoardCodec.encode(board)))
    }

    @Test
    fun `round trips a station board without direction`() {
        val board = Board(null, listOf(Departure("S1", "Townsville, Depot", 1, 1, null, false)), 9, "8500002")
        assertEquals(board, BoardCodec.decode(BoardCodec.encode(board)))
    }

    @Test
    fun `bad input decodes to null`() {
        assertNull(BoardCodec.decode(null))
        assertNull(BoardCodec.decode(""))
        assertNull(BoardCodec.decode("{not json"))
        assertNull(BoardCodec.decode("""{"v":2,"direction":"TO_MARS","fetchedAt":0,"departures":[]}"""))
        assertNull(BoardCodec.decode("""{"v":99,"direction":"TO_WORK","fetchedAt":0,"departures":[]}"""))
    }

    @Test
    fun `stops and routes round trip`() {
        assertEquals(TestData.ROUTE, StopCodec.decodeRoute(StopCodec.encodeRoute(TestData.ROUTE)))
        assertEquals(TestData.MARKET, StopCodec.decodeStop(StopCodec.encodeStop(TestData.MARKET)))
        assertNull(StopCodec.decodeRoute("{}"))
        assertNull(StopCodec.decodeStop(null))
    }
}
