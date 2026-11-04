package app.departures.core

/**
 * Decides when the network is worth touching. Rendering is free; fetching costs radio time,
 * so we fetch only when the cached board is for the wrong direction, getting old, or running
 * out of departures.
 */
object RefreshPolicy {
    /** Live delays move; older data is refreshed on the next opportunity. */
    const val STALE_AFTER_MS = 5 * 60_000L

    /** Keep at least this many upcoming departures cached (the widget shows two). */
    const val MIN_UPCOMING = 3

    /** Never schedule the next re-render further out than this. */
    const val MAX_RENDER_INTERVAL_MS = 15 * 60_000L

    /**
     * @param wanted the direction to show, or null for a station board.
     * @param fromStopId the stop the board must be for; a different cached stop means the
     *   user changed their stops.
     */
    fun shouldFetch(
        nowEpochMs: Long,
        board: Board?,
        wanted: Direction?,
        fromStopId: String,
        minUpcoming: Int = MIN_UPCOMING,
    ): Boolean {
        if (board == null || board.direction != wanted || board.fromStopId != fromStopId) return true
        if (nowEpochMs - board.fetchedAtEpochMs >= STALE_AFTER_MS) return true
        if (nowEpochMs < board.fetchedAtEpochMs) return true // clock moved backwards
        return board.upcoming(nowEpochMs).size < minUpcoming
    }

    /**
     * When the widget should next be redrawn: just after the first shown tram leaves, so it
     * drops off the list, but at least every [MAX_RENDER_INTERVAL_MS].
     */
    fun nextRenderAt(nowEpochMs: Long, board: Board?): Long {
        val cap = nowEpochMs + MAX_RENDER_INTERVAL_MS
        val first = board?.upcoming(nowEpochMs)?.firstOrNull() ?: return cap
        return (first.expectedEpochMs + 30_000).coerceIn(nowEpochMs + 60_000, cap)
    }

    /**
     * When the whole-minute countdown ("5 min") of any shown departure next changes, or when a
     * departure that shows "now" drops off the list. Null when nothing is shown.
     */
    fun nextMinuteChangeAt(nowEpochMs: Long, shown: List<Departure>): Long? = shown.minOfOrNull { d ->
        val remaining = d.expectedEpochMs - nowEpochMs
        if (remaining >= 60_000) {
            val intoMinute = remaining % 60_000
            nowEpochMs + if (intoMinute == 0L) 60_000 else intoMinute
        } else {
            d.expectedEpochMs + 30_000 // "now" until it leaves the list
        }
    }?.coerceAtLeast(nowEpochMs + 1_000)
}
