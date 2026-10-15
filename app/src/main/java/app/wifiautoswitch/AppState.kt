package app.wifiautoswitch

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class StatusKind {
    OFF, MONITORING, CONNECTED_UPSTAIRS, CONNECTED_DOWNSTAIRS, SWITCHING, SWITCHED,
    BETTER_AVAILABLE, PERMISSION_REQUIRED, SERVICE_UNAVAILABLE, WIFI_OFF, PAUSED,
}

data class Status(val kind: StatusKind, val title: String, val detail: String = "")

/** Process-wide state shared between the monitoring service and the UI (same process). */
object AppState {
    val status = MutableStateFlow(Status(StatusKind.OFF, "Auto Switch is off"))
    val serviceRunning = MutableStateFlow(false)

    /** "Switch now" taps from the app or the Quick Settings tile; handled by the running service. */
    val switchNowRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/**
 * Lightweight diagnostics: counters and a short in-memory log, visible under Advanced Settings.
 * Nothing leaves the device and no passwords are ever handled. Logcat output only in debug builds.
 */
object Diagnostics {
    data class Counters(
        val evaluations: Long = 0,
        val timerChecks: Long = 0,
        val appScanRequests: Long = 0,
        val appScansRejected: Long = 0,
        val scanResultEvents: Long = 0,
        val switchAttempts: Long = 0,
        val switchSuccesses: Long = 0,
        val switchFailures: Long = 0,
        val userPrompts: Long = 0,
        val sinceMillis: Long = System.currentTimeMillis(),
    )

    private const val TAG = "WifiAutoSwitch"
    private const val MAX_LINES = 200
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _counters = MutableStateFlow(Counters())
    val counters: StateFlow<Counters> = _counters.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun count(change: (Counters) -> Counters) = _counters.update(change)

    fun log(message: String) {
        val line = "${LocalTime.now().format(timeFormat)}  $message"
        _log.update { (it + line).takeLast(MAX_LINES) }
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun clear() {
        _log.value = emptyList()
        _counters.value = Counters()
    }
}
