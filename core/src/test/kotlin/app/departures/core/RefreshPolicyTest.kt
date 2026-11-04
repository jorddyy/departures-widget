package app.departures.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun dep(inMinutes: Long) =
        Departure("7", "Townsville, Station", now + inMinutes * min, now + inMinutes * min, 0, true)

    private fun board(vararg mins: Long, ageMin: Long = 0, dir: Direction = Direction.TO_WORK) =
        Board(dir, mins.map(::dep), now - ageMin * min, "S1")

    @Test
    fun `fresh full board is not refetched`() {
        assertFalse(RefreshPolicy.shouldFetch(now, board(2, 9, 16, 23), Direction.TO_WORK, "S1"))
    }

    @Test
    fun `fetches when nothing cached, wrong direction, stale or nearly empty`() {
        assertTrue(RefreshPolicy.shouldFetch(now, null, Direction.TO_WORK, "S1"))
        assertTrue(RefreshPolicy.shouldFetch(now, board(2, 9, 16, dir = Direction.TO_HOME), Direction.TO_WORK, "S1"))
        assertTrue(RefreshPolicy.shouldFetch(now, board(2, 9, 16, ageMin = 5), Direction.TO_WORK, "S1"))
        assertTrue(RefreshPolicy.shouldFetch(now, board(-3, 2, 9), Direction.TO_WORK, "S1"))
    }

    @Test
    fun `fetches when the clock went backwards`() {
        val future = Board(Direction.TO_WORK, listOf(dep(2), dep(9), dep(16)), now + 10 * min, "S1")
        assertTrue(RefreshPolicy.shouldFetch(now, future, Direction.TO_WORK, "S1"))
    }

    @Test
    fun `next render is just after the first tram leaves`() {
        assertEquals(now + 4 * min + 30_000, RefreshPolicy.nextRenderAt(now, board(4, 12)))
    }

    @Test
    fun `next render is clamped between one and fifteen minutes`() {
        assertEquals(now + min, RefreshPolicy.nextRenderAt(now, board(0, 12)))
        assertEquals(now + 15 * min, RefreshPolicy.nextRenderAt(now, board(40)))
        assertEquals(now + 15 * min, RefreshPolicy.nextRenderAt(now, null))
    }

    @Test
    fun `upcoming drops departed trams after a short grace period`() {
        val b = board(-2, 0, 5)
        assertEquals(2, b.upcoming(now).size)
    }

    @Test
    fun `minute countdown changes exactly when the displayed minute does`() {
        // 4 min 50 s away shows "4 min" and becomes "3 min" in 50 s.
        val d = Departure("7", "Townsville, Station", now, now + 290_000, 0, true)
        assertEquals(now + 50_000, RefreshPolicy.nextMinuteChangeAt(now, listOf(d)))
        // Exactly 5 min away: next change in a full minute.
        val e = d.copy(expectedEpochMs = now + 300_000)
        assertEquals(now + 60_000, RefreshPolicy.nextMinuteChangeAt(now, listOf(e)))
        // The earliest change of all shown departures wins.
        assertEquals(now + 50_000, RefreshPolicy.nextMinuteChangeAt(now, listOf(e, d)))
    }

    @Test
    fun `a tram showing now is redrawn when it leaves the list`() {
        val d = Departure("7", "Townsville, Station", now, now + 20_000, 0, true)
        assertEquals(now + 50_000, RefreshPolicy.nextMinuteChangeAt(now, listOf(d)))
        val gone = d.copy(expectedEpochMs = now - 29_500)
        assertEquals(now + 1_000, RefreshPolicy.nextMinuteChangeAt(now, listOf(gone)))
        assertEquals(null, RefreshPolicy.nextMinuteChangeAt(now, emptyList()))
    }

    @Test
    fun `a board for another stop is refetched`() {
        assertTrue(RefreshPolicy.shouldFetch(now, board(2, 9, 16, 23), Direction.TO_WORK, "S2"))
    }

    @Test
    fun `station boards have no direction`() {
        val b = Board(null, listOf(dep(2), dep(9), dep(16)), now, "S1")
        assertFalse(RefreshPolicy.shouldFetch(now, b, null, "S1"))
        assertTrue(RefreshPolicy.shouldFetch(now, b, Direction.TO_WORK, "S1"))
    }
}
