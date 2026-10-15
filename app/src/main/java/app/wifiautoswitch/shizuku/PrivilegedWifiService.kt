package app.wifiautoswitch.shizuku

import android.net.wifi.WifiConfiguration
import android.os.Bundle
import android.os.IBinder
import app.wifiautoswitch.core.Ssid
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/**
 * Shizuku "user service". Shizuku starts this class in a separate process that runs with the
 * shell (adb) identity, which holds android.permission.NETWORK_SETTINGS. That is the permission
 * the Settings app uses to join a saved network, and it is what an ordinary app cannot have.
 *
 * What it does — and nothing else:
 *  - reads the list of saved networks (SSID + internal id only; passwords are never read or returned)
 *  - asks the Wi-Fi service to join a saved network by its id, i.e. exactly what tapping the
 *    network in Settings does. The saved profile (password, MAC randomisation, metered, auto-join)
 *    is not recreated or modified.
 *
 * IWifiManager is a hidden framework interface whose method signatures change between Android
 * releases, so methods are found by name and arguments are filled by parameter type. Hidden-API
 * restrictions do not apply inside this app_process-based process. This has been used by other
 * Shizuku apps on Android 12–16; Android 17 behaviour must be confirmed on the device.
 */
@Suppress("DEPRECATION")
class PrivilegedWifiService : IPrivilegedWifi.Stub() {

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        val INT: Class<*> = Int::class.javaPrimitiveType!!
        val BOOL: Class<*> = Boolean::class.javaPrimitiveType!!
        val LONG: Class<*> = Long::class.javaPrimitiveType!!
    }

    private val wifiService: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "wifi") as? IBinder
            ?: error("Wi-Fi service not found")
        Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: error("IWifiManager unavailable")
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun listSavedSsids(): String = try {
        savedNetworks().mapNotNull { Ssid.normalize(it.SSID) }.distinct().sorted().joinToString("\n")
    } catch (t: Throwable) {
        ""
    }

    override fun connectSaved(ssid: String?): String {
        val wanted = ssid?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return "error:invalid network name"
        return try {
            val config = savedNetworks().firstOrNull { Ssid.normalize(it.SSID) == wanted } ?: return "notfound"
            val id = config.networkId
            // Null config + saved id = "connect to this saved network as-is" (same as Settings).
            call("connect", mapOf<Class<*>, Any?>(WifiConfiguration::class.java to null, INT to id))
            "ok:$id"
        } catch (t: Throwable) {
            val root = (t as? InvocationTargetException)?.targetException ?: t
            "error:${root.javaClass.simpleName}: ${root.message}".take(300)
        }
    }

    private fun savedNetworks(): List<WifiConfiguration> {
        val slice = call("getConfiguredNetworks", mapOf<Class<*>, Any?>(BOOL to false)) ?: return emptyList()
        val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return emptyList()
        return list.filterIsInstance<WifiConfiguration>()
    }

    /** Invokes the longest overload of [name], filling known types and neutral defaults for the rest. */
    private fun call(name: String, values: Map<Class<*>, Any?>): Any? {
        val method = wifiService.javaClass.methods
            .filter { it.name == name }
            .maxByOrNull { it.parameterCount }
            ?: error("IWifiManager.$name is not available on this Android version")
        var stringsUsed = 0
        val args = method.parameterTypes.map { type ->
            when {
                values.containsKey(type) -> values[type]
                type == String::class.java -> if (stringsUsed++ == 0) SHELL_PACKAGE else null
                type == Bundle::class.java -> Bundle()
                type == INT -> 0
                type == BOOL -> false
                type == LONG -> 0L
                else -> null // listeners/callbacks: null is accepted by the framework
            }
        }
        return method.invoke(wifiService, *args.toTypedArray())
    }
}
