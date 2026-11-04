package app.departures

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import app.departures.core.Direction
import app.departures.core.LocationFix
import app.departures.core.Reason
import app.departures.core.Route
import app.departures.core.Stop
import app.departures.core.TimetableClient
import app.departures.data.Commute
import app.departures.data.Store
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class CommuteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = Store(context)

    private val market = Stop("8500001", "Townsville, Market", 47.0, 7.0)
    private val station = Stop("8500002", "Townsville, Station", 47.05, 7.05)
    private val route = Route(home = market, work = station)

    // 07:50 local time: morning, so time of day says "to work".
    private var now = java.time.OffsetDateTime.parse("2026-11-03T07:50:00+01:00").toInstant().toEpochMilli()
    private var calls = 0
    private val requested = mutableListOf<String>()
    private var failing = false

    private fun board(departures: List<String>) = buildString {
        append("""{"stationboard":[""")
        append(
            departures.joinToString(",") { t ->
                """{"number":"7","category":"T","to":"Townsville, Station","stop":{"departure":"2026-11-03T$t:00+0100","delay":0,"prognosis":{"departure":"2026-11-03T$t:00+0100"}}},""" +
                    """{"number":"7","category":"T","to":"Townsville, Harbour","passList":[{"station":null},{"station":{"id":"8500003","name":"Townsville, Harbour"}}],"stop":{"departure":"2026-11-03T$t:00+0100"}}"""
            },
        )
        append("]}")
    }

    private val client = TimetableClient(http = {
        calls++
        requested += it
        if (failing) throw IOException("offline")
        board(listOf("07:52", "07:58", "08:04", "08:10"))
    })

    private fun commute() = Commute(context, store, client = client, clock = { now })

    @Before
    fun setUp() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        store.route = route
    }

    @Test
    fun `nothing happens until home and work are set`() = runBlocking {
        store.route = null
        val s = commute().refresh(userInitiated = true)
        assertNull(s.route)
        assertEquals(0, calls)
        assertFalse(commute().needsFetch())
    }

    @Test
    fun `first refresh fetches from home in the morning and caches`() = runBlocking {
        val s = commute().refresh(userInitiated = false)
        assertEquals(1, calls)
        assertTrue(requested.single().contains("id=8500001"))
        assertEquals(Direction.TO_WORK, s.resolution.direction)
        assertEquals(Reason.TIME_OF_DAY, s.resolution.reason)
        assertEquals(4, s.board!!.departures.size)
        assertFalse(s.offline)
    }

    @Test
    fun `fresh cache is reused by automatic refreshes but not by the user`() = runBlocking {
        val c = commute()
        c.refresh(userInitiated = false)
        c.refresh(userInitiated = false)
        assertEquals(1, calls)
        c.refresh(userInitiated = true)
        assertEquals(2, calls)
    }

    @Test
    fun `changing a stop invalidates the cache`() = runBlocking {
        val c = commute()
        c.refresh(userInitiated = false)
        c.setRoute(route.copy(home = Stop("8500005", "Townsville, Park", 47.01, 7.01)))
        assertNull(c.snapshot().board)
        assertTrue(c.needsFetch())
        c.refresh(userInitiated = false)
        assertTrue(requested.last().contains("id=8500005"))
    }

    @Test
    fun `failure keeps the old board and marks it offline`() = runBlocking {
        val c = commute()
        c.refresh(userInitiated = false)
        failing = true
        now += 6 * 60_000
        val s = c.refresh(userInitiated = true)
        assertTrue(s.offline)
        assertNotNull(s.board)
    }

    @Test
    fun `automatic refreshes back off after a failure`() = runBlocking {
        failing = true
        val c = commute()
        c.refresh(userInitiated = false)
        c.refresh(userInitiated = false)
        assertEquals(1, calls)
        now += Commute.RETRY_BACKOFF_MS
        c.refresh(userInitiated = false)
        assertEquals(2, calls)
    }

    @Test
    fun `no automatic network use while the screen is off`() = runBlocking {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        commute().refresh(userInitiated = false)
        assertEquals(0, calls)
        commute().refresh(userInitiated = true)
        assertEquals(1, calls)
    }

    @Test
    fun `stored location near work flips the direction`() {
        store.lastFix = LocationFix(station.latitude, station.longitude, now - 60_000)
        val s = commute().snapshot()
        assertEquals(Direction.TO_HOME, s.resolution.direction)
        assertEquals(Reason.NEAR_STOP, s.resolution.reason)
    }

    @Test
    fun `toggle sets a temporary override and toggling back returns to auto`() {
        val c = commute()
        c.toggleDirection()
        val manual = c.snapshot()
        assertEquals(Direction.TO_HOME, manual.resolution.direction)
        assertEquals(Reason.MANUAL, manual.resolution.reason)
        assertEquals(now + Commute.OVERRIDE_MS, manual.overrideUntil)

        c.toggleDirection()
        assertNull(store.override)
        assertEquals(Reason.TIME_OF_DAY, c.snapshot().resolution.reason)
    }

    @Test
    fun `override expires`() {
        val c = commute()
        c.setDirection(Direction.TO_HOME)
        now += Commute.OVERRIDE_MS
        assertEquals(Direction.TO_WORK, c.snapshot().resolution.direction)
    }

    @Test
    fun `boards are cached per direction`() = runBlocking {
        val c = commute()
        c.refresh(userInitiated = false)
        assertNotNull(c.board(Direction.TO_WORK))
        assertNull(c.board(Direction.TO_HOME))
    }
}
