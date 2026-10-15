package app.wifiautoswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * After a reboot: if the user left Auto Switch on and "Resume after restart" enabled, start again.
 *
 * Android only lets a location-type foreground service start from the background (boot counts as
 * background) when the app has "Allow all the time" location. Without it we post a notification;
 * one tap reopens the app and monitoring resumes. Nothing is bypassed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = SettingsRepository(app).settings.first()
                if (!s.autoSwitch || !s.resumeAfterReboot || !s.networksConfigured) return@launch
                val canStartDirectly = Permissions.hasPreciseLocation(app) && Permissions.hasBackgroundLocation(app)
                if (canStartDirectly) {
                    try {
                        MonitoringService.start(app)
                        Diagnostics.log("Resumed after restart")
                    } catch (e: Exception) {
                        Notifications.showResumePrompt(app)
                    }
                } else {
                    Notifications.showResumePrompt(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
