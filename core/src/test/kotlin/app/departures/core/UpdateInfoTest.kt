package app.departures.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {
    @Test
    fun `parses the CI version file`() {
        val info = UpdateInfo.parse(
            """{"versionCode":12,"versionName":"1.0.12","commit":"a1b2c3d","apkSha256":"ab12","extra":true}""",
        )
        assertEquals(UpdateInfo(12, "1.0.12", "a1b2c3d", "ab12"), info)
    }

    @Test
    fun `only a higher version code is an update`() {
        val info = UpdateInfo(12, "1.0.12")
        assertTrue(info.isNewerThan(11))
        assertFalse(info.isNewerThan(12))
        assertFalse(info.isNewerThan(13))
    }

    @Test
    fun `rejects junk`() {
        assertNull(UpdateInfo.parse("<html>Not Found</html>"))
        assertNull(UpdateInfo.parse("""{"versionName":"1.0"}"""))
        assertNull(UpdateInfo.parse("""{"versionCode":0,"versionName":"x"}"""))
    }
}
