package app.wifiautoswitch.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifiautoswitch.SettingsRepository
import app.wifiautoswitch.SpeedTester
import app.wifiautoswitch.WifiLink
import app.wifiautoswitch.core.SpeedHistory
import app.wifiautoswitch.core.SpeedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Speed and ping test, with a short history and averages per network (e.g. Upstairs vs Downstairs). */
@Composable
fun SpeedScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val tester = remember { SpeedTester(context) }
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<SpeedResult?>(null) }
    var lastDataMb by remember { mutableStateOf(0.0) }
    var history by remember { mutableStateOf<List<SpeedResult>>(emptyList()) }

    LaunchedEffect(Unit) { history = SpeedHistory.parse(repo.loadSpeedHistory()) }

    val ssid = remember { WifiLink.currentSsid(context) }
    val networkName = settings?.roleOf(ssid)?.label ?: ssid ?: "Wi-Fi"

    fun start() {
        if (running) return
        running = true
        error = null
        scope.launch {
            val r = tester.run(networkName) { text -> withContext(Dispatchers.Main) { step = text } }
            when (r) {
                is SpeedTester.Result.Ok -> {
                    last = r.result
                    lastDataMb = r.dataUsedMb
                    history = SpeedHistory.add(history, r.result)
                    repo.saveSpeedHistory(SpeedHistory.serialize(history))
                }
                is SpeedTester.Result.Failed -> error = r.message
            }
            running = false
        }
    }

    ToolScaffold("Speed test", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Testing on: $networkName", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { start() }, enabled = !running) { Text(if (running) "Testing…" else "Start test") }
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(step, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        last?.let { r ->
            ToolCard {
                Text("Result", style = MaterialTheme.typography.titleMedium)
                Metric("Download", r.downMbps?.let { "%.1f Mbps".format(Locale.US, it) })
                Metric("Upload", r.upMbps?.let { "%.1f Mbps".format(Locale.US, it) })
                Metric("Internet ping", r.pingMs?.let { "$it ms" })
                Metric("Router ping (Wi-Fi only)", r.routerPingMs?.let { "$it ms" })
                Text("Data used: about %.0f MB".format(Locale.US, lastDataMb), style = MaterialTheme.typography.bodySmall)
            }
        }

        val averages = SpeedHistory.averages(history)
        if (averages.isNotEmpty()) {
            ToolCard {
                Text("Averages by network", style = MaterialTheme.typography.titleMedium)
                averages.forEach { a ->
                    Text(
                        "${a.network} (${a.tests} tests): " + listOfNotNull(
                            a.downMbps?.let { "↓ %.0f".format(Locale.US, it) },
                            a.upMbps?.let { "↑ %.0f Mbps".format(Locale.US, it) },
                            a.pingMs?.let { "$it ms" },
                            a.routerPingMs?.let { "router $it ms" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent tests", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        history = emptyList()
                        scope.launch { repo.saveSpeedHistory("") }
                    }) { Text("Clear") }
                }
                val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                history.takeLast(10).reversed().forEach { r ->
                    Text(
                        "${fmt.format(Date(r.timeMs))} · ${r.network}: " + listOfNotNull(
                            r.downMbps?.let { "↓ %.0f".format(Locale.US, it) },
                            r.upMbps?.let { "↑ %.0f".format(Locale.US, it) },
                            r.pingMs?.let { "$it ms" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text(
            "Router ping shows the Wi-Fi link alone, so it's the best number for comparing upstairs and downstairs. " +
                "A test uses up to ~150 MB on very fast connections. Uses Cloudflare's public speed-test server.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Metric(label: String, value: String?) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value ?: "–", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
