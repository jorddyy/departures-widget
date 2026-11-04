package app.departures.core

import app.departures.core.TestData.MARKET
import app.departures.core.TestData.ROUTE
import app.departures.core.TestData.STATION
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionResolverTest {
    private val resolver = DirectionResolver()
    private val now = 1_800_000_000_000L

    private fun fixAt(lat: Double, lon: Double, ageMs: Long = 0) = LocationFix(lat, lon, now - ageMs)
    private fun resolve(hour: Int, fix: LocationFix?, override: ManualOverride? = null) =
        resolver.resolve(ROUTE, now, hour, override, fix)

    @Test
    fun `near home shows the way to work, even in the evening`() {
        assertEquals(Resolution(Direction.TO_WORK, Reason.NEAR_STOP), resolve(19, fixAt(47.002, 7.003)))
    }

    @Test
    fun `near work shows the way home, even in the morning`() {
        assertEquals(Resolution(Direction.TO_HOME, Reason.NEAR_STOP), resolve(8, fixAt(47.049, 7.048)))
    }

    @Test
    fun `elsewhere falls back to time of day and says so`() {
        val faraway = fixAt(46.5, 6.6)
        assertEquals(Resolution(Direction.TO_WORK, Reason.AWAY), resolve(8, faraway))
        assertEquals(Resolution(Direction.TO_HOME, Reason.AWAY), resolve(17, faraway))
    }

    @Test
    fun `halfway between the stops counts as away`() {
        assertEquals(Reason.AWAY, resolve(8, fixAt(47.025, 7.025)).reason)
    }

    @Test
    fun `no location uses time of day`() {
        assertEquals(Resolution(Direction.TO_WORK, Reason.TIME_OF_DAY), resolve(7, null))
        assertEquals(Resolution(Direction.TO_HOME, Reason.TIME_OF_DAY), resolve(13, null))
        // Late-night trips still point home.
        assertEquals(Direction.TO_HOME, resolve(1, null).direction)
    }

    @Test
    fun `stale location is ignored`() {
        val old = fixAt(47.049, 7.048, ageMs = DirectionResolver.MAX_FIX_AGE_MS + 1)
        assertEquals(Resolution(Direction.TO_WORK, Reason.TIME_OF_DAY), resolve(8, old))
    }

    @Test
    fun `active manual choice wins over location`() {
        val r = resolve(8, fixAt(47.049, 7.048), ManualOverride(Direction.TO_WORK, now + 1))
        assertEquals(Resolution(Direction.TO_WORK, Reason.MANUAL), r)
    }

    @Test
    fun `expired manual choice is ignored`() {
        assertEquals(Reason.TIME_OF_DAY, resolve(8, null, ManualOverride(Direction.TO_HOME, now)).reason)
    }

    @Test
    fun `radius is capped at 1 5 km and shrinks for close stops`() {
        assertEquals(DirectionResolver.NEAR_RADIUS_M, resolver.nearRadius(ROUTE), 0.1)
        val close = Route(MARKET, Stop("x", "Townsville, Corner", 47.009, 7.0)) // ~1 km apart
        assertEquals(400.0, resolver.nearRadius(close), 15.0)
    }

    @Test
    fun `distance between the test stops is about 6 point 7 km`() {
        assertEquals(6_700.0, distanceM(MARKET.latitude, MARKET.longitude, STATION.latitude, STATION.longitude), 200.0)
    }
}
