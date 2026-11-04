package app.departures

import androidx.test.core.app.ApplicationProvider
import app.departures.core.Board
import app.departures.core.Direction
import app.departures.core.LocationFix
import app.departures.core.ManualOverride
import app.departures.core.Route
import app.departures.core.Stop
import app.departures.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private val store = Store(ApplicationProvider.getApplicationContext())

    @Test
    fun `override round trips and clears`() {
        assertNull(store.override)
        store.override = ManualOverride(Direction.TO_HOME, 1234)
        assertEquals(ManualOverride(Direction.TO_HOME, 1234), store.override)
        store.override = null
        assertNull(store.override)
    }

    @Test
    fun `location fix keeps full precision`() {
        val fix = LocationFix(47.001234, 7.005678, 99)
        store.lastFix = fix
        assertEquals(fix, store.lastFix)
        store.lastFix = null
        assertNull(store.lastFix)
    }

    @Test
    fun `route and station stops round trip`() {
        val market = Stop("8500001", "Townsville, Market", 47.0, 7.0)
        val station = Stop("8500002", "Townsville, Station", 47.05, 7.05)
        assertNull(store.route)
        store.route = Route(market, station)
        assertEquals(Route(market, station), store.route)

        store.setStationStop(42, station)
        assertEquals(station, store.stationStop(42))
        assertNull(store.stationStop(43))
        store.setStationStop(42, null)
        assertNull(store.stationStop(42))
    }

    @Test
    fun `station boards are cached per stop`() {
        val board = Board(null, emptyList(), 5, "8500002")
        store.saveStationBoard(board)
        assertEquals(board, store.stationBoard("8500002"))
        assertNull(store.stationBoard("8500001"))
    }
}
