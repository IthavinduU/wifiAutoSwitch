package app.wifiautoswitch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

object Tiles {
    /** Asks Android to refresh our tiles (cheap; only called when the status title changes). */
    fun refresh(ctx: Context) {
        for (cls in listOf(AutoSwitchTileService::class.java, SwitchNowTileService::class.java)) {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, cls)) }
        }
    }

    fun shortStatus(status: Status): String = when (status.kind) {
        StatusKind.CONNECTED_UPSTAIRS -> "Upstairs"
        StatusKind.CONNECTED_DOWNSTAIRS -> "Downstairs"
        StatusKind.SWITCHING -> "Switching…"
        StatusKind.SWITCHED -> "Switched"
        StatusKind.BETTER_AVAILABLE -> "Tap notification"
        StatusKind.WIFI_OFF -> "Wi-Fi off"
        StatusKind.PERMISSION_REQUIRED -> "Needs permission"
        StatusKind.PAUSED -> "Paused"
        else -> status.title
    }
}

/** Opens an activity from a tile and collapses the panel (Android 14+ requires a PendingIntent). */
@SuppressLint("StartActivityAndCollapseDeprecated")
fun TileService.openAndCollapse(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
