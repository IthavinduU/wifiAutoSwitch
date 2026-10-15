package app.wifiautoswitch.core

/**
 * Tunables for the switching decision. The first four are user-adjustable under Advanced Settings;
 * the rest are fixed safety limits.
 */
data class Thresholds(
    /** Current network is "weak" below this. Nothing happens while the signal is at or above it. */
    val weakDbm: Int = -75,
    /** Other network must be at least this much stronger than the current one. */
    val minImprovementDb: Int = 12,
    /** No new switch for this long after a successful switch. */
    val cooldownMs: Long = 120_000,
    /** Number of separate scans that must agree before switching. */
    val requiredObservations: Int = 2,

    /** A scan reading older than this is treated as unknown. */
    val maxScanAgeMs: Long = 30_000,
    /** Agreeing observations must fall within this window. */
    val observationWindowMs: Long = 90_000,
    /** Two readings closer together than this are considered the same scan (e.g. 2.4 + 5 GHz entries). */
    val minObservationSpacingMs: Long = 3_000,
    /** Never move to a network that is itself this weak, however bad the current one is. */
    val minCandidateDbm: Int = -82,
    /** After this many consecutive failed switches, stop until the user re-enables Auto Switch. */
    val maxConsecutiveFailures: Int = 5,
    val baseBackoffMs: Long = 60_000,
    val maxBackoffMs: Long = 30 * 60_000,
    /** After asking the user to switch manually, don't nag again for this long (unless they move networks). */
    val promptCooldownMs: Long = 5 * 60_000,
) {
    fun sanitized(): Thresholds = copy(
        weakDbm = weakDbm.coerceIn(-95, -50),
        minImprovementDb = minImprovementDb.coerceIn(3, 40),
        cooldownMs = cooldownMs.coerceIn(15_000, 3_600_000),
        requiredObservations = requiredObservations.coerceIn(1, 5),
    )
}

/** Best recent reading of a network from Wi-Fi scan results. [scanTimeMs] is on the elapsed-realtime clock. */
data class ScanReading(val rssi: Int, val scanTimeMs: Long)

/** Everything the engine needs to know at one moment. All times use the same monotonic clock. */
data class Snapshot(
    val nowMs: Long,
    /** null when not connected to either selected network. */
    val currentRole: Role?,
    /** null when unknown. */
    val currentRssi: Int?,
    /** Best reading of the other selected network, or null if it has not been seen. */
    val candidate: ScanReading?,
)

sealed interface Decision {
    /** Nothing to do (good signal, or not on a selected network). */
    data class Idle(val reason: String) : Decision

    /** Deliberately waiting: cooldown, backoff, switch in progress or paused. */
    data class Hold(val reason: String) : Decision

    /** Signal is weak, but the other network is not clearly better right now. */
    data class WeakNoBetter(val reason: String) : Decision

    /** Signal is weak and we need fresher scan data. [confirming] = we already have one good observation. */
    data class NeedScan(val reason: String, val confirming: Boolean) : Decision

    /** Enough consistent evidence: move to [target]. */
    data class Switch(val target: Role, val currentRssi: Int, val targetRssi: Int) : Decision
}

/**
 * Pure decision logic: no Android APIs, no clocks of its own, no I/O. The service feeds it snapshots
 * and reports the outcome of every switch attempt back to it.
 */
class SwitchingEngine(thresholds: Thresholds = Thresholds()) {

    var thresholds: Thresholds = thresholds.sanitized()
        set(value) { field = value.sanitized() }

    private data class Observation(val scanTimeMs: Long, val atMs: Long, val currentRssi: Int, val candidateRssi: Int)

    private val observations = ArrayDeque<Observation>()
    private var lastRole: Role? = null
    private var cooldownUntilMs = 0L
    private var cooldownEndsOnNetworkChange = false
    private var backoffUntilMs = 0L

    var consecutiveFailures = 0
        private set
    var isPaused = false
        private set
    var isSwitching = false
        private set
    val observationCount: Int get() = observations.size

