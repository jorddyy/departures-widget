package app.departures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Describes the newest published build. CI uploads it as `version.json` next to `departures.apk`
 * in the latest GitHub release.
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val commit: String = "",
    /** Lower-case hex SHA-256 of departures.apk, checked after download. Required. */
    val apkSha256: String = "",
    /**
     * Where this exact build's APK lives (a release-specific URL), so the APK always matches
     * this file even if a newer release is published mid-download.
     */
    val apkUrl: String = "",
) {
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null if the text isn't a valid version file. */
        fun parse(text: String): UpdateInfo? = try {
            json.decodeFromString(serializer(), text).takeIf { it.versionCode > 0 && it.apkSha256.length == 64 }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
