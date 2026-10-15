package app.wifiautoswitch

import android.content.Context
import app.wifiautoswitch.core.Role
import app.wifiautoswitch.shizuku.ShizukuConnector

/**
 * The only place that tries to change the Wi-Fi network.
 *
 * Android 10+ does not allow an ordinary app to make the phone join a saved network:
 *  - WifiManager.enableNetwork()/reconnect() always fail for apps targeting Android 10+.
 *  - WifiNetworkSuggestion only *suggests*; Android's own selection logic decides, and it needs
 *    the password re-entered in the app. It won't reliably move you between two good networks.
 *  - WifiNetworkSpecifier creates an app-only, local connection — not the phone's internet Wi-Fi.
 *
 * So there are two honest modes:
 *  AUTOMATIC — Shizuku is running and authorised: join the saved network directly.
 *  ASSISTED  — no Shizuku: post a one-tap notification that opens the Wi-Fi panel.
 *              This is reported as "stronger network available", never as a successful switch.
 */
class NetworkConnector(private val context: Context) {

    enum class Mode { AUTOMATIC, ASSISTED }

    sealed interface Outcome {
        /** The system accepted the request; the caller must still verify the connection. */
        data object Requested : Outcome
        /** User was notified to switch manually. */
        data object Prompted : Outcome
        /** The SSID isn't saved on the phone — retrying can't help. */
        data object NotSaved : Outcome
        data class Failed(val reason: String) : Outcome
    }

    val mode: Mode get() = if (ShizukuConnector.isReady) Mode.AUTOMATIC else Mode.ASSISTED

    suspend fun switchTo(target: Role, ssid: String, fromRssi: Int, toRssi: Int): Outcome {
        if (mode == Mode.AUTOMATIC) {
            when (val r = ShizukuConnector.connectSaved(ssid)) {
                is ShizukuConnector.ConnectResult.Requested -> return Outcome.Requested
                is ShizukuConnector.ConnectResult.NotSaved -> return Outcome.NotSaved
                is ShizukuConnector.ConnectResult.Failed -> return Outcome.Failed(r.reason)
                is ShizukuConnector.ConnectResult.Unavailable -> Unit // fall through to assisted
            }
        }
        Notifications.showSwitchPrompt(context, target.label, ssid, fromRssi, toRssi)
        return Outcome.Prompted
    }

    /** Best-effort recovery after a failed switch left us without Wi-Fi. */
    suspend fun reconnect(ssid: String): Boolean =
        mode == Mode.AUTOMATIC && ShizukuConnector.connectSaved(ssid) is ShizukuConnector.ConnectResult.Requested
}
