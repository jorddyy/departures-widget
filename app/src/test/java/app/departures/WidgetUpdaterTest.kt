package app.departures

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("7", v.text(R.id.badge_text))
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
        assertEquals("7/S1", render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, board), compact = true).text(R.id.badge_text))
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

    @Test
    fun `badges use the official line colour when known`() {
        val tram18 = Departure("18", "Townsville, Hill", now + 2 * min, now + 2 * min, 0, true, operator = "TPG")
        val v = render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, Board(Direction.TO_WORK, listOf(tram18), now)), compact = true)
        assertEquals(0xFFFFFFFF.toInt(), v.findViewById<TextView>(R.id.badge_text).currentTextColor)
        assertNotNull(v.findViewById<ImageView>(R.id.badge_bg).colorFilter)

        // Unknown operator: the app's default badge colours, set explicitly.
        val plain = render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, Board(Direction.TO_WORK, listOf(dep(2, 0)), now)), compact = true)
        assertEquals(context.getColor(R.color.badge_text), plain.findViewById<TextView>(R.id.badge_text).currentTextColor)
    }

    // The launcher reuses the existing view when it redraws, so a render must undo whatever an
    // earlier one changed. These tests redraw onto the same view, like the launcher does.

    private fun reapply(view: View, s: Snapshot, compact: Boolean) {
        WidgetUpdater.build(context, s, refreshing = false, now = now, compact = compact, minutes = true).reapply(context, view)
    }

    @Test
    fun `buttons come back after setup on a reused view`() {
        for (compact in listOf(true, false)) {
            val v = render(Snapshot(null, Resolution(Direction.TO_WORK, Reason.TIME_OF_DAY), null, false, null), compact = compact)
            assertEquals(View.GONE, v.visibility(R.id.swap))
            reapply(v, snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, Board(Direction.TO_WORK, listOf(dep(3, 0)), now)), compact)
            assertEquals(View.VISIBLE, v.visibility(R.id.swap))
            if (!compact) assertEquals(View.VISIBLE, v.visibility(R.id.refresh))
            assertEquals(View.VISIBLE, v.visibility(R.id.row1))
            assertEquals(View.GONE, v.visibility(R.id.empty))
        }
    }

    @Test
    fun `badge colour does not stick when the next line has none`() {
        val tram18 = Departure("18", "Townsville, Hill", now + 2 * min, now + 2 * min, 0, true, operator = "TPG")
        val v = render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, Board(Direction.TO_WORK, listOf(tram18), now)), compact = true)
        val official = v.findViewById<TextView>(R.id.badge_text).currentTextColor
        reapply(v, snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, Board(Direction.TO_WORK, listOf(dep(4, 0)), now)), compact = true)
        val text = v.findViewById<TextView>(R.id.badge_text).currentTextColor
        assertEquals(context.getColor(R.color.badge_text), text)
        assertEquals(0xFFFFFFFF.toInt(), official)
    }

    @Test
    fun `mixed lines get the default badge colour`() {
        val tram18 = Departure("18", "Townsville, Hill", now + 2 * min, now + 2 * min, 0, true, operator = "TPG")
        val tram12 = Departure("12", "Townsville, Hill", now + 5 * min, now + 5 * min, 0, true, operator = "TPG")
        val v = render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, Board(Direction.TO_WORK, listOf(tram18, tram12), now)), compact = true)
        assertEquals("18/12", v.text(R.id.badge_text))
        assertEquals(context.getColor(R.color.badge_text), v.findViewById<TextView>(R.id.badge_text).currentTextColor)
    }

    @Test
    fun `station board shows platforms and keeps other towns`() {
        val board = Board(
            null,
            listOf(
                Departure("IR 15", "Elsewhere, Gare", now + 3 * min, now + 3 * min, 0, true, platform = "3"),
                Departure("7", "Townsville, Hill", now + 4 * min, now + 4 * min, 0, true),
            ),
            now,
            "8500001",
        )
        val v = station(board, rows = 2)
        assertEquals("Elsewhere, Gare", v.text(R.id.r1_dest))
        assertEquals("3", v.text(R.id.r1_platform))
        assertEquals(View.VISIBLE, v.visibility(R.id.r1_platform))
        assertEquals("Hill", v.text(R.id.r2_dest))
        // No platform for this row, but the column stays so times line up.
        assertEquals(View.INVISIBLE, v.visibility(R.id.r2_platform))

        // A board without any platforms has no platform column.
        val plain = station(Board(null, listOf(board.departures[1]), now, "8500001"), rows = 2)
        assertEquals(View.GONE, plain.visibility(R.id.r1_platform))
    }

    @Test
    fun `screen readers hear line and destination`() {
        val d = Departure("7", "Townsville, Hill", now + 5 * min, now + 5 * min, 0, true)
        assertEquals(
            "Line 7 to Hill. Departs 07:55, on time, 5 min",
            app.departures.ui.Texts.describe(context, d, now, "Townsville, Market"),
        )
    }

    @Test
    fun `badge shape covers the number in every layout`() {
        // The coloured shape must be measured to the number's size, not to nothing.
        val tram18 = Departure("18", "Townsville, Hill", now + 2 * min, now + 2 * min, 0, true, operator = "TPG")
        val board = Board(Direction.TO_WORK, listOf(tram18), now)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun check(v: View, bg: Int, text: Int) {
            v.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), unspecified)
            val shape = v.findViewById<View>(bg)
            val number = v.findViewById<View>(text)
            assertTrue(number.measuredHeight > 0)
            assertEquals(number.measuredWidth, shape.measuredWidth)
            assertEquals(number.measuredHeight, shape.measuredHeight)
        }
        for (compact in listOf(true, false)) {
            check(render(snapshot(Direction.TO_WORK, Reason.TIME_OF_DAY, board), compact = compact), R.id.badge_bg, R.id.badge_text)
        }
        check(station(Board(null, listOf(tram18), now, "8500001"), rows = 2), R.id.r1_line_bg, R.id.r1_line)
    }

    @Test
    fun `station board columns line up whatever the delay`() {
        val board = Board(
            null,
            listOf(
                Departure("68", "Townsville, Hill", now + 3 * min, now + 4 * min, 1, true, platform = "S"),
                Departure("18", "Townsville, Harbour", now + 15 * min, now + 15 * min, 0, true, platform = "B"),
            ),
            now,
            "8500001",
        )
        val v = station(board, rows = 2)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        // Rows have the same width, so positions within a row are comparable.
        fun left(id: Int) = v.findViewById<View>(id).left
        assertEquals("+1", v.text(R.id.r1_delay))
        assertEquals("15 min", v.text(R.id.r2_min))
        // Platform, time and countdown columns start at the same x in both rows.
        assertEquals(left(R.id.r1_platform), left(R.id.r2_platform))
        assertEquals((v.findViewById<View>(R.id.r1_time).parent as View).left, (v.findViewById<View>(R.id.r2_time).parent as View).left)
        assertEquals(left(R.id.r1_min), left(R.id.r2_min))
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
        assertEquals("Updated 07:49", v.text(R.id.subtitle))
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
