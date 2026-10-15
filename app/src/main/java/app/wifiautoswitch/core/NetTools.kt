package app.wifiautoswitch.core

// ---------------------------------------------------------------- Internet outages

data class Outage(val startMs: Long, val endMs: Long, val network: String) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Detects "connected to home Wi-Fi but no internet" periods. Feed it every change (wall-clock times).
 * Short blips (under [confirmMs]) and the few seconds Android needs to check a fresh connection are
 * ignored. An outage ends when internet returns or the phone leaves that network.
 *
 * Note: a full power cut turns the routers' Wi-Fi off, which looks the same as leaving home, so those
 * aren't logged; this records internet drops while the Wi-Fi itself stays up.
 */
class OutageTracker(private val confirmMs: Long = 30_000) {
    private var network: String? = null
    private var connectedAtMs = 0L
    private var seenWorking = false
    private var downSinceMs: Long? = null

    /** [homeNetwork] = name of the home network we're on, or null if not on one. Returns a finished outage. */
    fun update(nowMs: Long, homeNetwork: String?, hasInternet: Boolean): Outage? {
        if (homeNetwork != network) {
            val finished = finish(nowMs)
            network = homeNetwork
            connectedAtMs = nowMs
            seenWorking = false
            downSinceMs = null
            if (homeNetwork != null) process(nowMs, hasInternet)
            return finished
        }
        if (homeNetwork == null) return null
        return process(nowMs, hasInternet)
    }

    private fun process(nowMs: Long, hasInternet: Boolean): Outage? {
        if (hasInternet) {
            val finished = finish(nowMs)
            seenWorking = true
            return finished
        }
        if (downSinceMs == null) downSinceMs = if (seenWorking) nowMs else connectedAtMs
        return null
    }

    private fun finish(nowMs: Long): Outage? {
        val start = downSinceMs ?: return null
        val net = network ?: return null
        downSinceMs = null
        return if (nowMs - start >= confirmMs) Outage(start, nowMs, net) else null
    }

    /** Start time of an outage that is happening right now, if it has lasted long enough to count. */
    fun ongoingSince(nowMs: Long): Long? = downSinceMs?.takeIf { nowMs - it >= confirmMs }
    val ongoingNetwork: String? get() = network
}

object OutageLog {
    const val MAX_ENTRIES = 300

    fun serialize(list: List<Outage>): String =
        list.takeLast(MAX_ENTRIES).joinToString("\n") { "${it.startMs},${it.endMs},${clean(it.network)}" }

    fun parse(data: String): List<Outage> = data.lineSequence().mapNotNull { line ->
        val p = line.split(',', limit = 3)
        if (p.size != 3) return@mapNotNull null
        val s = p[0].toLongOrNull() ?: return@mapNotNull null
        val e = p[1].toLongOrNull() ?: return@mapNotNull null
        if (e < s) null else Outage(s, e, p[2])
    }.toList()

    fun add(list: List<Outage>, outage: Outage): List<Outage> = (list + outage).takeLast(MAX_ENTRIES)

    /** Count and total downtime of outages that started after [sinceMs]. */
    fun summary(list: List<Outage>, sinceMs: Long): Pair<Int, Long> {
        val recent = list.filter { it.startMs >= sinceMs }
        return recent.size to recent.sumOf { it.durationMs }
    }

    private fun clean(s: String) = s.replace(',', ' ').replace('\n', ' ')
}

/** "1 h 5 min", "23 min", "45 s". */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min"
        else -> "$totalSec s"
    }
}

// ---------------------------------------------------------------- Speed tests

data class SpeedResult(
    val timeMs: Long,
    val network: String,
    val downMbps: Double?,
    val upMbps: Double?,
    val pingMs: Int?,
    val routerPingMs: Int?,
)

object SpeedMath {
    fun mbps(bytes: Long, millis: Long): Double = if (millis <= 0) 0.0 else bytes * 8.0 / millis / 1000.0

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

object SpeedHistory {
    const val MAX_ENTRIES = 30

    data class Average(val network: String, val tests: Int, val downMbps: Double?, val upMbps: Double?, val pingMs: Int?, val routerPingMs: Int?)

    fun serialize(list: List<SpeedResult>): String = list.takeLast(MAX_ENTRIES).joinToString("\n") {
        listOf(it.timeMs, it.downMbps ?: "", it.upMbps ?: "", it.pingMs ?: "", it.routerPingMs ?: "", clean(it.network))
            .joinToString(";")
    }

    fun parse(data: String): List<SpeedResult> = data.lineSequence().mapNotNull { line ->
        val p = line.split(';', limit = 6)
        if (p.size != 6) return@mapNotNull null
        SpeedResult(
            timeMs = p[0].toLongOrNull() ?: return@mapNotNull null,
            network = p[5],
            downMbps = p[1].toDoubleOrNull(),
            upMbps = p[2].toDoubleOrNull(),
            pingMs = p[3].toIntOrNull(),
            routerPingMs = p[4].toIntOrNull(),
        )
    }.toList()

    fun add(list: List<SpeedResult>, r: SpeedResult): List<SpeedResult> = (list + r).takeLast(MAX_ENTRIES)

    /** Average per network, e.g. to compare Upstairs and Downstairs. */
    fun averages(list: List<SpeedResult>): List<Average> = list.groupBy { it.network }.map { (net, rs) ->
        fun avgD(f: (SpeedResult) -> Double?) = rs.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        fun avgI(f: (SpeedResult) -> Int?) = rs.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()?.toInt()
        Average(net, rs.size, avgD { it.downMbps }, avgD { it.upMbps }, avgI { it.pingMs }, avgI { it.routerPingMs })
    }.sortedBy { it.network }

    private fun clean(s: String) = s.replace(';', ' ').replace('\n', ' ')
}
