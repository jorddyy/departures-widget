package app.departures.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.departures.R
import app.departures.core.Stop
import app.departures.core.StopCodec
import app.departures.core.TimetableClient
import app.departures.data.Stations
import app.departures.widget.WidgetUpdater
import app.departures.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Search for a stop by name. Used to set Home and Work, and as the configuration screen of the
 * station-board widget. Only the text you type is sent to the timetable API.
 */
class StopPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val forWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        // Leaving without choosing cancels adding the widget.
        if (forWidget) setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.choose_station)
        setContent {
            DeparturesTheme {
                StopSearch(title) { stop -> if (forWidget) configureWidget(widgetId, stop) else returnStop(stop) }
            }
        }
    }

    private fun configureWidget(widgetId: Int, stop: Stop) {
        Stations(applicationContext).setStop(widgetId, stop)
        WidgetUpdater.renderAll(applicationContext)
        RefreshWorker.schedulePeriodic(applicationContext)
        RefreshWorker.enqueue(applicationContext, userInitiated = true)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun returnStop(stop: Stop) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_STOP, StopCodec.encodeStop(stop)))
        finish()
    }

    /** Opens the picker with a title and returns the chosen stop, or null if cancelled. */
    class Pick : ActivityResultContract<String, Stop?>() {
        override fun createIntent(context: Context, input: String): Intent =
            Intent(context, StopPickerActivity::class.java).putExtra(EXTRA_TITLE, input)

        override fun parseResult(resultCode: Int, intent: Intent?): Stop? =
            if (resultCode == Activity.RESULT_OK) StopCodec.decodeStop(intent?.getStringExtra(EXTRA_STOP)) else null
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_STOP = "stop"
    }
}

private sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Results(val stops: List<Stop>) : SearchState
    data object Offline : SearchState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopSearch(title: String, onPick: (Stop) -> Unit) {
    val client = remember { TimetableClient() }
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            state = SearchState.Idle
            return@LaunchedEffect
        }
        delay(350) // wait until typing pauses
        state = SearchState.Searching
        state = try {
            SearchState.Results(withContext(Dispatchers.IO) { client.searchStops(query) })
        } catch (_: IOException) {
            SearchState.Offline
        } catch (_: RuntimeException) {
            SearchState.Offline
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_stop)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            val message = when (val s = state) {
                SearchState.Idle -> stringResource(R.string.search_hint)
                SearchState.Searching -> stringResource(R.string.searching)
                SearchState.Offline -> stringResource(R.string.no_connection)
                is SearchState.Results -> if (s.stops.isEmpty()) stringResource(R.string.no_stops_found) else null
            }
            if (message != null) {
                Text(
                    message,
                    Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val results = (state as? SearchState.Results)?.stops.orEmpty()
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(results, key = { it.id }) { stop ->
                    Text(
                        stop.name,
                        Modifier.fillMaxWidth().clickable { onPick(stop) }.padding(vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
