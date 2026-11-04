package app.departures

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import app.departures.core.UpdateInfo
import app.departures.data.InstallOutcome
import app.departures.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base = "https://github.com/o/r/releases/latest/download/"
    private val apkBytes = "pretend this is an APK".toByteArray()
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val downloaded = mutableListOf<String>()

    private fun updater(bytes: ByteArray = apkBytes) = Updater(
        context,
        http = { error("no version check expected") },
        download = { url, file -> downloaded += url; file.writeBytes(bytes) },
        baseUrl = base,
    )

    private fun info(url: String = "", sha: String = apkSha) = UpdateInfo(2, "1.0.2", "abc1234", sha, url)

    @Test
    fun `downloads the exact release named in version json`() {
        val url = "https://github.com/o/r/releases/download/build-2/departures.apk"
        val apk = updater().downloadVerified(info(url))
        assertEquals(listOf(url), downloaded)
        assertTrue(apk.exists())
    }

    @Test
    fun `ignores APK URLs outside this repository's releases`() {
        updater().downloadVerified(info("https://evil.example/departures.apk"))
        updater().downloadVerified(info("https://github.com/o/r/releases/download/../../x/departures.apk"))
        assertEquals(listOf(base + "departures.apk", base + "departures.apk"), downloaded)
    }

    @Test
    fun `a checksum mismatch fails and deletes the download`() {
        try {
            updater(bytes = "tampered".toByteArray()).downloadVerified(info())
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertFalse(File(context.cacheDir, "update/departures.apk").exists())
    }

    @Test
    fun `a missing checksum is never accepted`() {
        try {
            updater().downloadVerified(info(sha = ""))
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `installer statuses map to what the app shows`() {
        assertNull(Updater.outcomeFor(PackageInstaller.STATUS_SUCCESS, null))
        assertNull(Updater.outcomeFor(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
        assertEquals(InstallOutcome.Cancelled, Updater.outcomeFor(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(InstallOutcome.Conflict, Updater.outcomeFor(PackageInstaller.STATUS_FAILURE_CONFLICT, "x"))
        assertEquals(InstallOutcome.Failed("disk full"), Updater.outcomeFor(PackageInstaller.STATUS_FAILURE_STORAGE, "disk full"))
    }

    @Test
    fun `an install outcome is reported once`() {
        val u = updater()
        u.recordOutcome(InstallOutcome.Failed("oops: details"))
        assertEquals(InstallOutcome.Failed("oops: details"), u.takeOutcome())
        assertNull(u.takeOutcome())
    }
}
