package app.wifiautoswitch.ui

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifiautoswitch.AppState
import app.wifiautoswitch.SettingsRepository
import app.wifiautoswitch.core.Outage
import app.wifiautoswitch.core.OutageLog
import app.wifiautoswitch.core.formatDuration
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Internet outage log: "connected to home Wi-Fi but no internet" periods, shareable as proof for the ISP. */
@Composable
fun OutagesScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by AppState.outageLogRevision.collectAsStateWithLifecycle()
    val ongoingSince by AppState.ongoingOutageSince.collectAsStateWithLifecycle()
    val ongoingNetwork by AppState.ongoingOutageNetwork.collectAsStateWithLifecycle()
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    var outages by remember { mutableStateOf<List<Outage>>(emptyList()) }

    LaunchedEffect(revision) { outages = OutageLog.parse(repo.loadOutages()) }

    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val timeOnly = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    fun describe(o: Outage) =
        "${dateTime.format(Date(o.startMs))} → ${timeOnly.format(Date(o.endMs))} · ${formatDuration(o.durationMs)} · ${o.network}"

    ToolScaffold("Internet outages", onBack) {
        if (!running) {
            Text(
                "Outages are only recorded while Auto Switch is on.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ongoingSince?.let { since ->
            ToolCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text("No internet right now", style = MaterialTheme.typography.titleMedium)
                Text(
                    "On ${ongoingNetwork ?: "home Wi-Fi"} since ${timeOnly.format(Date(since))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val (count, total) = OutageLog.summary(outages, monthAgo)
        ToolCard {
            Text("Last 30 days", style = MaterialTheme.typography.titleMedium)
            Text(
                if (count == 0) "No outages recorded." else "$count outage${if (count == 1) "" else "s"}, ${formatDuration(total)} without internet in total.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (outages.isNotEmpty()) {
            Row {
                FilledTonalButton(onClick = {
                    val text = buildString {
                        appendLine("Home internet outages (recorded by WiFi AutoSwitch)")
                        appendLine("Last 30 days: $count outages, ${formatDuration(total)} in total")
                        appendLine()
                        outages.reversed().forEach { appendLine(describe(it)) }
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Internet outage log")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(Intent.createChooser(send, "Share outage log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Share log") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    outages = emptyList()
                    scope.launch { repo.saveOutages("") }
                }) { Text("Clear") }
            }
            ToolCard {
                outages.reversed().take(100).forEach { Text(describe(it), style = MaterialTheme.typography.bodySmall) }
            }
        }

        Text(
            "Recorded when your phone is on your home Wi-Fi but the internet stops working for 30 seconds or more. " +
                "Power cuts that switch the routers off look like leaving home, so they aren't counted.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
