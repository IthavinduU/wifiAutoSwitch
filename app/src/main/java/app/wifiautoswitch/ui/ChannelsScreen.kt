package app.wifiautoswitch.ui

import android.content.Intent
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifiautoswitch.SettingsRepository
import app.wifiautoswitch.WifiLink
import app.wifiautoswitch.core.AccessPoint
import app.wifiautoswitch.core.Band
import app.wifiautoswitch.core.BandReport
import app.wifiautoswitch.core.ChannelAnalyzer
import app.wifiautoswitch.core.Ssid
import kotlinx.coroutines.delay

/** Wi-Fi channel checker: how crowded each channel is and which channel your routers should use. */
@Composable
fun ChannelsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val wifi = remember { context.getSystemService(WifiManager::class.java) }
    var reports by remember { mutableStateOf<List<BandReport>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val gateway = remember { WifiLink.current(context)?.gateway }

    val s = settings
    LaunchedEffect(s != null, refresh) {
        val st = s ?: return@LaunchedEffect
        scanning = true
        @Suppress("DEPRECATION")
        runCatching { wifi?.startScan() } // may be throttled by Android; cached results are used then
        delay(4_000)
        reports = ChannelAnalyzer.analyze(readAccessPoints(wifi), setOf(st.upstairsSsid, st.downstairsSsid))
        scanning = false
    }

    ToolScaffold("Wi-Fi channels", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (scanning) "Scanning nearby Wi-Fi…" else "Neighbouring Wi-Fi on each channel",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { refresh++ }, enabled = !scanning) { Text("Rescan") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

        val list = reports
        if (list != null && list.isEmpty()) {
            Text("No networks found. Make sure Wi-Fi and Location are on.", color = MaterialTheme.colorScheme.error)
        }
        list?.forEach { report ->
            BandCard(report) { ssid -> s?.roleOf(ssid)?.label }
        }

        if (list != null) {
            Text(
                "To change a channel, open your router's admin page → Wireless / WLAN settings → Channel, " +
                    "and set it instead of \"Auto\". Your two routers should use different channels.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (gateway != null) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$gateway")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Open router page ($gateway)") }
            }
        }
    }
}

@Composable
private fun BandCard(report: BandReport, labelFor: (String) -> String?) {
    ToolCard {
        Text("${report.band.label} · ${report.networkCount} networks", style = MaterialTheme.typography.titleMedium)

        if (report.own.isEmpty() && report.band != Band.GHZ_6) {
            Text("Your home networks weren't seen on this band.", style = MaterialTheme.typography.bodySmall)
        }
        report.own.forEach { own ->
            val name = labelFor(own.ssid)?.let { "$it (${own.ssid})" } ?: own.ssid
            val width = if (own.widthMhz > 20) ", ${own.widthMhz} MHz" else ""
            Text(
                if (own.worthChanging) "⚠ $name: channel ${own.channel}$width is crowded. Try channel ${own.bestChannel}."
                else "✓ $name: channel ${own.channel}$width is a good choice.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (own.worthChanging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (report.band == Band.GHZ_5) {
            Text(
                "Suggestions avoid radar (DFS) channels 52–144, which can make a router pause for a minute.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val ownChannels = report.own.map { it.channel }.toSet()
        val max = (report.loads.maxOfOrNull { it.networks } ?: 1).coerceAtLeast(1)
        Spacer(Modifier.height(4.dp))
        report.loads.forEach { load ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ch ${load.channel}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (load.channel in ownChannels) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(52.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(load.networks.toFloat() / max)
                            .height(10.dp)
                            .background(
                                if (load.channel in ownChannels) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                RoundedCornerShape(5.dp),
                            )
                    )
                }
                Text("  ${load.networks}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(32.dp))
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun readAccessPoints(wifi: WifiManager?): List<AccessPoint> =
    runCatching { wifi?.scanResults.orEmpty() }.getOrDefault(emptyList()).map { r ->
        AccessPoint(
            ssid = Ssid.normalize(r.SSID),
            bssid = r.BSSID ?: "",
            frequencyMhz = r.frequency,
            level = r.level,
            widthMhz = when (r.channelWidth) {
                ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                ScanResult.CHANNEL_WIDTH_320MHZ -> 320
                else -> 20
            },
            centerFreqMhz = r.centerFreq0,
        )
    }
