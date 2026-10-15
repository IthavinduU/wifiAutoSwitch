package app.wifiautoswitch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import app.wifiautoswitch.core.Ipv4
import app.wifiautoswitch.core.Ssid
import java.net.Inet4Address

/** The phone's current Wi-Fi network: interface, own IPv4 address/prefix and the router (gateway). */
data class WifiLink(
    val network: Network,
    val iface: String?,
    val ownIp: Int,
    val prefix: Int,
    val gateway: String?,
) {
    companion object {
        @Suppress("DEPRECATION")
        fun current(context: Context): WifiLink? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null
            val lp = cm.getLinkProperties(network) ?: return null
            val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
            val own = v4.address.hostAddress?.let { Ipv4.toInt(it) } ?: return null
            val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            return WifiLink(network, lp.interfaceName, own, v4.prefixLength, gateway)
        }

        @Suppress("DEPRECATION")
        fun currentSsid(context: Context): String? = runCatching {
            Ssid.normalize(context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
        }.getOrNull()
    }
}
