package app.departures.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.departures.core.UpdateInfo
import app.departures.BuildConfig
import app.departures.R
import app.departures.data.Updater
import kotlinx.coroutines.launch
import java.io.IOException

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo) : UpdateUi
    data class NeedsPermission(val info: UpdateInfo) : UpdateUi
    data object Offline : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/**
 * Version line plus update controls at the bottom of the detail screen. Checks automatically
 * when shown (rate-limited by [Updater]); the button forces a fresh check.
 *
 * @param resumeCount changes every time the app comes to the foreground, so a permission the user
 *   just granted in Settings is picked up.
 * @param installMessage a failure reported by Android's installer, if any.
 */
@Composable
fun UpdateSection(updater: Updater, resumeCount: Int, installMessage: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }

    fun check(force: Boolean) {
        state = UpdateUi.Checking
        scope.launch {
            state = try {
                updater.check(force)?.let { UpdateUi.Available(it) } ?: UpdateUi.UpToDate
            } catch (_: IOException) {
                UpdateUi.Offline
            }
        }
    }

    fun install(info: UpdateInfo) {
        if (!updater.canInstall()) {
            state = UpdateUi.NeedsPermission(info)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            )
            return
        }
        state = UpdateUi.Downloading(info)
        scope.launch {
            state = try {
                updater.downloadAndInstall(info)
                UpdateUi.Available(info) // stays offered until Android finishes and restarts the app
            } catch (e: IOException) {
                UpdateUi.Failed(e.message ?: context.getString(R.string.update_failed))
            }
        }
    }

    LaunchedEffect(resumeCount) {
        val current = state
        when {
            // Back from "Install unknown apps": continue if it's now allowed.
            current is UpdateUi.NeedsPermission && updater.canInstall() -> install(current.info)
            current is UpdateUi.Idle -> check(force = false)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.version_line, updater.installedVersionName, updater.installedVersionCode, BuildConfig.GIT_SHA),
                style = MaterialTheme.typography.titleSmall,
            )
            val status = installMessage ?: when (val s = state) {
                UpdateUi.Idle, UpdateUi.Checking -> stringResource(R.string.update_checking)
                UpdateUi.UpToDate -> stringResource(R.string.update_up_to_date)
                is UpdateUi.Available -> stringResource(R.string.update_available, s.info.versionName, s.info.versionCode)
                is UpdateUi.Downloading -> stringResource(R.string.update_downloading, s.info.versionName)
                is UpdateUi.NeedsPermission -> stringResource(R.string.update_needs_permission)
                UpdateUi.Offline -> stringResource(R.string.update_offline)
                is UpdateUi.Failed -> s.message
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    is UpdateUi.Available -> FilledTonalButton(onClick = { install(s.info) }) {
                        Text(stringResource(R.string.update_now))
                    }
                    is UpdateUi.NeedsPermission -> FilledTonalButton(onClick = { install(s.info) }) {
                        Text(stringResource(R.string.update_now))
                    }
                    else -> Unit
                }
                if (state !is UpdateUi.Checking && state !is UpdateUi.Downloading) {
                    TextButton(onClick = { check(force = true) }) { Text(stringResource(R.string.update_check)) }
                }
            }
        }
    }
}
