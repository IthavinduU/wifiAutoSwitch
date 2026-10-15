package app.wifiautoswitch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.wifiautoswitch.AppState
import app.wifiautoswitch.Diagnostics
import app.wifiautoswitch.MonitoringService
import app.wifiautoswitch.Permissions
import app.wifiautoswitch.Settings
import app.wifiautoswitch.SettingsRepository
import app.wifiautoswitch.Status
import app.wifiautoswitch.StatusKind
import app.wifiautoswitch.Tuning
import app.wifiautoswitch.WifiMonitor
import app.wifiautoswitch.core.Role
import app.wifiautoswitch.core.SignalQuality
import app.wifiautoswitch.shizuku.ShizukuConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HomeScreen(repo: SettingsRepository, onOpenTool: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }

    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by AppState.status.collectAsStateWithLifecycle()
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val shizuku by ShizukuConnector.state.collectAsStateWithLifecycle()

    // Re-check permissions and system switches whenever the screen comes back into view.
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                ShizukuConnector.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasLocation = remember(resumeTick) { Permissions.hasPreciseLocation(context) }
    val approximateOnly = remember(resumeTick) { Permissions.hasApproximateOnly(context) }
    val locationOn = remember(resumeTick) { Permissions.isLocationEnabled(context) }
    val hasNotifications = remember(resumeTick) { Permissions.hasNotifications(context) }
    val hasBackgroundLocation = remember(resumeTick) { Permissions.hasBackgroundLocation(context) }

    // A light monitor for the on-screen readout; it only runs while the app is visible.
    val uiMonitor = remember { WifiMonitor(context) {} }
    DisposableEffect(lifecycleOwner, hasLocation, locationOn) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> uiMonitor.start()
                Lifecycle.Event.ON_STOP -> uiMonitor.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            uiMonitor.stop()
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                uiMonitor.refreshRssi()
                delay(5_000)
            }
        }
    }
    val connection by uiMonitor.connection.collectAsStateWithLifecycle()

    fun startAutoSwitch() {
        scope.launch {
            repo.update { it.copy(autoSwitch = true) }
            MonitoringService.start(context)
        }
    }

    var pendingEnable by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick++
        if (pendingEnable) {
            pendingEnable = false
            if (Permissions.hasPreciseLocation(context)) {
                startAutoSwitch()
            } else if (Permissions.hasApproximateOnly(context)) {
                message = "Please allow \"Precise\" location. Android hides Wi-Fi names from apps with approximate location."
            } else {
                message = "Location permission is needed to see Wi-Fi names and signal strength."
            }
        }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick++
    }

    fun setAutoSwitch(on: Boolean, s: Settings) {
        if (!on) {
            scope.launch {
                repo.update { it.copy(autoSwitch = false) }
                MonitoringService.stop(context)
            }
            return
        }
        if (!s.networksConfigured) {
            message = "Choose two different networks for Upstairs and Downstairs first."
            return
        }
        when {
            !Permissions.hasPreciseLocation(context) -> {
                pendingEnable = true
                permissionLauncher.launch(Permissions.initialRequest())
            }
            !Permissions.hasNotifications(context) -> {
                permissionLauncher.launch(Permissions.initialRequest())
                startAutoSwitch()
            }
            else -> startAutoSwitch()
        }
    }

    // If Auto Switch is on but monitoring isn't running (e.g. after a reboot), opening the app resumes it.
    LaunchedEffect(settings?.autoSwitch, resumeTick) {
        val s = settings ?: return@LaunchedEffect
        if (s.autoSwitch && !AppState.serviceRunning.value && s.networksConfigured && Permissions.hasPreciseLocation(context)) {
            MonitoringService.start(context)
        }
    }

    LaunchedEffect(Unit) { Tuning.load(repo) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    var pickerFor by remember { mutableStateOf<Role?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val s = settings
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("WiFi AutoSwitch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

            if (s == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                ConnectionCard(connection, s, hasLocation, locationOn)

                NetworksCard(s, onChange = { pickerFor = it })

                AutoSwitchCard(checked = s.autoSwitch, running = running, onCheckedChange = { setAutoSwitch(it, s) })

                val switchTarget = s.roleOf(connection?.ssid)?.other
                StatusCard(
                    status = if (s.autoSwitch || running) status else Status(StatusKind.OFF, "Auto Switch is off", "Turn it on to start monitoring."),
                    switchLabel = if (s.autoSwitch && running && status.kind != StatusKind.SWITCHING) {
                        switchTarget?.let { "Switch to ${it.label} now" } ?: "Switch now"
                    } else null,
                    onSwitchNow = {
                        if (ShizukuConnector.isReady) {
                            AppState.switchNowRequests.tryEmit(Unit)
                        } else {
                            // Without Shizuku Android won't let the app switch: open the Wi-Fi panel.
                            context.safeStart(Intent(AndroidSettings.Panel.ACTION_WIFI))
                        }
                    },
                )

                ToolsCard(onOpenTool)

                SetupCard(
                    hasLocation = hasLocation,
                    approximateOnly = approximateOnly,
                    locationOn = locationOn,
                    hasNotifications = hasNotifications,
                    shizuku = shizuku,
                    onRequestPermissions = { permissionLauncher.launch(Permissions.initialRequest()) },
                    onOpenLocationSettings = { context.safeStart(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onShizukuAction = {
                        val state = shizuku
                        if (state == ShizukuConnector.State.NOT_INSTALLED) {
                            context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                        } else if (state == ShizukuConnector.State.PERMISSION_NEEDED || state == ShizukuConnector.State.PERMISSION_DENIED) {
                            if (!ShizukuConnector.requestPermission() && !ShizukuConnector.openShizukuApp(context)) {
                                message = "Open Shizuku and allow WiFi AutoSwitch under Authorized applications."
                            }
                        } else if (!ShizukuConnector.openShizukuApp(context)) {
                            message = "Couldn't open Shizuku."
                        }
                    },
                )

                AdvancedCard(
                    open = advancedOpen,
                    onToggle = { advancedOpen = !advancedOpen },
                    settings = s,
                    hasBackgroundLocation = hasBackgroundLocation,
                    hasLocation = hasLocation,
                    onUpdate = { change -> scope.launch { repo.update(change) } },
                    onRequestBackgroundLocation = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    shizuku = shizuku,
                    onResetTuning = { scope.launch { Tuning.reset(repo) } },
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        val role = pickerFor
        if (role != null && s != null) {
            NetworkPickerDialog(
                role = role,
                current = s.ssidFor(role),
                connectedSsid = connection?.ssid,
                nearby = remember(role) { uiMonitor.visibleSsids() },
                hasLocation = hasLocation,
                onDismiss = { pickerFor = null },
                onPick = { ssid ->
                    pickerFor = null
                    val other = s.ssidFor(role.other)
                    if (ssid == other) {
                        message = "That network is already used for ${role.other.label}."
                    } else {
                        scope.launch {
                            repo.update { if (role == Role.UPSTAIRS) it.copy(upstairsSsid = ssid) else it.copy(downstairsSsid = ssid) }
                        }
                    }
                },
            )
        }
    }
}

private fun Context.safeStart(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun SectionCard(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConnectionCard(conn: WifiMonitor.Connection?, s: Settings, hasLocation: Boolean, locationOn: Boolean) {
    SectionCard {
        SectionLabel("Current connection")
        val name = when {
            !hasLocation -> "Unknown — location permission needed"
            !locationOn -> "Unknown — Location is off"
            conn == null -> "Not connected to Wi-Fi"
            conn.ssid == null -> "Wi-Fi (name hidden)"
            else -> conn.ssid
        }
        Text(name, style = MaterialTheme.typography.titleLarge)
        if (conn != null) {
            val rssi = conn.rssiOrNull
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(SignalQuality.bars(rssi))
                Spacer(Modifier.width(10.dp))
                Text(
                    (rssi?.let { "$it dBm · " } ?: "") + SignalQuality.label(rssi),
                    style = MaterialTheme.typography.bodyLarge,
                )
                s.roleOf(conn.ssid)?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        it.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 4) {
            Box(
                Modifier
                    .width(6.dp)
                    .height((8 + i * 5).dp)
                    .background(
                        if (i < bars) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

@Composable
private fun NetworksCard(s: Settings, onChange: (Role) -> Unit) {
    SectionCard {
        SectionLabel("Preferred networks")
        for (role in Role.values()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${role.label} Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        s.ssidFor(role).ifBlank { "Not set" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (s.ssidFor(role).isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                TextButton(onClick = { onChange(role) }) { Text(if (s.ssidFor(role).isBlank()) "Choose" else "Change") }
            }
        }
    }
}

@Composable
private fun AutoSwitchCard(checked: Boolean, running: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SectionCard(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto Switch", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        checked && running -> "On — watching your Wi-Fi"
                        checked -> "On — starting…"
                        else -> "Off"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(1.35f).padding(end = 8.dp))
        }
    }
}

@Composable
private fun StatusCard(status: Status, switchLabel: String?, onSwitchNow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = when (status.kind) {
        StatusKind.PERMISSION_REQUIRED, StatusKind.SERVICE_UNAVAILABLE, StatusKind.PAUSED -> colors.errorContainer
        StatusKind.SWITCHED, StatusKind.BETTER_AVAILABLE -> colors.tertiaryContainer
        else -> colors.surfaceContainerLow
    }
    SectionCard(containerColor = container) {
        SectionLabel("Status")
        Text(status.title, style = MaterialTheme.typography.titleLarge)
        if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodyMedium)
        if (switchLabel != null) {
            FilledTonalButton(onClick = onSwitchNow, modifier = Modifier.padding(top = 4.dp)) { Text(switchLabel) }
        }
    }
}

@Composable
private fun ToolsCard(onOpen: (Screen) -> Unit) {
    SectionCard {
        SectionLabel("Wi-Fi tools")
        ToolRow("Who's on my Wi-Fi", "Devices on this network and their MAC addresses") { onOpen(Screen.DEVICES) }
        ToolRow("Wi-Fi channels", "Find the least crowded channel for your routers") { onOpen(Screen.CHANNELS) }
        ToolRow("Speed test", "Download, upload and ping; compare floors") { onOpen(Screen.SPEED) }
        ToolRow("Internet outages", "When your internet dropped, to show your ISP") { onOpen(Screen.OUTAGES) }
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SetupCard(
    hasLocation: Boolean,
    approximateOnly: Boolean,
    locationOn: Boolean,
    hasNotifications: Boolean,
    shizuku: ShizukuConnector.State,
    onRequestPermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onShizukuAction: () -> Unit,
) {
    SectionCard {
        SectionLabel("Setup")
        if (!hasLocation) {
            SetupRow(
                if (approximateOnly) "Precise location is off. Android hides Wi-Fi names unless it's allowed."
                else "Location access lets the app read Wi-Fi names and signal. No GPS tracking.",
                "Allow", onRequestPermissions,
            )
        }
        if (!locationOn) SetupRow("Location is turned off. Android hides Wi-Fi scan results while it's off.", "Turn on", onOpenLocationSettings)
        if (!hasNotifications) SetupRow("Notifications are used for one-tap switch suggestions.", "Allow", onRequestPermissions)

        val (text, action) = when (shizuku) {
            ShizukuConnector.State.READY -> "Automatic switching is ready (Shizuku)." to null
            ShizukuConnector.State.NOT_INSTALLED ->
                "Optional: install Shizuku so the app can switch for you. Without it you get a one-tap notification instead." to "Get"
            ShizukuConnector.State.NOT_RUNNING ->
                "Shizuku is installed but not running (it needs restarting after each phone restart)." to "Open"
            ShizukuConnector.State.PERMISSION_NEEDED -> "Shizuku is running. Allow this app to use it." to "Allow"
            ShizukuConnector.State.PERMISSION_DENIED -> "Shizuku access was denied. Allow it in the Shizuku app." to "Allow"
            ShizukuConnector.State.UNSUPPORTED -> "Your Shizuku version is too old. Please update it." to "Open"
        }
        if (action != null) SetupRow(text, action, onShizukuAction) else Text("✓ $text", style = MaterialTheme.typography.bodyMedium)
        if (hasLocation && locationOn && hasNotifications && action == null) {
            Text("✓ Everything is set up.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SetupRow(text: String, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun AdvancedCard(
    open: Boolean,
    onToggle: () -> Unit,
    settings: Settings,
    hasBackgroundLocation: Boolean,
    hasLocation: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    shizuku: ShizukuConnector.State,
    onResetTuning: () -> Unit,
) {
    SectionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            IntSlider("Weak signal threshold", settings.weakDbm, -90..-60, 1, "dBm") { v -> onUpdate { it.copy(weakDbm = v) } }
            IntSlider("Minimum improvement", settings.minImprovementDb, 6..25, 1, "dB") { v -> onUpdate { it.copy(minImprovementDb = v) } }
            IntSlider("Cooldown after switching", settings.cooldownSec, 30..600, 30, "s") { v -> onUpdate { it.copy(cooldownSec = v) } }
            IntSlider("Scans that must agree", settings.requiredObservations, 1..4, 1, "") { v -> onUpdate { it.copy(requiredObservations = v) } }
            TextButton(onClick = { onUpdate { it.withDefaultThresholds() } }) { Text("Restore defaults") }

            HorizontalDivider()
            AutoTuneSection(settings.weakDbm, onApply = { v -> onUpdate { it.copy(weakDbm = v) } }, onReset = onResetTuning)

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Switch when internet stops", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "If one router has no internet for 30 seconds, move to the other (then wait 10 minutes before trying again).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.failover, onCheckedChange = { v -> onUpdate { it.copy(failover = v) } })
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Don't switch during calls", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Phone and WhatsApp-type calls. Switching waits until the call ends.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.pauseDuringCalls, onCheckedChange = { v -> onUpdate { it.copy(pauseDuringCalls = v) } })
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Resume after phone restarts", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (hasBackgroundLocation) "Starts automatically."
                        else "You'll get a one-tap notification after restart.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.resumeAfterReboot, onCheckedChange = { v -> onUpdate { it.copy(resumeAfterReboot = v) } })
            }
            if (settings.resumeAfterReboot && hasLocation && !hasBackgroundLocation) {
                Text(
                    "To restart without a tap, Android requires location \"Allow all the time\". " +
                        "It is only used to read Wi-Fi names; the app never requests your position.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onRequestBackgroundLocation) { Text("Allow all the time") }
            }

            HorizontalDivider()
            DiagnosticsSection(shizuku)
        }
    }
}

@Composable
private fun AutoTuneSection(currentThreshold: Int, onApply: (Int) -> Unit, onReset: () -> Unit) {
    val tuning by Tuning.state.collectAsStateWithLifecycle()
    val suggestion = tuning.suggestion
    Text("Auto-tune threshold", style = MaterialTheme.typography.titleSmall)
    Text(
        when {
            suggestion == null ->
                "Learning from ${tuning.readings} readings so far. Keep Auto Switch on and use your phone on " +
                    "both floors with the screen on; a suggestion appears after a few trips up and down."
            suggestion == currentThreshold ->
                "Your threshold ($currentThreshold dBm) already matches what ${tuning.readings} readings suggest."
            else ->
                "Based on ${tuning.readings} readings, $suggestion dBm suits your house better " +
                    "than the current $currentThreshold dBm."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Row {
        if (suggestion != null && suggestion != currentThreshold) {
            FilledTonalButton(onClick = { onApply(suggestion) }) { Text("Apply $suggestion dBm") }
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onReset, enabled = tuning.readings > 0) { Text("Reset readings") }
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, step: Int, unit: String, onCommit: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    fun snapped(): Int = (((local - range.first) / step).roundToInt() * step + range.first).coerceIn(range.first, range.last)
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${snapped()} $unit".trim(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(snapped()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun DiagnosticsSection(shizuku: ShizukuConnector.State) {
    val c by Diagnostics.counters.collectAsStateWithLifecycle()
    val log by Diagnostics.log.collectAsStateWithLifecycle()
    var showLog by rememberSaveable { mutableStateOf(false) }
    val minutes = ((System.currentTimeMillis() - c.sinceMillis) / 60_000).coerceAtLeast(1)

    Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
    Text(
        "Since ${minutes} min ago:\n" +
            "• Checks: ${c.evaluations} (timer ${c.timerChecks})\n" +
            "• Scans requested: ${c.appScanRequests} (throttled ${c.appScansRejected}) · scan results seen: ${c.scanResultEvents}\n" +
            "• Switches: ${c.switchAttempts} tried, ${c.switchSuccesses} ok, ${c.switchFailures} failed · prompts: ${c.userPrompts}\n" +
            "• Shizuku: ${shizuku.label}",
        style = MaterialTheme.typography.bodySmall,
    )
    Row {
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        TextButton(onClick = { Diagnostics.clear() }) { Text("Clear") }
    }
    if (showLog) {
        Text(
            if (log.isEmpty()) "No entries yet." else log.takeLast(100).reversed().joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun NetworkPickerDialog(
    role: Role,
    current: String,
    connectedSsid: String?,
    nearby: List<String>,
    hasLocation: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var manual by remember { mutableStateOf(current) }
    var saved by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val shizukuReady = ShizukuConnector.isReady

    LaunchedEffect(Unit) {
        if (shizukuReady) {
            loading = true
            saved = ShizukuConnector.listSavedSsids()
            loading = false
        }
    }
    val options = (listOfNotNull(connectedSsid) + saved + nearby).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${role.label} Wi-Fi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        shizukuReady -> "Your saved networks and nearby ones. Pick the one for ${role.label.lowercase()}."
                        hasLocation -> "Android doesn't let normal apps read your saved Wi-Fi list, so these are " +
                            "the current and nearby networks. If yours isn't here, type its name exactly."
                        else -> "Allow location to see nearby networks, or type the name exactly."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                options.forEach { ssid ->
                    Text(
                        ssid + if (ssid == connectedSsid) "  (connected)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(ssid) }
                            .padding(vertical = 10.dp),
                    )
                }
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text("Network name (exact)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(manual.trim()) }, enabled = manual.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
