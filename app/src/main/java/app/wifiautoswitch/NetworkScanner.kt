package app.wifiautoswitch

import android.content.Context
import android.net.Network
import app.wifiautoswitch.core.Ipv4
import app.wifiautoswitch.core.KnownDevices
import app.wifiautoswitch.core.MacInfo
import app.wifiautoswitch.core.NeighborTable
import app.wifiautoswitch.shizuku.ShizukuConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * "Who's on my Wi-Fi" — runs only when the user opens the screen or taps Scan.
 *
 * 1. Sends one tiny UDP packet to every address on the current Wi-Fi subnet (twice). This makes the
 *    phone ask "who has this address?" at the network level; every device that's present answers,
 *    even ones that ignore normal traffic.
 * 2. Reads the resulting neighbour table through Shizuku (Android hides MAC addresses from apps).
 * 3. Asks the router's DNS for device names, and flags MACs never seen before on this network.
 *
 * Total network traffic is a few KB; nothing runs in the background.
 */
class NetworkScanner(context: Context, private val repo: SettingsRepository) {

    data class Device(
        val ip: String,
        val mac: String?,
        val name: String?,
        val isRouter: Boolean,
        val isThisPhone: Boolean,
        val isNew: Boolean,
        val active: Boolean,
    ) {
        val privateMac: Boolean get() = mac != null && MacInfo.isPrivate(mac)
    }

    sealed interface Result {
        data class Ok(val networkName: String?, val gateway: String?, val devices: List<Device>) : Result
        data class Failed(val message: String) : Result
    }

    private val app = context.applicationContext

    /** Call from the main thread (Shizuku binding requires it). */
    suspend fun scan(): Result {
        if (!ShizukuConnector.isReady) {
            return Result.Failed("This needs Shizuku: ${ShizukuConnector.state.value.label}.")
        }
        val link = WifiLink.current(app) ?: return Result.Failed("Connect to a Wi-Fi network first.")
        val targets = Ipv4.scanTargets(link.ownIp, link.prefix)

        withContext(Dispatchers.IO) {
            sweep(link.network, targets)
            delay(1_500)
            sweep(link.network, targets) // second pass catches devices that were slow to wake
            delay(2_500)
        }

        val raw = ShizukuConnector.listNeighbors()
            ?: return Result.Failed("Couldn't read the device list through Shizuku. Try again.")
        if (raw.startsWith("error:")) {
            Diagnostics.log("Device scan failed: ${raw.removePrefix("error:")}")
            return Result.Failed("Android refused to share the device list on this version.")
        }

        val neighbors = NeighborTable.parse(raw, link.iface)
        val names = lookupNames(neighbors.map { it.ip })
        val networkName = WifiLink.currentSsid(app)
        val key = networkName ?: link.gateway ?: "unknown"

        val known = KnownDevices.parse(repo.loadKnownDevices())
        val macs = neighbors.map { it.mac }.toSet()
        val newMacs = KnownDevices.newOnes(known, key, macs)
        repo.saveKnownDevices(KnownDevices.serialize(KnownDevices.remember(known, key, macs)))

        val ownIp = Ipv4.toStr(link.ownIp)
        val devices = neighbors.map { n ->
            Device(
                ip = n.ip,
                mac = n.mac,
                name = names[n.ip],
                isRouter = n.ip == link.gateway,
                isThisPhone = false,
                isNew = n.mac in newMacs,
                active = n.state == "REACHABLE",
            )
        } + Device(ownIp, null, null, isRouter = false, isThisPhone = true, isNew = false, active = true)

        val sorted = devices.sortedWith(
            compareBy<Device>({ !it.isRouter }, { !it.isThisPhone }, { Ipv4.sortKey(it.ip) })
        )
        Diagnostics.log("Device scan: ${sorted.size} devices, ${newMacs.size} new")
        return Result.Ok(networkName, link.gateway, sorted)
    }

    private fun sweep(network: Network, targets: List<Int>) {
        runCatching {
            DatagramSocket().use { socket ->
                network.bindSocket(socket) // stay on Wi-Fi even if mobile data is the default network
                val payload = ByteArray(1)
                for (t in targets) {
                    runCatching { socket.send(DatagramPacket(payload, 1, InetAddress.getByAddress(Ipv4.bytes(t)), 9)) }
                }
            }
        }.onFailure { Diagnostics.log("Device scan sweep failed: ${it.message}") }
    }

    /** Reverse DNS through the router (it usually knows DHCP names). Slow lookups are simply skipped. */
    private suspend fun lookupNames(ips: List<String>): Map<String, String> {
        val lookups = ips.map { ip ->
            ip to CoroutineScope(Dispatchers.IO).async { runCatching { resolveName(ip) }.getOrNull() }
        }
        val deadline = System.currentTimeMillis() + 2_500 // one shared deadline for all lookups
        return lookups.mapNotNull { (ip, job) ->
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            withTimeoutOrNull(remaining) { job.await() }?.let { ip to it }
        }.toMap()
    }

    private fun resolveName(ip: String): String? {
        val v = Ipv4.toInt(ip) ?: return null
        val host = InetAddress.getByAddress(Ipv4.bytes(v)).canonicalHostName ?: return null
        if (host == ip || Ipv4.toInt(host) != null) return null
        return host.substringBefore('.').ifBlank { null }
    }
}
