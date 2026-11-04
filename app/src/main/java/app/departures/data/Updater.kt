package app.departures.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.edit
import app.departures.BuildConfig
import app.departures.core.TimetableClient
import app.departures.core.UpdateInfo
import app.departures.ui.InstallStatusActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** How an install handed to Android's installer ended, when it didn't simply succeed. */
sealed interface InstallOutcome {
    /** You tapped Cancel on Android's confirmation screen. */
    data object Cancelled : InstallOutcome

    /** The APK is signed with a different key than the installed app. */
    data object Conflict : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}

/**
 * Checks the latest GitHub release for a newer build and installs it.
 *
 * Only runs when the app is open (never from the widgets): it fetches a ~200-byte version.json
 * from github.com, at most every [CHECK_INTERVAL_MS] unless you tap "Check for updates".
 * Android always asks you to confirm the install itself.
 */
class Updater(
    context: Context,
    private val http: (String) -> String = TimetableClient::httpGet,
    private val download: (String, File) -> Unit = ::httpDownload,
    private val clock: () -> Long = System::currentTimeMillis,
    /** "https://github.com/<owner>/<repo>/releases/latest/download/", or empty for local builds. */
    private val baseUrl: String = BuildConfig.UPDATE_BASE_URL,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val installedVersionCode: Int = BuildConfig.VERSION_CODE
    val installedVersionName: String = BuildConfig.VERSION_NAME

    /** False for local builds, which have no release to update from. */
    val enabled: Boolean = baseUrl.isNotEmpty()

    /**
     * Returns the newer release if there is one, null if up to date.
     * With [force] false, returns the cached answer when the last check was recent.
     *
     * @throws IOException when GitHub can't be reached.
     */
    suspend fun check(force: Boolean): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val now = clock()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) {
            return@withContext UpdateInfo.parse(prefs.getString(KEY_LAST_INFO, null).orEmpty())
                ?.takeIf { it.isNewerThan(installedVersionCode) }
        }
        val text = http(baseUrl + "version.json")
        val info = UpdateInfo.parse(text) ?: throw IOException("Unexpected version file")
        prefs.edit {
            putLong(KEY_LAST_CHECK, now)
            putString(KEY_LAST_INFO, text)
        }
        info.takeIf { it.isNewerThan(installedVersionCode) }
    }

    /** True if Android lets this app install updates ("Install unknown apps" is allowed). */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /**
     * The URL to download [info]'s APK from: its release-specific URL if it points into this
     * repository's releases, otherwise the latest release.
     */
    internal fun apkUrl(info: UpdateInfo): String {
        val releaseDownloads = baseUrl.removeSuffix("latest/download/") + "download/"
        return info.apkUrl.takeIf { it.startsWith(releaseDownloads) && !it.contains("..") }
            ?: (baseUrl + "departures.apk")
    }

    /**
     * Downloads the APK, checks its SHA-256 against version.json and hands it to Android's
     * installer. The result arrives in [InstallStatusActivity].
     */
    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val apk = downloadVerified(info)
        try {
            install(apk)
        } catch (e: SecurityException) {
            throw IOException("Android refused the install: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw IOException("Android's installer is busy: ${e.message}", e)
        } finally {
            // The installer has its own copy once the session is committed.
            apk.delete()
        }
    }

    /** Downloads and verifies the APK; throws [IOException] (and deletes it) if it doesn't match. */
    internal fun downloadVerified(info: UpdateInfo): File {
        val apk = File(app.cacheDir, "update/departures.apk").apply { parentFile?.mkdirs() }
        download(apkUrl(info), apk)
        if (info.apkSha256.isEmpty() || !sha256(apk).equals(info.apkSha256, ignoreCase = true)) {
            apk.delete()
            throw IOException("Download corrupted (checksum mismatch)")
        }
        return apk
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(app.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("departures.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Goes to a private (non-exported) activity, so no other app can feed it intents.
            val intent = Intent(app, InstallStatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = if (Build.VERSION.SDK_INT >= 34) {
                // Let the installer open its confirmation screen even if you switched away
                // from the app while the update downloaded.
                ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            val callback = PendingIntent.getActivity(
                app,
                sessionId,
                intent,
                // Mutable: the installer adds the status and confirmation intent as extras.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                options,
            )
            session.commit(callback.intentSender)
        }
    }

    /** Remembers how the last install ended, for the app screen to show. */
    fun recordOutcome(outcome: InstallOutcome) = prefs.edit {
        when (outcome) {
            InstallOutcome.Cancelled -> putString(KEY_OUTCOME, "cancelled")
            InstallOutcome.Conflict -> putString(KEY_OUTCOME, "conflict")
            is InstallOutcome.Failed -> putString(KEY_OUTCOME, "failed:" + outcome.message)
        }
    }

    /** Returns and forgets the last install outcome, if any. */
    fun takeOutcome(): InstallOutcome? {
        val raw = prefs.getString(KEY_OUTCOME, null) ?: return null
        prefs.edit { remove(KEY_OUTCOME) }
        return when {
            raw == "cancelled" -> InstallOutcome.Cancelled
            raw == "conflict" -> InstallOutcome.Conflict
            raw.startsWith("failed:") -> InstallOutcome.Failed(raw.removePrefix("failed:"))
            else -> null
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_LAST_INFO = "last_info"
        private const val KEY_OUTCOME = "install_outcome"

        /**
         * What a PackageInstaller status means for the app screen. Null for statuses that need
         * no message: success (the app restarts) and "waiting for you to confirm".
         */
        fun outcomeFor(status: Int, message: String?): InstallOutcome? = when (status) {
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> null
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Cancelled
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome.Conflict
            else -> InstallOutcome.Failed(message ?: "error $status")
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun httpDownload(url: String, target: File) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "DeparturesWidget/${BuildConfig.VERSION_NAME}")
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
        }
    }
}
