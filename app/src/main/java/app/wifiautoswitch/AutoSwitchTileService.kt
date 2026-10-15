package app.wifiautoswitch

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: tap to turn Auto Switch on/off. Subtitle shows the current floor.
 * Only runs while the Quick Settings panel is open.
 */
class AutoSwitchTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: kotlinx.coroutines.Job? = null

    override fun onStartListening() {
        val repo = SettingsRepository(applicationContext)
        listening?.cancel()
        listening = scope.launch {
            combine(repo.settings, AppState.status, AppState.serviceRunning) { s, status, running ->
                Triple(s, status, running)
            }.collect { (s, status, running) -> render(s, status, running) }
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

    private fun render(s: Settings, status: Status, running: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Auto Switch"
        tile.state = if (s.autoSwitch) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            !s.autoSwitch -> "Off"
            !running -> "Tap to start"
            else -> Tiles.shortStatus(status)
        }
        tile.updateTile()
    }

    override fun onClick() {
        val app = applicationContext
        scope.launch {
            val repo = SettingsRepository(app)
            val s = repo.settings.first()
            if (s.autoSwitch) {
                repo.update { it.copy(autoSwitch = false) }
                MonitoringService.stop(app)
                return@launch
            }
            if (!s.networksConfigured || !Permissions.hasPreciseLocation(app)) {
                openAndCollapse(Intent(app, MainActivity::class.java))
                return@launch
            }
            repo.update { it.copy(autoSwitch = true) }
            // Android only lets a location service start from here with "Allow all the time".
            // Otherwise opening the app starts it (it resumes monitoring automatically).
            val started = Permissions.hasBackgroundLocation(app) &&
                runCatching { MonitoringService.start(app) }.isSuccess
            if (!started) openAndCollapse(Intent(app, MainActivity::class.java))
        }
    }
}
