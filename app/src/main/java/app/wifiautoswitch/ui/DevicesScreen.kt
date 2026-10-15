package app.wifiautoswitch.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifiautoswitch.NetworkScanner
import app.wifiautoswitch.SettingsRepository
import app.wifiautoswitch.shizuku.ShizukuConnector
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val shizuku by ShizukuConnector.state.collectAsStateWithLifecycle()
    val scanner = remember { NetworkScanner(context, repo) }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NetworkScanner.Result?>(null) }
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)

    BackHandler(onBack = onBack)

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            result = scanner.scan()
            scanning = false
        }
    }

    // Scan automatically when the screen opens (and once Shizuku becomes ready).
    LaunchedEffect(shizuku) {
        if (shizuku == ShizukuConnector.State.READY && result == null) scan()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Who's on my Wi-Fi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

            settings?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Alert me about new devices", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Checks your home Wi-Fi every ~15 minutes, only while the phone is charging and Auto Switch is on.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = s.newDeviceAlerts,
                        onCheckedChange = { v -> scope.launch { repo.update { it.copy(newDeviceAlerts = v) } } },
                    )
                }
            }

            if (shizuku != ShizukuConnector.State.READY) {
                Text(
                    "This needs Shizuku, because Android hides other devices' MAC addresses from normal apps. " +
                        "${shizuku.label}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val ok = result as? NetworkScanner.Result.Ok
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            scanning -> "Scanning… (about 5 seconds)"
                            ok != null -> "${ok.networkName ?: "This Wi-Fi"} · ${ok.devices.size} devices"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { scan() }, enabled = !scanning) { Text("Scan") }
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

                when (val r = result) {
                    is NetworkScanner.Result.Failed ->
                        Text(r.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    is NetworkScanner.Result.Ok -> {
                        r.devices.forEach { device ->
                            DeviceCard(device) { mac ->
                                clipboard.setText(AnnotatedString(mac.uppercase()))
                                scope.launch { snackbar.showSnackbar("MAC address copied") }
                            }
                        }
                        val gateway = r.gateway
                        if (gateway != null) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("http://$gateway"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) { Text("Open router page ($gateway)") }
                        }
                        Text(
                            "Tap a device to copy its MAC address, e.g. to block it in your router's settings. " +
                                "Devices that are asleep may not show up; scan again if one is missing. " +
                                "Each router only knows about devices on its own Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    null -> Unit
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceCard(device: NetworkScanner.Device, onCopy: (String) -> Unit) {
    val title = when {
        device.isThisPhone -> "This phone"
        device.isRouter -> device.name ?: "Router"
        else -> device.name ?: "Unknown device"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = device.mac != null) { device.mac?.let(onCopy) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (device.isRouter) Tag("Router", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                if (device.isThisPhone) Tag("You", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                if (device.isNew) Tag("New", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(
                device.ip + (device.mac?.let { "  ·  ${it.uppercase()}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (device.privateMac) {
                Text("Private MAC: probably a phone, tablet or laptop", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Tag(text: String, background: Color, content: Color) {
    Spacer(Modifier.width(6.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
