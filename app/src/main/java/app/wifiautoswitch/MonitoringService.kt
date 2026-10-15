package app.wifiautoswitch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.wifiautoswitch.core.Decision
import app.wifiautoswitch.core.Role
import app.wifiautoswitch.core.Snapshot
import app.wifiautoswitch.core.SwitchingEngine
import app.wifiautoswitch.shizuku.ShizukuConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (type "location", because Wi-Fi names and scan results are location data).
 *
 * Battery rules:
 *  - Screen off: no timers and no scan requests at all. Only free system events are processed.
 *  - Away from the two home networks (or Wi-Fi/Location off): fully dormant until the network changes.
 *  - Strong signal: signal-change events are ignored; one cached-value check every 2 minutes.
 *  - Near or below the threshold: cached checks every 30 s / 8 s and a strict scan budget.
 *  - Bursts of signal events are coalesced to at most one evaluation every 2 seconds.
 *  - System state (screen, Wi-Fi, Location) is cached from broadcasts instead of queried each time.
 * No wake locks, no alarms, no busy loops; coroutine delays never wake a sleeping CPU.
 *
 * Extras: waits for calls to end before switching, alerts if Shizuku stops, handles "Switch now"
 * from the app/tile, and feeds free scan readings to the threshold auto-tuner.
 */
class MonitoringService : Service() {

