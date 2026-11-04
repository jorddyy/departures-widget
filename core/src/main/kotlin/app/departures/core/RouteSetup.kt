package app.departures.core

/**
 * Applies a stop picked for Home or Work. During first-time setup the first pick is held as
 * pending until the second one completes the route.
 */
object RouteSetup {
    data class State(val route: Route?, val pendingHome: Stop?, val pendingWork: Stop?)

    fun apply(state: State, home: Boolean, stop: Stop): State {
        val pendingHome = if (home) stop else state.pendingHome
        val pendingWork = if (home) state.pendingWork else stop
        val newHome = if (home) stop else state.route?.home ?: pendingHome
        val newWork = if (home) state.route?.work ?: pendingWork else stop
        val route = if (newHome != null && newWork != null) Route(newHome, newWork) else state.route
        return State(route, pendingHome, pendingWork)
    }
}
