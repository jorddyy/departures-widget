package app.departures.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.departures.core.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Coarse, on-device location using the platform LocationManager (no Google Play Services).
 *
 * It prefers locations other apps already obtained (free), and only asks the low-power
 * network provider for a fresh fix when the cached one is too old to be useful.
 */
class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasForegroundPermission(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** On Android 10+ the widget can only read location in the background with "Allow all the time". */
    fun hasBackgroundPermission(): Boolean =
        hasForegroundPermission() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    fun isEnabled(): Boolean = manager != null && LocationManagerCompat.isLocationEnabled(manager)

    fun canUse(inForeground: Boolean): Boolean =
        isEnabled() &&
            if (inForeground) hasForegroundPermission() else hasBackgroundPermission()

    /**
     * Returns a fix no older than [maxAgeMs], or null. Only call when [canUse] is true for the
     * current situation.
     */
    @SuppressLint("MissingPermission")
    suspend fun fix(nowEpochMs: Long, maxAgeMs: Long, timeoutMs: Long = 10_000): LocationFix? {
        val lm = manager ?: return null
        val cached = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: SecurityException) {
            return null
        }
        if (cached != null && nowEpochMs - cached.time <= maxAgeMs) return cached.toFix()

        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            else -> return cached?.toFix()
        }
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { cont.resume(it) }
                } catch (_: SecurityException) {
                    cont.resume(null)
                }
            }
        }
        return (fresh ?: cached)?.toFix()
    }

    private fun Location.toFix() = LocationFix(latitude, longitude, time)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