    companion object {
        private const val MIN_SCAN_GAP_MS = 20_000L
        private const val MAX_SCAN_GAP_MS = 5 * 60_000L
        private const val SCAN_BUDGET_WINDOW_MS = 120_000L
        /** Android lets a foreground app scan 4 times per 2 minutes; keep one spare for everyone else. */
        private const val SCAN_BUDGET = 3
        private const val VERIFY_TIMEOUT_MS = 25_000L
        private const val SWITCHED_BANNER_MS = 60_000L
        private const val MIN_EVAL_GAP_MS = 2_000L
        private const val SAMPLE_MAX_AGE_MS = 10_000L
        /** Shizuku's binder arrives a moment after start-up; don't alert during that window. */
        private const val SHIZUKU_ALERT_GRACE_MS = 10_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: SettingsRepository
    private lateinit var monitor: WifiMonitor
    private lateinit var connector: NetworkConnector
    private val engine = SwitchingEngine()

    private var settings = Settings()
    private var settingsLoaded = false
    private var running = false
    private var switchInProgress = false
    private var finalStatus: Status? = null

    private var tickJob: Job? = null
    private val scanTimes = ArrayDeque<Long>()
    private var nextScanGapMs = MIN_SCAN_GAP_MS
    private var lastSwitchedAtMs = -1L
    private var promptedFrom: Role? = null
    private var lastRole: Role? = null
    private var lastDecisionKey = ""
    private var lastNotificationText = ""

    // Cached system state, refreshed from broadcasts (avoids system calls on every evaluation).
    private var screenOn = true
    private var wifiOn = true
    private var locationOn = true
    private var lastRssi: Int? = null
    private var lastEvalAtMs = 0L
    private var pendingEval: Job? = null

    private var audio: AudioManager? = null
    private var inCall = false
    private var lastShizukuState: ShizukuConnector.State? = null
    private var shizukuAlertJob: Job? = null
    private var lastSampleScanMs = -1L

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val nowInCall = isCallMode(mode)
        if (nowInCall != inCall) {
            inCall = nowInCall
            Diagnostics.log(if (inCall) "Call started: switching waits" else "Call ended")
            evaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = try {
            ServiceCompat.startForeground(
                this,
                Notifications.MONITOR_ID,
                Notifications.monitoring(this, "Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: Exception) {
            Diagnostics.log("Android refused to start monitoring: ${e.javaClass.simpleName}")
            finalStatus = if (!Permissions.hasPreciseLocation(this)) {
                Status(StatusKind.PERMISSION_REQUIRED, "Permission required", "Allow precise location, then turn Auto Switch on.")
            } else {
                Status(StatusKind.SERVICE_UNAVAILABLE, "Service unavailable", "Android blocked starting in the background. Open the app to resume.")
            }
            false
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) begin()
        return START_STICKY
    }

    private fun begin() {
        running = true
        AppState.serviceRunning.value = true
        repo = SettingsRepository(this)
        connector = NetworkConnector(this)
        ShizukuConnector.init(this)
        Notifications.cancelResumePrompt(this)
        monitor = WifiMonitor(this, ::onEvent)
        monitor.start()
        refreshSystemState()
        audio = getSystemService(AudioManager::class.java)
        inCall = audio?.let { isCallMode(it.mode) } ?: false
        runCatching { audio?.addOnModeChangedListener(ContextCompat.getMainExecutor(this), modeListener) }
        Diagnostics.log("Monitoring started")
        Tiles.refresh(this)

        scope.launch { Tuning.load(repo) }
        scope.launch { AppState.switchNowRequests.collect { switchNow() } }

        scope.launch {
            repo.settings.collect { s ->
                settings = s
                settingsLoaded = true
                engine.thresholds = s.thresholds()
                updateShizukuAlert()
                if (!s.autoSwitch) {
                    finalStatus = Status(StatusKind.OFF, "Auto Switch is off")
                    stopSelf()
                } else {
                    evaluate()
                }
            }
        }
        scope.launch {
            ShizukuConnector.state.collect { state ->
                if (lastShizukuState != null && state != lastShizukuState) Diagnostics.log("Shizuku: ${state.label}")
                lastShizukuState = state
                updateShizukuAlert()
                evaluate()
            }
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { audio?.removeOnModeChangedListener(modeListener) }
        if (::repo.isInitialized) {
            val r = repo
            CoroutineScope(Dispatchers.IO).launch { Tuning.saveIfNeeded(r, force = true) }
        }
        scope.cancel()
        if (::monitor.isInitialized) monitor.stop()
        Notifications.cancelSwitchPrompt(this)
        Notifications.cancelShizukuStopped(this)
        AppState.serviceRunning.value = false
        AppState.status.value = finalStatus ?: Status(StatusKind.OFF, "Auto Switch is off")
        Diagnostics.log("Monitoring stopped")
        Tiles.refresh(this)
        super.onDestroy()
    }

    private fun isCallMode(mode: Int) = mode == AudioManager.MODE_IN_CALL ||
        mode == AudioManager.MODE_IN_COMMUNICATION ||
        mode == AudioManager.MODE_RINGTONE ||
        mode == AudioManager.MODE_CALL_SCREENING

    /** Alert only if Shizuku has worked before and stays unavailable past a short grace period. */
    private fun updateShizukuAlert() {
        if (!settingsLoaded) return
        if (ShizukuConnector.isReady) {
            shizukuAlertJob?.cancel()
            Notifications.cancelShizukuStopped(this)
            if (!settings.shizukuSetUp) scope.launch { repo.update { it.copy(shizukuSetUp = true) } }
        } else if (settings.shizukuSetUp && shizukuAlertJob?.isActive != true) {
            shizukuAlertJob = scope.launch {
                delay(SHIZUKU_ALERT_GRACE_MS)
                if (!ShizukuConnector.isReady) {
                    Notifications.showShizukuStopped(this@MonitoringService, ShizukuConnector.state.value.label)
                    Diagnostics.log("Shizuku unavailable: alert shown")
                }
            }
        }
    }

    /** One free reading per system scan for the auto-tuner (screen on only, both values fresh). */
    private fun sampleForTuning() {
        if (!screenOn || !settings.networksConfigured) return
        monitor.refreshRssi()
        val conn = monitor.connection.value ?: return
        val role = settings.roleOf(conn.ssid) ?: return
        val current = conn.rssiOrNull ?: return
        val now = now()
        if (now - conn.rssiAtMs > SAMPLE_MAX_AGE_MS) return
        val other = monitor.bestReading(settings.ssidFor(role.other)) ?: return
        if (now - other.scanTimeMs > SAMPLE_MAX_AGE_MS || other.scanTimeMs == lastSampleScanMs) return
        lastSampleScanMs = other.scanTimeMs
        Tuning.add(current, other.rssi, settings.minImprovementDb)
        scope.launch { Tuning.saveIfNeeded(repo) }
    }

    private fun refreshSystemState() {
        screenOn = monitor.isScreenOn
        wifiOn = monitor.isWifiEnabled
        locationOn = Permissions.isLocationEnabled(this)
    }

    private fun onEvent(event: WifiMonitor.Event) {
        when (event) {
            WifiMonitor.Event.RSSI -> {
                // Away from home: signal changes don't matter.
                if (lastRole == null) return
                // Strong and still strong: nothing to decide.
                val strong = settings.weakDbm + 10
                val now = monitor.connection.value?.rssiOrNull
                if (now != null && now >= strong && (lastRssi ?: -127) >= strong) return
                requestEvaluate()
                return
            }
            WifiMonitor.Event.SCAN_RESULTS -> {
                Diagnostics.count { it.copy(scanResultEvents = it.scanResultEvents + 1) }
                sampleForTuning()
            }
            WifiMonitor.Event.SCREEN_ON -> {
                refreshSystemState()
                monitor.refreshRssi()
            }
            WifiMonitor.Event.SCREEN_OFF -> screenOn = false
            WifiMonitor.Event.WIFI_STATE -> wifiOn = monitor.isWifiEnabled
            WifiMonitor.Event.LOCATION_MODE -> locationOn = Permissions.isLocationEnabled(this)
            WifiMonitor.Event.NETWORK -> {
                if (monitor.connection.value != null) wifiOn = true
            }
        }
        evaluate()
    }

    /** Coalesces bursts of signal events into at most one evaluation per [MIN_EVAL_GAP_MS]. */
    private fun requestEvaluate() {
        val wait = MIN_EVAL_GAP_MS - (now() - lastEvalAtMs)
        if (wait <= 0) {
            evaluate()
            return
        }
        if (pendingEval?.isActive == true) return
        pendingEval = scope.launch {
            delay(wait)
            evaluate()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun evaluate() {
        if (!running || !settingsLoaded || switchInProgress) return
        pendingEval?.cancel()
        Diagnostics.count { it.copy(evaluations = it.evaluations + 1) }
        val now = now()
        lastEvalAtMs = now

        if (!Permissions.hasPreciseLocation(this)) {
            return pauseWith(Status(StatusKind.PERMISSION_REQUIRED, "Permission required", "Precise location is needed to read Wi-Fi names and signal strength."))
        }
        if (!locationOn) {
            return pauseWith(Status(StatusKind.PERMISSION_REQUIRED, "Permission required", "Turn on Location. Android hides Wi-Fi names and scan results while it is off."))
        }
        if (!wifiOn) {
            return pauseWith(Status(StatusKind.WIFI_OFF, "Wi-Fi is off", "Monitoring resumes when Wi-Fi is turned back on."))
        }
        if (!settings.networksConfigured) {
            return pauseWith(Status(StatusKind.SERVICE_UNAVAILABLE, "Choose your networks", "Select two different networks for Upstairs and Downstairs."))
        }

        val conn = monitor.connection.value
        val role = settings.roleOf(conn?.ssid)
        if (role != lastRole) {
            Diagnostics.log(
                when {
                    role != null -> "On ${role.label} (${conn?.ssid})"
                    conn != null -> "On a network that isn't Upstairs/Downstairs"
                    else -> "Not connected to Wi-Fi"
                }
            )
            lastRole = role
            promptedFrom = null
            Notifications.cancelSwitchPrompt(this)
            nextScanGapMs = MIN_SCAN_GAP_MS
        }

        val rssi = effectiveRssi(conn)
        lastRssi = rssi

        if (inCall && settings.pauseDuringCalls && role != null) {
            tickJob?.cancel()
            setStatus(
                Status(
                    if (role == Role.UPSTAIRS) StatusKind.CONNECTED_UPSTAIRS else StatusKind.CONNECTED_DOWNSTAIRS,
                    "Connected to ${role.label}",
                    (rssi?.let { "$it dBm · " } ?: "") + "On a call: switching waits until it ends",
                )
            )
            return
        }
        // Only look at scan results when they could matter (cheap, but no need every few seconds).
        val candidate = if (role != null && rssi != null && rssi < settings.weakDbm) {
            monitor.bestReading(settings.ssidFor(role.other))
        } else null

        val decision = engine.evaluate(Snapshot(now, role, rssi, candidate))
        logDecision(decision)

        when (decision) {
            is Decision.Switch -> {
                startSwitch(decision, conn?.ssid)
                return
            }
            // Never request scans with the screen off; system scans are still used for free.
            is Decision.NeedScan -> if (screenOn) maybeScan(now, decision.confirming)
            is Decision.Idle -> nextScanGapMs = MIN_SCAN_GAP_MS
            else -> Unit
        }
        setStatus(statusFor(role, rssi, conn != null, decision, now))
        scheduleTick(role, rssi)
    }

    private fun pauseWith(status: Status) {
        tickJob?.cancel()
        setStatus(status)
    }

    /** With the screen off Android stops polling signal strength, so a newer scan entry wins. */
    private fun effectiveRssi(conn: WifiMonitor.Connection?): Int? {
        conn ?: return null
        if (screenOn) return conn.rssiOrNull
        val fromScan = conn.bssid?.let { monitor.readingForBssid(it) }
        return if (fromScan != null && fromScan.scanTimeMs > conn.rssiAtMs) fromScan.rssi else conn.rssiOrNull
    }

    private fun maybeScan(now: Long, confirming: Boolean) {
        while (scanTimes.isNotEmpty() && now - scanTimes.first() > SCAN_BUDGET_WINDOW_MS) scanTimes.removeFirst()
        val gap = if (confirming) MIN_SCAN_GAP_MS else nextScanGapMs
        val last = scanTimes.lastOrNull()
        if (last != null && now - last < gap) return
        if (scanTimes.size >= SCAN_BUDGET) return

        scanTimes.addLast(now)
        val accepted = monitor.requestScan()
        Diagnostics.count {
            it.copy(
                appScanRequests = it.appScanRequests + 1,
                appScansRejected = it.appScansRejected + if (accepted) 0 else 1,
            )
        }
        Diagnostics.log(if (accepted) "Requested a Wi-Fi scan" else "Scan throttled by Android; relying on system scans")
        if (!confirming) nextScanGapMs = (nextScanGapMs * 2).coerceAtMost(MAX_SCAN_GAP_MS)
    }

    private fun scheduleTick(role: Role?, rssi: Int?) {
        tickJob?.cancel()
        if (!screenOn || role == null) return   // dormant: events will wake us when something changes
        val weak = settings.weakDbm
        val interval = when {
            rssi == null -> 15_000L
            rssi >= weak + 10 -> 120_000L    // strong: safety net only
            rssi >= weak -> 30_000L          // getting close
            else -> 8_000L                   // weak: cached-value checks only, scans are budgeted
        }
        tickJob = scope.launch {
            delay(interval)
            Diagnostics.count { it.copy(timerChecks = it.timerChecks + 1) }
            monitor.refreshRssi()
            evaluate()
        }
    }

    private fun startSwitch(d: Decision.Switch, fromSsid: String?) {
        val targetSsid = settings.ssidFor(d.target)
        engine.onSwitchStarted()
        switchInProgress = true
        tickJob?.cancel()
        Diagnostics.log("${d.target.label} is stronger: ${d.targetRssi} dBm vs ${d.currentRssi} dBm")

        scope.launch {
            try {
                val automatic = connector.mode == NetworkConnector.Mode.AUTOMATIC
                if (automatic) {
                    Diagnostics.count { it.copy(switchAttempts = it.switchAttempts + 1) }
                    setStatus(Status(StatusKind.SWITCHING, "Switching…", "Connecting to ${d.target.label} ($targetSsid)"))
                }
                val outcome = connector.switchTo(d.target, targetSsid, d.currentRssi, d.targetRssi)
                when (outcome) {
                    NetworkConnector.Outcome.Prompted -> {
                        engine.onUserPrompted(now())
                        promptedFrom = d.target.other
                        Diagnostics.count { it.copy(userPrompts = it.userPrompts + 1) }
                        Diagnostics.log("Automatic switching unavailable (${ShizukuConnector.state.value.label}); notified you instead")
                    }
                    NetworkConnector.Outcome.NotSaved -> {
                        engine.onSwitchFailed(now(), permanent = true)
                        Diagnostics.count { it.copy(switchFailures = it.switchFailures + 1) }
                        Diagnostics.log("\"$targetSsid\" is not saved on this phone. Connect to it once in Settings.")
                    }
                    is NetworkConnector.Outcome.Failed -> {
                        engine.onSwitchFailed(now())
                        Diagnostics.count { it.copy(switchFailures = it.switchFailures + 1) }
                        Diagnostics.log("Switch request failed: ${outcome.reason}")
                        recoverIfDisconnected(fromSsid)
                    }
                    NetworkConnector.Outcome.Requested -> {
                        val ok = waitUntilWorking(targetSsid)
                        if (ok) {
                            engine.onSwitchSucceeded(now())
                            lastSwitchedAtMs = now()
                            Diagnostics.count { it.copy(switchSuccesses = it.switchSuccesses + 1) }
                            Diagnostics.log("Connected to ${d.target.label} and internet verified")
                        } else {
                            engine.onSwitchFailed(now())
                            Diagnostics.count { it.copy(switchFailures = it.switchFailures + 1) }
                            Diagnostics.log("No working connection on ${d.target.label} within ${VERIFY_TIMEOUT_MS / 1000}s (attempt ${engine.consecutiveFailures})")
                            recoverIfDisconnected(fromSsid)
                        }
                    }
                }
            } finally {
                switchInProgress = false
                nextScanGapMs = MIN_SCAN_GAP_MS
            }
            evaluate()
        }
    }

    /** True once the phone is on [ssid] with verified internet. */
    private suspend fun waitUntilWorking(ssid: String): Boolean =
        withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
            monitor.connection.first { it != null && it.ssid == ssid && it.validated }
        } != null

    /** "Switch now" from the app or tile: skip the thresholds, go to the other home network (needs Shizuku). */
    private fun switchNow() {
        if (!running || !settingsLoaded || switchInProgress || !settings.networksConfigured) return
        if (!ShizukuConnector.isReady) {
            Diagnostics.log("Switch now needs Shizuku (${ShizukuConnector.state.value.label})")
            return
        }
        val fromSsid = monitor.connection.value?.ssid
        val target = settings.roleOf(fromSsid)?.other ?: strongerVisible() ?: run {
            Diagnostics.log("Switch now: neither home network is in range")
            return
        }
        val targetSsid = settings.ssidFor(target)
        engine.onSwitchStarted()
        switchInProgress = true
        tickJob?.cancel()
        Notifications.cancelSwitchPrompt(this)
        Diagnostics.log("Switch now: moving to ${target.label}")

        scope.launch {
            try {
                Diagnostics.count { it.copy(switchAttempts = it.switchAttempts + 1) }
                setStatus(Status(StatusKind.SWITCHING, "Switching…", "Connecting to ${target.label} ($targetSsid)"))
                val ok = when (val r = ShizukuConnector.connectSaved(targetSsid)) {
                    is ShizukuConnector.ConnectResult.Requested -> waitUntilWorking(targetSsid)
                    is ShizukuConnector.ConnectResult.NotSaved -> {
                        Diagnostics.log("\"$targetSsid\" is not saved on this phone")
                        false
                    }
                    is ShizukuConnector.ConnectResult.Failed -> {
                        Diagnostics.log("Switch request failed: ${r.reason}")
                        false
                    }
                    ShizukuConnector.ConnectResult.Unavailable -> false
                }
                if (ok) {
                    engine.onSwitchSucceeded(now())
                    lastSwitchedAtMs = now()
                    Diagnostics.count { it.copy(switchSuccesses = it.switchSuccesses + 1) }
                    Diagnostics.log("Switched to ${target.label} and internet verified")
                } else {
                    // A manual attempt never counts toward the automatic failure limit.
                    engine.onSwitchAbandoned()
                    Diagnostics.count { it.copy(switchFailures = it.switchFailures + 1) }
                    recoverIfDisconnected(fromSsid)
                }
            } finally {
                switchInProgress = false
                nextScanGapMs = MIN_SCAN_GAP_MS
            }
            evaluate()
        }
    }

    /** When on neither home network, pick whichever one the last scan saw stronger. */
    private fun strongerVisible(): Role? {
        val up = monitor.bestReading(settings.upstairsSsid)
        val down = monitor.bestReading(settings.downstairsSsid)
        return when {
            up == null && down == null -> null
            down == null -> Role.UPSTAIRS
            up == null -> Role.DOWNSTAIRS
            else -> if (up.rssi >= down.rssi) Role.UPSTAIRS else Role.DOWNSTAIRS
        }
    }

    /** Never leave the phone without Wi-Fi because of us. Android usually reconnects by itself first. */
    private suspend fun recoverIfDisconnected(fromSsid: String?) {
        if (fromSsid == null) return
        delay(5_000)
        if (monitor.connection.value == null && monitor.isWifiEnabled) {
            Diagnostics.log("No Wi-Fi after failed switch; rejoining $fromSsid")
            connector.reconnect(fromSsid)
        }
    }

    private fun statusFor(role: Role?, rssi: Int?, onWifi: Boolean, decision: Decision, now: Long): Status {
        val signal = rssi?.let { "$it dBm" } ?: "signal unknown"
        val note = when (decision) {
            is Decision.Idle -> if (rssi != null && rssi >= settings.weakDbm) "Signal good" else decision.reason
            is Decision.Hold -> decision.reason
            is Decision.WeakNoBetter -> decision.reason
            is Decision.NeedScan -> decision.reason
            is Decision.Switch -> "Switching"
        }
        val mode = if (connector.mode == NetworkConnector.Mode.AUTOMATIC) "Automatic (Shizuku)" else "Assisted: one-tap notifications"
        return when {
            engine.isPaused -> Status(
                StatusKind.PAUSED, "Paused",
                "Switching failed ${engine.consecutiveFailures} times. Turn Auto Switch off and on to try again.",
            )
            role != null && promptedFrom == role -> Status(
                StatusKind.BETTER_AVAILABLE, "Stronger network available",
                "Tap the notification to move to ${role.other.label}. Automatic switching needs Shizuku.",
            )
            role != null && lastSwitchedAtMs >= 0 && now - lastSwitchedAtMs < SWITCHED_BANNER_MS -> Status(
                StatusKind.SWITCHED, "Switched successfully", "Now on ${role.label} · $signal",
            )
            role == Role.UPSTAIRS -> Status(StatusKind.CONNECTED_UPSTAIRS, "Connected to Upstairs", "$signal · $note\n$mode")
            role == Role.DOWNSTAIRS -> Status(StatusKind.CONNECTED_DOWNSTAIRS, "Connected to Downstairs", "$signal · $note\n$mode")
            else -> Status(
                StatusKind.MONITORING, "Monitoring",
                if (onWifi) "Connected to a different network; waiting." else "Not connected to Wi-Fi; waiting.",
            )
        }
    }

    private fun setStatus(status: Status) {
        AppState.status.value = status
        if (status.title != lastNotificationText) {
            lastNotificationText = status.title
            Notifications.updateMonitoring(this, status.title)
            Tiles.refresh(this)
        }
    }

    private fun logDecision(d: Decision) {
        val key = when (d) {
            is Decision.Switch -> "switch:${d.target}"
            is Decision.Hold -> "hold:" + d.reason.substringBefore(' ')
            is Decision.Idle -> "idle:" + d.reason.substringBefore(' ')
            is Decision.WeakNoBetter -> "weak"
            is Decision.NeedScan -> "scan:${d.confirming}"
        }
        if (key == lastDecisionKey) return
        lastDecisionKey = key
        val text = when (d) {
            is Decision.Idle -> d.reason
            is Decision.Hold -> d.reason
            is Decision.WeakNoBetter -> d.reason
            is Decision.NeedScan -> d.reason
            is Decision.Switch -> "Decision: switch to ${d.target.label}"
        }
        Diagnostics.log(text)
    }
}
