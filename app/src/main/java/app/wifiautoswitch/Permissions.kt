package app.wifiautoswitch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Why location? Android treats Wi-Fi names and scan results as location data, so reading
 * "which network am I on" and "how strong is the other one" requires precise location access
 * and the system Location switch to be on. The app never requests GPS fixes.
 */
object Permissions {
    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasPreciseLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasApproximateOnly(ctx: Context) =
        !hasPreciseLocation(ctx) && granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Only needed to restart monitoring automatically after a reboot without a tap. */
    fun hasBackgroundLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun isLocationEnabled(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }.getOrDefault(false)

    /** Permissions requested when the user turns Auto Switch on. */
    fun initialRequest(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
