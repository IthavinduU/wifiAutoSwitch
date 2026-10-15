package app.wifiautoswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.wifiautoswitch.core.ScanReading
import app.wifiautoswitch.core.Ssid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the Wi-Fi connection without polling the radio:
 *  - ConnectivityManager.NetworkCallback (with location info) for which network we're on + validation
 *  - RSSI_CHANGED broadcasts for signal updates (sent while the screen is on)
 *  - ScanResultsCallback to reuse every scan the *system* does, at no extra cost
 *  - Screen on/off, Wi-Fi on/off and Location on/off broadcasts
 *
 * Everything is delivered on the main thread. Reading cached scan results never triggers a scan;
 * only [requestScan] does, and the service rate-limits that.
 */
class WifiMonitor(context: Context, private val onEvent: (Event) -> Unit) {

    enum class Event { NETWORK, RSSI, SCAN_RESULTS, SCREEN_ON, SCREEN_OFF, WIFI_STATE, LOCATION_MODE }

    data class Connection(
        val network: Network,
        val ssid: String?,
        val bssid: String?,
        val rssi: Int,
        val validated: Boolean,
        val frequencyMhz: Int,
        /** Elapsed-realtime when [rssi] was last known to be current. */
        val rssiAtMs: Long,
    ) {
        val rssiOrNull: Int? get() = rssi.takeIf { it in -126..0 }
    }

    companion object {
        const val INVALID_RSSI = -127
        private const val REDACTED_BSSID = "02:00:00:00:00:00"
        /** Entries for the same SSID within this span of the newest one belong to the same scan. */
        private const val SAME_SCAN_SPAN_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val networks = LinkedHashMap<Network, Connection>()
    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    private var started = false

    val isScreenOn: Boolean get() = power?.isInteractive ?: true
    val isWifiEnabled: Boolean get() = wifi?.isWifiEnabled == true

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiInfo
                networks[network] = Connection(
                    network = network,
                    ssid = Ssid.normalize(info?.ssid),
                    bssid = info?.bssid?.takeUnless { it == REDACTED_BSSID },
                    rssi = info?.rssi ?: INVALID_RSSI,
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    frequencyMhz = info?.frequency ?: 0,
                    rssiAtMs = SystemClock.elapsedRealtime(),
                )
                publish()
                onEvent(Event.NETWORK)
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                publish()
                onEvent(Event.NETWORK)
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.RSSI_CHANGED_ACTION -> {
                    updateRssi(intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, INVALID_RSSI))
                    onEvent(Event.RSSI)
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> onEvent(Event.WIFI_STATE)
                Intent.ACTION_SCREEN_ON -> onEvent(Event.SCREEN_ON)
                Intent.ACTION_SCREEN_OFF -> onEvent(Event.SCREEN_OFF)
                LocationManager.MODE_CHANGED_ACTION -> onEvent(Event.LOCATION_MODE)
            }
        }
    }

    private val scanCallback = object : WifiManager.ScanResultsCallback() {
        override fun onScanResultsAvailable() = onEvent(Event.SCAN_RESULTS)
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity?.registerNetworkCallback(request, networkCallback, mainHandler)
        }.onFailure { Diagnostics.log("Network callback unavailable: ${it.message}") }

        val filter = IntentFilter().apply {
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        runCatching { wifi?.registerScanResultsCallback(ContextCompat.getMainExecutor(appContext), scanCallback) }
            .onFailure { Diagnostics.log("Scan-results callback unavailable: ${it.message}") }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        runCatching { appContext.unregisterReceiver(receiver) }
        runCatching { wifi?.unregisterScanResultsCallback(scanCallback) }
        networks.clear()
        _connection.value = null
    }

    /**
     * Re-reads the framework's cached signal value (no radio activity). Only meaningful while the
     * screen is on, because Android stops polling signal strength when the screen is off.
     */
    @Suppress("DEPRECATION")
    fun refreshRssi() {
        val current = _connection.value ?: return
        val info = runCatching { wifi?.connectionInfo }.getOrNull() ?: return
        if (Ssid.normalize(info.ssid) == current.ssid) updateRssi(info.rssi)
    }

    private fun updateRssi(rssi: Int) {
        if (rssi !in -126..0) return
        val current = _connection.value ?: return
        networks[current.network] = current.copy(rssi = rssi, rssiAtMs = SystemClock.elapsedRealtime())
        publish()
    }

    private fun publish() {
        _connection.value = networks.values.firstOrNull { it.validated } ?: networks.values.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun cachedResults(): List<ScanResult> =
        runCatching { wifi?.scanResults.orEmpty() }.getOrDefault(emptyList())

    /** Strongest access point (any band) for [ssid] from the most recent scan that saw it. */
    @Suppress("DEPRECATION")
    fun bestReading(ssid: String): ScanReading? {
        val matches = cachedResults().filter { Ssid.normalize(it.SSID) == ssid }
        if (matches.isEmpty()) return null
        val newestMs = matches.maxOf { it.timestamp } / 1000
        return matches
            .filter { newestMs - it.timestamp / 1000 <= SAME_SCAN_SPAN_MS }
            .maxByOrNull { it.level }
            ?.let { ScanReading(it.level, it.timestamp / 1000) }
    }

    /** Scan entry for the access point we are connected to (fresher than WifiInfo when the screen is off). */
    @Suppress("DEPRECATION")
    fun readingForBssid(bssid: String): ScanReading? =
        cachedResults().firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
            ?.let { ScanReading(it.level, it.timestamp / 1000) }

    /** Nearby network names, strongest first (for the network picker). */
    @Suppress("DEPRECATION")
    fun visibleSsids(): List<String> =
        cachedResults().sortedByDescending { it.level }.mapNotNull { Ssid.normalize(it.SSID) }.distinct()

    /** Asks for a fresh scan. Returns false if Android refused or throttled it. */
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = runCatching { wifi?.startScan() == true }.getOrDefault(false)
}
