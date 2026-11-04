package app.departures.core

/** Fictional stops used by the tests: about 6.7 km apart. */
object TestData {
    val MARKET = Stop("8500001", "Townsville, Market", 47.0, 7.0)
    val STATION = Stop("8500002", "Townsville, Station", 47.05, 7.05)
    val ROUTE = Route(home = MARKET, work = STATION)

    fun fixture(name: String): String = TestData::class.java.classLoader.getResource(name)!!.readText()
}
