package app.wifiautoswitch

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.wifiautoswitch.shizuku.ShizukuConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Quick Settings tile: switch to the other home network right now. */
class SwitchNowTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        listening?.cancel()
        listening = scope.launch {
            combine(AppState.status, AppState.serviceRunning) { status, running -> status to running }
                .collect { (status, running) -> render(status, running) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(status: Status, running: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Switch Wi-Fi"
        tile.subtitle = when {
            !running -> "Auto Switch off"
            status.kind == StatusKind.CONNECTED_UPSTAIRS -> "To Downstairs"
            status.kind == StatusKind.CONNECTED_DOWNSTAIRS -> "To Upstairs"
            else -> Tiles.shortStatus(status)
        }
        // Greyed out only while a switch is already happening; otherwise tappable.
        tile.state = if (running && status.kind == StatusKind.SWITCHING) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        if (!AppState.serviceRunning.value) {
            openAndCollapse(Intent(applicationContext, MainActivity::class.java))
            return
        }
        if (!ShizukuConnector.isReady) {
            // Without Shizuku Android won't let the app switch: open the Wi-Fi panel instead.
            openAndCollapse(Intent(AndroidSettings.Panel.ACTION_WIFI))
            return
        }
        AppState.switchNowRequests.tryEmit(Unit)
    }
}
