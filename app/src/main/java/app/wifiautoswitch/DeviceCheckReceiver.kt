package app.wifiautoswitch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import app.wifiautoswitch.shizuku.ShizukuConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * New device alert. Woken by an inexact alarm every ~15 minutes, which the monitoring service only
 * sets while the phone is charging. Runs one quick "Who's on my Wi-Fi" scan on a home network and
 * notifies about MAC addresses never seen there before.
 */
class DeviceCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION = "app.wifiautoswitch.action.CHECK_DEVICES"

        fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 30,
            Intent(ctx, DeviceCheckReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun isCharging(ctx: Context): Boolean =
            ctx.getSystemService(BatteryManager::class.java)?.isCharging == true
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                withTimeoutOrNull(40_000) { check(app) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun check(app: Context) {
        if (!AppState.serviceRunning.value || !isCharging(app)) return
        val repo = SettingsRepository(app)
        val s = repo.settings.first()
        if (!s.newDeviceAlerts || !s.networksConfigured) return
        val role = s.roleOf(WifiLink.currentSsid(app)) ?: return // home networks only
        ShizukuConnector.init(app)
        if (!ShizukuConnector.isReady) return

        val result = NetworkScanner(app, repo).scan() as? NetworkScanner.Result.Ok ?: return
        val newOnes = result.devices.filter { it.isNew }
        if (newOnes.isEmpty()) return
        Notifications.showNewDevices(
            app, role.label,
            newOnes.map { d -> (d.name ?: "Unknown device") + " (" + d.ip + (d.mac?.let { " · ${it.uppercase()}" } ?: "") + ")" },
        )
        Diagnostics.log("New device alert: ${newOnes.size} new on ${role.label}")
    }
}
