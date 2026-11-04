package app.departures.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A location reading, as obtained on the device. Never leaves the phone. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val timeEpochMs: Long,
)

/** A manual direction choice that expires at [untilEpochMs]. */
data class ManualOverride(val direction: Direction, val untilEpochMs: Long)

/** Why a direction was chosen; shown in the widget so the choice is never a mystery. */
enum class Reason {
    MANUAL,
    NEAR_STOP,
    /** Location known, but not near either stop: fall back to time of day. */
    AWAY,
    /** No usable location: fall back to time of day. */
    TIME_OF_DAY,
}

data class Resolution(val direction: Direction, val reason: Reason)

/**
 * Decides which direction to show.
 *
 * Priority: a manual choice that has not expired, then a recent location near one of the two
 * stops, then the time of day (mornings towards work, afternoons and evenings towards home).
 */
class DirectionResolver(
    /** How close (metres) you must be to a stop to count as "near" it, at most. */
    private val maxNearRadiusM: Double = NEAR_RADIUS_M,
    /** Location fixes older than this are ignored. */
    private val maxFixAgeMs: Long = MAX_FIX_AGE_MS,
    /** Hour of day (local time, 0-23) at which the time-of-day guess switches to TO_HOME. */
    private val switchHour: Int = SWITCH_HOUR,
) {

    fun resolve(
        route: Route,
        nowEpochMs: Long,
        localHour: Int,
        override: ManualOverride?,
        fix: LocationFix?,
    ): Resolution {
        if (override != null && override.untilEpochMs > nowEpochMs) {
            return Resolution(override.direction, Reason.MANUAL)
        }
        val byTime = if (localHour in 3 until switchHour) Direction.TO_WORK else Direction.TO_HOME
        if (fix == null || nowEpochMs - fix.timeEpochMs > maxFixAgeMs) {
            return Resolution(byTime, Reason.TIME_OF_DAY)
        }
        val toHome = distanceM(fix.latitude, fix.longitude, route.home.latitude, route.home.longitude)
        val toWork = distanceM(fix.latitude, fix.longitude, route.work.latitude, route.work.longitude)
        val radius = nearRadius(route)
        return if (minOf(toHome, toWork) <= radius) {
            // Near home: you're about to go to work, and vice versa.
            Resolution(if (toHome <= toWork) Direction.TO_WORK else Direction.TO_HOME, Reason.NEAR_STOP)
        } else {
            Resolution(byTime, Reason.AWAY)
        }
    }

    /**
     * 1.5 km is wide enough for coarse (network) location, which is all the app asks for. For stops
     * close together it shrinks to 40% of their distance, so the two areas never overlap.
     */
    fun nearRadius(route: Route): Double {
        val apart = distanceM(route.home.latitude, route.home.longitude, route.work.latitude, route.work.longitude)
        return minOf(maxNearRadiusM, apart * 0.4)
    }

    companion object {
        const val NEAR_RADIUS_M = 1_500.0
        const val MAX_FIX_AGE_MS = 30 * 60_000L
        const val SWITCH_HOUR = 13
    }
}

/** Great-circle distance in metres. */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