    fun evaluate(s: Snapshot): Decision {
        val t = thresholds

        if (s.currentRole != lastRole) {
            observations.clear()
            // A prompt-cooldown only exists to avoid nagging; if the user moved networks it is over.
            if (lastRole != null && s.currentRole != null && cooldownEndsOnNetworkChange) {
                cooldownUntilMs = 0
                cooldownEndsOnNetworkChange = false
            }
            lastRole = s.currentRole
        }

        if (isSwitching) return Decision.Hold("Switch in progress")
        val role = s.currentRole ?: return Decision.Idle("Not connected to a selected network")
        val rssi = s.currentRssi?.takeIf { it in -126..0 } ?: return Decision.Idle("Signal strength unknown")
        if (isPaused) return Decision.Hold("Paused after $consecutiveFailures failed attempts")
        if (s.nowMs < cooldownUntilMs) return Decision.Hold("Cooldown (${(cooldownUntilMs - s.nowMs + 999) / 1000}s left)")
        if (s.nowMs < backoffUntilMs) return Decision.Hold("Retrying in ${(backoffUntilMs - s.nowMs + 999) / 1000}s")

        if (rssi >= t.weakDbm) {
            observations.clear()
            return Decision.Idle("Signal good ($rssi dBm)")
        }

        observations.removeAll { s.nowMs - it.atMs > t.observationWindowMs }

        val c = s.candidate
        if (c == null || s.nowMs - c.scanTimeMs > t.maxScanAgeMs || c.scanTimeMs > s.nowMs + 1_000) {
            return Decision.NeedScan(
                "Weak signal ($rssi dBm); no recent reading of ${role.other.label}",
                confirming = observations.isNotEmpty(),
            )
        }

        if (c.rssi < t.minCandidateDbm || c.rssi - rssi < t.minImprovementDb) {
            observations.clear()
            return Decision.WeakNoBetter("Weak ($rssi dBm) but ${role.other.label} is ${c.rssi} dBm")
        }

        val last = observations.lastOrNull()
        if (last == null || c.scanTimeMs - last.scanTimeMs >= t.minObservationSpacingMs) {
            observations.addLast(Observation(c.scanTimeMs, s.nowMs, rssi, c.rssi))
        }

        if (observations.size >= t.requiredObservations) {
            observations.clear()
            return Decision.Switch(role.other, rssi, c.rssi)
        }
        return Decision.NeedScan(
            "${role.other.label} looks stronger (${c.rssi} vs $rssi dBm), confirming " +
                "${observations.size}/${t.requiredObservations}",
            confirming = true,
        )
    }

    fun onSwitchStarted() {
        isSwitching = true
        observations.clear()
    }

    fun onSwitchSucceeded(nowMs: Long) {
        isSwitching = false
        consecutiveFailures = 0
        backoffUntilMs = 0
        cooldownUntilMs = nowMs + thresholds.cooldownMs
        cooldownEndsOnNetworkChange = false
    }

    /** [permanent] = retrying cannot help (e.g. network not saved); pause immediately. */
    fun onSwitchFailed(nowMs: Long, permanent: Boolean = false) {
        isSwitching = false
        consecutiveFailures++
        val t = thresholds
        val shift = (consecutiveFailures - 1).coerceIn(0, 20)
        backoffUntilMs = nowMs + minOf(t.baseBackoffMs shl shift, t.maxBackoffMs)
        if (permanent || consecutiveFailures >= t.maxConsecutiveFailures) isPaused = true
    }

    /** A user-requested ("Switch now") attempt didn't complete. Doesn't count as an automatic failure. */
    fun onSwitchAbandoned() {
        isSwitching = false
    }

    /** Used in assisted mode: we asked the user to switch instead of switching ourselves. */
    fun onUserPrompted(nowMs: Long) {
        isSwitching = false
        cooldownUntilMs = nowMs + maxOf(thresholds.promptCooldownMs, thresholds.cooldownMs)
        cooldownEndsOnNetworkChange = true
    }

    /** Clears failures/pauses, e.g. when the user turns Auto Switch off and on. */
    fun reset() {
        observations.clear()
        isPaused = false
        isSwitching = false
        consecutiveFailures = 0
        backoffUntilMs = 0
        cooldownUntilMs = 0
        cooldownEndsOnNetworkChange = false
    }
}
