package app.departures

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.departures.core.Board
import app.departures.core.Departure
import app.departures.core.Direction
import app.departures.core.Reason
import app.departures.core.Resolution
import app.departures.core.Route
import app.departures.core.Stop
import app.departures.data.Snapshot
import app.departures.widget.StationWidgetUpdater
import app.departures.widget.WidgetUpdater
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetUpdaterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = java.time.OffsetDateTime.parse("2026-11-03T07:50:00+01:00").toInstant().toEpochMilli()
    private val min = 60_000L

    private val market = Stop("8500001", "Townsville, Market", 47.0, 7.0)
    private val station = Stop("8500002", "Townsville, Station", 47.05, 7.05)
    private val route = Route(home = market, work = station)

    private fun dep(scheduledIn: Long, delay: Int?, line: String = "7", destination: String = "Townsville, Hill") = Departure(
        line, destination, now + scheduledIn * min, now + (scheduledIn + (delay ?: 0)) * min, delay, delay != null,
    )

    private fun snapshot(direction: Direction, reason: Reason, board: Board?, offline: Boolean = false, overrideUntil: Long? = null) =
        Snapshot(route, Resolution(direction, reason), board, offline, overrideUntil)

    private fun render(s: Snapshot, compact: Boolean = false, minutes: Boolean = false): View =
        WidgetUpdater.build(context, s, refreshing = false, now = now, compact = compact, minutes = minutes)
            .apply(context, FrameLayout(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visibility(id: Int) = findViewById<View>(id).visibility

    @Test
    fun `shows the next two departures with delay`() {
        val board = Board(Direction.TO_WORK, listOf(dep(-5, 0), dep(4, 2), dep(12, null), dep(20, 0)), now - min)
        val v = render(snapshot(Direction.TO_WORK, Reason.NEAR_STOP, board))

        assertEquals("→ Station", v.text(R.id.title))
        assertEquals("From Market · you're near Market", v.text(R.id.subtitle))
        assertEquals("7", v.text(R.id.badge))
        assertEquals("07:54", v.text(R.id.time1))
        assertEquals("+2", v.text(R.id.delay1))
        assertEquals("08:02", v.text(R.id.time2))
        assertEquals("", v.text(R.id.delay2))
        assertEquals(View.GONE, v.visibility(R.id.empty))
        assertEquals("Updated 07:49", v.text(R.id.footer))
    }

    @Test
    fun `badge lists each line shown once`() {
        val board = Board(Direction.TO_WORK, listOf(dep(2, 0, "7"), dep(4, 0, "S1")), now)
        assertEquals("7/S1", render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, board), compact = true).text(R.id.badge))
    }

    @Test
    fun `asks to set up home and work when they are missing`() {
        val s = Snapshot(null, Resolution(Direction.TO_WORK, Reason.TIME_OF_DAY), null, false, null)
        for (compact in listOf(true, false)) {
            val v = render(s, compact = compact)
            assertEquals("Departures", v.text(R.id.title))
            assertEquals("Tap to choose your Home and Work stops", v.text(R.id.empty))
            assertEquals(View.GONE, v.visibility(R.id.swap))
            assertEquals(View.GONE, v.visibility(R.id.row1))
        }
    }

    @Test
    fun `shows a clear message when there is nothing to show`() {
        val offline = render(snapshot(Direction.TO_HOME, Reason.AWAY, null, offline = true))
        assertEquals(View.VISIBLE, offline.visibility(R.id.empty))
        assertEquals("No connection", offline.text(R.id.empty))
        assertEquals(View.GONE, offline.visibility(R.id.row1))
        assertEquals("From Station · away · by time of day", offline.text(R.id.subtitle))

        val loading = render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, null))
        assertEquals("Loading departures…", loading.text(R.id.empty))
    }

    @Test
    fun `offline board keeps departures and says so`() {
        val board = Board(Direction.TO_WORK, listOf(dep(3, 0)), now - 20 * min)
        val v = render(snapshot(Direction.TO_WORK, Reason.MANUAL, board, offline = true, overrideUntil = now + 60 * min))
        assertEquals(View.VISIBLE, v.visibility(R.id.row1))
        assertEquals(View.GONE, v.visibility(R.id.row2))
        assertEquals("Offline · last update 07:30", v.text(R.id.footer))
        assertEquals("From Market · manual until 08:50", v.text(R.id.subtitle))
    }

    @Test
    fun `compact layout shows route, short status and both departures`() {
        val board = Board(Direction.TO_HOME, listOf(dep(4, 2), dep(12, null)), now - min)
        val v = render(snapshot(Direction.TO_HOME, Reason.NEAR_STOP, board), compact = true)
        assertEquals("Station → Market", v.text(R.id.title))
        assertEquals("nearby", v.text(R.id.subtitle))
        assertEquals("07:54", v.text(R.id.time1))
        assertEquals("+2", v.text(R.id.delay1))
        assertEquals("08:02", v.text(R.id.time2))
        assertEquals(View.GONE, v.visibility(R.id.empty))
    }

    @Test
    fun `compact layout flags offline data and empty boards`() {
        val v = render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, null, offline = true), compact = true)
        assertEquals("offline", v.text(R.id.subtitle))
        assertEquals("No connection", v.text(R.id.empty))
        assertEquals(View.GONE, v.visibility(R.id.row1))
        assertEquals(View.GONE, v.visibility(R.id.row2))
    }

    @Test
    fun `minute mode shows whole minutes instead of a seconds countdown`() {
        val board = Board(Direction.TO_WORK, listOf(dep(0, null), dep(4, 1)), now - min)
        val s = snapshot(Direction.TO_WORK, Reason.NEAR_STOP, board)
        for (compact in listOf(true, false)) {
            val v = render(s, compact = compact, minutes = true)
            assertEquals(View.GONE, v.visibility(R.id.count1))
            assertEquals(View.VISIBLE, v.visibility(R.id.min1))
            assertEquals("now", v.text(R.id.min1))
            assertEquals("5 min", v.text(R.id.min2))
        }
        val seconds = render(s, compact = true, minutes = false)
        assertEquals(View.VISIBLE, seconds.visibility(R.id.count1))
        assertEquals(View.GONE, seconds.visibility(R.id.min1))
    }

    @Test
    fun `redraw timing follows the minute only in minute mode`() {
        val shown = listOf(dep(4, 0)) // 4 min away: "4 min" turns into "3 min" in a minute
        val nextRender = now + 4 * min + 30_000
        assertEquals(now + min, WidgetUpdater.nextTick(now, nextRender, shown, minutes = true))
        // Seconds countdown: the launcher ticks it, so only redraw when the tram leaves.
        assertEquals(nextRender, WidgetUpdater.nextTick(now, nextRender, shown, minutes = false))
    }

    // --- Station board ---

    private fun station(board: Board?, rows: Int, stop: Stop? = market, offline: Boolean = false): View =
        StationWidgetUpdater.build(context, 1, stop, board, offline, refreshing = false, now = now, rows = rows, minutes = true)
            .apply(context, FrameLayout(context))

    @Test
    fun `station board lists line, destination and time for as many rows as fit`() {
        val board = Board(
            null,
            listOf(dep(-3, 0), dep(2, 0, "7", "Townsville, Hill"), dep(5, 1, "S1", "Townsville, Harbour"), dep(9, null, "3", "Townsville, School")),
            now - min,
            "8500001",
        )
        val v = station(board, rows = 2)
        assertEquals("Townsville, Market", v.text(R.id.title))
        assertEquals("07:49", v.text(R.id.subtitle))
        assertEquals("7", v.text(R.id.r1_line))
        assertEquals("Hill", v.text(R.id.r1_dest))
        assertEquals("07:52", v.text(R.id.r1_time))
        assertEquals("2 min", v.text(R.id.r1_min))
        assertEquals("S1", v.text(R.id.r2_line))
        assertEquals("+1", v.text(R.id.r2_delay))
        assertEquals(View.GONE, v.visibility(R.id.r3))

        assertEquals(View.VISIBLE, station(board, rows = 4).visibility(R.id.r3))
    }

    @Test
    fun `station board without a stop asks for one`() {
        val v = station(null, rows = 4, stop = null)
        assertEquals("Tap to choose a stop", v.text(R.id.empty))
        assertEquals(View.GONE, v.visibility(R.id.r1))
    }

    @Test
    fun `station board offline without data says so`() {
        val v = station(null, rows = 4, offline = true)
        assertEquals("No connection", v.text(R.id.empty))
        assertEquals("offline", v.text(R.id.subtitle))
    }
}
