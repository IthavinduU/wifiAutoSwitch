package app.wifiautoswitch.core

/** IPv4 helpers using a 32-bit Int (192.168.x.x is negative as a signed Int; ranges still work). */
object Ipv4 {
    fun toInt(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var result = 0
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            result = (result shl 8) or v
        }
        return result
    }

    fun toStr(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    fun bytes(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** For sorting: 10.0.0.2 < 192.168.1.5 regardless of sign. */
    fun sortKey(ip: String): Long = (toInt(ip)?.toLong() ?: 0L) and 0xFFFFFFFFL

    /**
     * Addresses to probe on our subnet, excluding our own. Home networks are /24 (254 hosts);
     * anything bigger is limited to the /24 around us so a scan stays quick and light.
     */
    fun scanTargets(own: Int, prefixLength: Int): List<Int> {
        val p = if (prefixLength < 24) 24 else prefixLength.coerceAtMost(30)
        val mask = -1 shl (32 - p)
        val network = own and mask
        val broadcast = network or mask.inv()
        return ((network + 1) until broadcast).filter { it != own }
    }
}

/** One device the phone has heard from on the local network. */
data class Neighbor(val ip: String, val mac: String, val state: String)

/**
 * Parses the kernel's neighbour (ARP) table as printed by `ip -4 neigh show`, e.g.
 * `192.168.1.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff router REACHABLE`.
 * Entries without a MAC (FAILED/INCOMPLETE) mean nothing answered at that address.
 */
object NeighborTable {
    private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    private val PRESENT = setOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT")

    fun parse(output: String, iface: String?): List<Neighbor> = output.lineSequence()
        .mapNotNull { line -> parseLine(line, iface) }
        .distinctBy { it.ip }
        .toList()

    private fun parseLine(line: String, iface: String?): Neighbor? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.size < 4) return null
        val ip = t[0]
        if (Ipv4.toInt(ip) == null) return null
        val dev = t.indexOf("dev").takeIf { it >= 0 }?.let { t.getOrNull(it + 1) }
        if (iface != null && dev != iface) return null
        val mac = t.indexOf("lladdr").takeIf { it >= 0 }?.let { t.getOrNull(it + 1) }?.lowercase() ?: return null
        if (!MAC.matches(mac) || mac == "00:00:00:00:00:00") return null
        val state = t.last().uppercase()
        if (state !in PRESENT) return null
        return Neighbor(ip, mac, state)
    }
}

object MacInfo {
    /**
     * "Locally administered" bit set = a randomised/private address. Modern phones, tablets and
     * laptops use one per Wi-Fi network instead of their real hardware address.
     */
    fun isPrivate(mac: String): Boolean {
        val first = mac.take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }
}

/** MAC addresses already seen on each network, to flag new devices. Stored as plain text lines. */
object KnownDevices {
    fun parse(data: String): Map<String, Set<String>> = data.lineSequence()
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null
            else line.substring(0, tab) to line.substring(tab + 1).split(',').filter { it.isNotBlank() }.toSet()
        }
        .toMap()

    fun serialize(map: Map<String, Set<String>>): String = map.entries.joinToString("\n") { (network, macs) ->
        clean(network) + "\t" + macs.sorted().joinToString(",")
    }

    fun clean(network: String): String = network.replace('\t', ' ').replace('\n', ' ')

    /** New = not seen before on this network. The very first scan of a network marks nothing as new. */
    fun newOnes(known: Map<String, Set<String>>, network: String, current: Set<String>): Set<String> {
        val before = known[clean(network)] ?: return emptySet()
        return current - before
    }

    fun remember(known: Map<String, Set<String>>, network: String, current: Set<String>): Map<String, Set<String>> {
        val key = clean(network)
        return known + (key to (known[key].orEmpty() + current))
    }
}
