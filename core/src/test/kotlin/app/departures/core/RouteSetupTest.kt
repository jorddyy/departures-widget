package app.departures.core

import app.departures.core.TestData.MARKET
import app.departures.core.TestData.ROUTE
import app.departures.core.TestData.STATION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSetupTest {
    private val park = Stop("8500005", "Townsville, Park", 47.01, 7.01)

    @Test
    fun `first-time setup completes after both picks, in either order`() {
        val afterWork = RouteSetup.apply(RouteSetup.State(null, null, null), home = false, stop = STATION)
        assertNull(afterWork.route)
        assertEquals(STATION, afterWork.pendingWork)
        val done = RouteSetup.apply(afterWork, home = true, stop = MARKET)
        assertEquals(ROUTE, done.route)
    }

    @Test
    fun `changing one stop keeps the other`() {
        val start = RouteSetup.State(ROUTE, null, null)
        assertEquals(Route(MARKET, park), RouteSetup.apply(start, home = false, stop = park).route)
        assertEquals(Route(park, STATION), RouteSetup.apply(start, home = true, stop = park).route)
    }
}
