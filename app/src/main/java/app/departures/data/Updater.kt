package app.departures.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.edit
import app.departures.core.TimetableClient
import app.departures.core.UpdateInfo
import app.departures.BuildConfig
import app.departures.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks the latest GitHub release for a newer build and installs it.
 *
 * Only runs when the app is open (never from the widget): it fetches a ~100-byte version.json
 * from github.com, at most every [CHECK_INTERVAL_MS] unless you tap "Check for updates".
 * Android always asks you to confirm the install itself.
 */
class Updater(
    context: Context,
    private val http: (String) -> String = TimetableClient::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val installedVersionCode: Int = BuildConfig.VERSION_CODE
    val installedVersionName: String = BuildConfig.VERSION_NAME

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
        val text = http(BuildConfig.UPDATE_BASE_URL + "version.json")
        val info = UpdateInfo.parse(text) ?: throw IOException("Unexpected version file")
        prefs.edit {
            putLong(KEY_LAST_CHECK, now)
            putString(KEY_LAST_INFO, text)
        }
        info.takeIf { it.isNewerThan(installedVersionCode) }
    }

    /** False for local builds, which have no release to update from. */
    val enabled: Boolean = BuildConfig.UPDATE_BASE_URL.isNotEmpty()

    /** True if Android lets this app install updates ("Install unknown apps" is allowed). */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /**
     * Downloads the APK, checks its SHA-256 against version.json and hands it to Android's
     * installer. The confirmation screen arrives via [MainActivity.onNewIntent].
     */
    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val apk = File(app.cacheDir, "update/departures.apk").apply { parentFile?.mkdirs() }
        download(BuildConfig.UPDATE_BASE_URL + "departures.apk", apk)
        if (info.apkSha256.isNotEmpty() && !sha256(apk).equals(info.apkSha256, ignoreCase = true)) {
            apk.delete()
            throw IOException("Download corrupted (checksum mismatch)")
        }
        install(apk)
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
            val callback = PendingIntent.getActivity(
                app,
                sessionId,
                Intent(app, MainActivity::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                // Mutable: the installer adds the status and confirmation intent as extras.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun download(url: String, target: File) {
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

    private fun sha256(file: File): String {
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

    companion object {
        const val ACTION_INSTALL_STATUS = "app.departures.INSTALL_STATUS"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_LAST_INFO = "last_info"
    }
}
