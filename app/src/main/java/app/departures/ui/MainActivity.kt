package app.departures.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.departures.core.Departure
import app.departures.core.Direction
import app.departures.core.Route
import app.departures.core.Stop
import app.departures.core.Format
import app.departures.R
import app.departures.data.Commute
import app.departures.data.LocationSource
import app.departures.data.Snapshot
import app.departures.data.Updater
import app.departures.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Detail view: more departures, manual direction choice and location settings. */
class MainActivity : ComponentActivity() {
    /** A failure reported by Android's package installer during an in-app update. */
    private val installMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val commute = Commute(applicationContext)
        val location = LocationSource(applicationContext)
        val updater = Updater(applicationContext)
        handleInstallStatus(intent)
        setContent {
            DeparturesTheme {
                DetailScreen(commute, location, updater, installMessage.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    /** Android's installer reports back here: show its confirmation screen, or the error. */
    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> installMessage.value = null
            PackageInstaller.STATUS_FAILURE_ABORTED -> installMessage.value = null // user tapped Cancel
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                installMessage.value = getString(R.string.update_conflict)
            else -> installMessage.value = getString(
                R.string.update_install_failed,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString(),
            )
        }
    }
}

@Composable
internal fun DeparturesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFB9A6F5))
        else -> lightColorScheme(primary = Color(0xFF5B3FA8))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(commute: Commute, location: LocationSource, updater: Updater, installMessage: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(commute.snapshot()) }
    var refreshing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var permissionsVersion by remember { mutableStateOf(0) }

    fun refresh(userInitiated: Boolean) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            snapshot = commute.refresh(userInitiated = userInitiated, inForeground = true)
            refreshing = false
            now = System.currentTimeMillis()
            WidgetUpdater.renderAll(context)
        }
    }

    // Opening the app is a clear signal you're about to travel: update location and data.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        now = System.currentTimeMillis()
        permissionsVersion++ // the user may have changed permissions in system settings
        val fetched = snapshot.board?.fetchedAtEpochMs ?: 0
        refresh(userInitiated = now - fetched > 60_000)
    }

    // Keep "in N min" current while the screen is visible. Purely local, no network.
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    // Home and Work: picked by search, stored only on this phone. During first-time setup the
    // first pick is held here until the second one completes the route.
    var pickingHome by remember { mutableStateOf(true) }
    var pendingHome by remember { mutableStateOf<Stop?>(null) }
    var pendingWork by remember { mutableStateOf<Stop?>(null) }
    val stopPicker = rememberLauncherForActivityResult(StopPickerActivity.Pick()) { stop ->
        if (stop == null) return@rememberLauncherForActivityResult
        if (pickingHome) pendingHome = stop else pendingWork = stop
        val current = commute.route
        val home = if (pickingHome) stop else current?.home ?: pendingHome
        val work = if (!pickingHome) stop else current?.work ?: pendingWork
        if (home != null && work != null) {
            commute.setRoute(Route(home, work))
            snapshot = commute.snapshot()
            WidgetUpdater.renderAll(context)
            refresh(userInitiated = true)
        }
    }
    fun pick(home: Boolean) {
        pickingHome = home
        stopPicker.launch(context.getString(if (home) R.string.choose_home else R.string.choose_work))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissionsVersion++
        refresh(userInitiated = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val route = snapshot.route
                    Column {
                        if (route == null) {
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        } else {
                            val direction = snapshot.resolution.direction
                            Text(stringResource(R.string.towards, route.to(direction).label), fontWeight = FontWeight.Bold)
                            Text(
                                Texts.subtitle(context, snapshot),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (snapshot.route == null) {
                        // Nothing to refresh until Home and Work are set.
                    } else if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { refresh(userInitiated = true) }) {
                            Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            val route = snapshot.route
            if (route == null) {
                item {
                    StopsCard(
                        title = stringResource(R.string.setup_title),
                        intro = stringResource(R.string.setup_intro),
                        home = pendingHome,
                        work = pendingWork,
                        onPick = ::pick,
                    )
                }
            } else {
            item {
                DirectionPicker(snapshot, route) { choice ->
                    commute.setDirection(choice)
                    snapshot = commute.snapshot()
                    WidgetUpdater.renderAll(context)
                    refresh(userInitiated = false)
                }
                Spacer(Modifier.padding(6.dp))
            }

            val upcoming = snapshot.board?.upcoming(now).orEmpty()
            if (upcoming.isEmpty()) {
                item {
                    val message = when {
                        refreshing || snapshot.board == null && !snapshot.offline -> stringResource(R.string.loading)
                        snapshot.board == null -> stringResource(R.string.no_connection)
                        else -> stringResource(R.string.no_departures)
                    }
                    Text(message, Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(upcoming, key = { it.scheduledEpochMs.toString() + it.destination }) { d ->
                DepartureRow(context, d, now)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            }
            if (route != null) item {
                val footer = Texts.footer(context, snapshot, refreshing = false)
                if (footer.isNotEmpty()) {
                    Text(
                        footer,
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Spacer(Modifier.padding(8.dp))
                if (route != null) {
                    StopsCard(stringResource(R.string.stops_title), null, route.home, route.work, onPick = ::pick)
                    Spacer(Modifier.padding(6.dp))
                }
                // Re-read permission state whenever a request completes or the app resumes.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !WidgetUpdater.canUseExactAlarms(context) && permissionsVersion >= 0
                ) {
                    MinutesCard {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    }
                    Spacer(Modifier.padding(6.dp))
                }
                LocationCard(context, location, permissionsVersion) { permission ->
                    permissionLauncher.launch(permission)
                }
                Spacer(Modifier.padding(6.dp))
                UpdateSection(updater, resumeCount = permissionsVersion, installMessage = installMessage)
                Text(
                    stringResource(R.string.privacy_note),
                    Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DirectionPicker(snapshot: Snapshot, route: Route, onChoose: (Direction?) -> Unit) {
    val manual = snapshot.overrideUntil != null
    val options: List<Pair<Direction?, String>> = listOf(
        null to stringResource(R.string.auto),
        Direction.TO_WORK to stringResource(R.string.towards, route.work.label),
        Direction.TO_HOME to stringResource(R.string.towards, route.home.label),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (direction, label) ->
            val selected = if (direction == null) !manual else manual && snapshot.resolution.direction == direction
            SegmentedButton(
                selected = selected,
                onClick = { onChoose(direction) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun DepartureRow(context: Context, d: Departure, now: Long) {
    val description = Texts.describe(context, d, now)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.clock(d.scheduledEpochMs), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Format.delay(d)?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
            val detail = buildString {
                append(d.destination.substringAfter(", ", d.destination))
                append(" · ")
                append(
                    when {
                        !d.realtime -> context.getString(R.string.scheduled)
                        (d.delayMinutes ?: 0) > 0 -> context.getString(R.string.late, d.delayMinutes)
                        (d.delayMinutes ?: 0) < 0 -> context.getString(R.string.early, -(d.delayMinutes ?: 0))
                        else -> context.getString(R.string.on_time)
                    },
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(Format.countdown(now, d), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Home and Work with a button to choose or change each. */
@Composable
private fun StopsCard(title: String, intro: String?, home: Stop?, work: Stop?, onPick: (home: Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (intro != null) Text(intro, style = MaterialTheme.typography.bodyMedium)
            for ((isHome, stop) in listOf(true to home, false to work)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (isHome) R.string.home else R.string.work),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stop?.name ?: stringResource(R.string.not_set), style = MaterialTheme.typography.bodyLarge)
                    }
                    FilledTonalButton(onClick = { onPick(isHome) }) {
                        Text(stringResource(if (stop == null) R.string.choose else R.string.change))
                    }
                }
            }
        }
    }
}

@Composable
private fun MinutesCard(onAllow: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.minutes_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.minutes_body), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.allow)) }
        }
    }
}

@Composable
private fun LocationCard(
    context: Context,
    location: LocationSource,
    @Suppress("UNUSED_PARAMETER") version: Int,
    request: (String) -> Unit,
) {
    val (body, action) = when {
        !location.hasForegroundPermission() ->
            R.string.location_off_body to (R.string.allow to Manifest.permission.ACCESS_COARSE_LOCATION)
        !location.isEnabled() -> R.string.location_disabled_body to null
        !location.hasBackgroundPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            R.string.location_fg_body to (R.string.allow_all_the_time to Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else -> R.string.location_on_body to null
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleSmall)
            Text(context.getString(body), style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, permission) ->
                FilledTonalButton(onClick = { request(permission) }) { Text(stringResource(label)) }
            }
        }
    }
}
