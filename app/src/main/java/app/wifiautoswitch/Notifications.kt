package app.wifiautoswitch

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.wifiautoswitch.shizuku.ShizukuConnector

object Notifications {
    const val MONITOR_ID = 1
    private const val PROMPT_ID = 2
    private const val RESUME_ID = 3
    private const val SHIZUKU_ID = 4
    private const val NEW_DEVICE_ID = 5
    private const val CH_MONITOR = "monitor"
    private const val CH_ALERTS = "alerts"
    // New channel id: Android never lets an app raise an existing channel's importance,
    // so the pop-up behaviour needs a fresh channel.
    private const val CH_SWITCH = "switch_popup"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Background monitoring", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required by Android while Auto Switch watches your Wi-Fi. Silent."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "For example, resuming Auto Switch after a restart."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SWITCH, "Switch suggestions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pops up when a stronger home network is available."
            }
        )
    }

    private fun openAppIntent(ctx: Context, action: String? = null): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(action ?: Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun monitoring(ctx: Context, text: String): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Auto Switch is on")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent(ctx))
            .build()
    }

    @SuppressLint("MissingPermission") // checked via canPost()
    fun updateMonitoring(ctx: Context, text: String) {
        if (canPost(ctx)) NotificationManagerCompat.from(ctx).notify(MONITOR_ID, monitoring(ctx, text))
    }

    /**
     * Assisted mode: pops up on screen with a "Switch" button.
     * Android doesn't let apps change the network without Shizuku, so the button opens the
     * Wi-Fi panel over the current screen and the user taps the network there.
     */
    @SuppressLint("MissingPermission")
    fun showSwitchPrompt(
        ctx: Context, targetLabel: String, targetSsid: String, fromRssi: Int, toRssi: Int, reason: String? = null,
    ) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val panel = PendingIntent.getActivity(
            ctx, 10,
            Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_SWITCH)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(reason ?: "$targetLabel Wi-Fi is stronger")
            .setContentText(
                if (reason != null) "Tap Switch, then choose \"$targetSsid\""
                else "Tap Switch, then choose \"$targetSsid\" ($toRssi dBm vs $fromRssi dBm now)"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(5 * 60_000L)
            .setContentIntent(panel)
            .addAction(R.drawable.ic_stat_wifi, "Switch", panel)
            .build()
        NotificationManagerCompat.from(ctx).notify(PROMPT_ID, n)
    }

    fun cancelSwitchPrompt(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(PROMPT_ID)

    @SuppressLint("MissingPermission")
    fun showResumePrompt(ctx: Context) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Resume Wi-Fi Auto Switch")
            .setContentText("Tap to start monitoring again after restart")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, MainActivity.ACTION_RESUME))
            .build()
        NotificationManagerCompat.from(ctx).notify(RESUME_ID, n)
    }

    fun cancelResumePrompt(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(RESUME_ID)

    /** Shown when Shizuku stops after it had been working; tapping opens Shizuku to restart it. */
    @SuppressLint("MissingPermission")
    fun showShizukuStopped(ctx: Context, reason: String) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val launch = ctx.packageManager.getLaunchIntentForPackage(ShizukuConnector.SHIZUKU_PACKAGE)
            ?: Intent(ctx, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            ctx, 20, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Automatic switching paused")
            .setContentText("$reason. Tap to open Shizuku and press Start.")
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(ctx).notify(SHIZUKU_ID, n)
    }

    fun cancelShizukuStopped(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(SHIZUKU_ID)

    /** Unknown device(s) joined the home Wi-Fi. Tapping opens the "Who's on my Wi-Fi" screen. */
    @SuppressLint("MissingPermission")
    fun showNewDevices(ctx: Context, networkLabel: String, devices: List<String>) {
        if (!canPost(ctx) || devices.isEmpty()) return
        ensureChannels(ctx)
        val title = if (devices.size == 1) "New device on $networkLabel Wi-Fi" else "${devices.size} new devices on $networkLabel Wi-Fi"
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(title)
            .setContentText(devices.joinToString(", "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(devices.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, MainActivity.ACTION_OPEN_DEVICES))
            .build()
        NotificationManagerCompat.from(ctx).notify(NEW_DEVICE_ID, n)
    }

    private fun canPost(ctx: Context) =
        Permissions.hasNotifications(ctx) && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}
