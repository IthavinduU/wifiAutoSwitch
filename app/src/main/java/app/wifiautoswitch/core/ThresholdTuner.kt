package app.wifiautoswitch.core

/**
 * Learns a good "weak signal" threshold for this particular house.
 *
 * Every time Android finishes a Wi-Fi scan anyway, we compare the signal of the network we're on
 * with the other home network and drop one reading into a histogram (by current signal):
 *  - "switch would help": the other network was clearly stronger (>= min improvement)
 *  - "stay":              the current network was at least as strong
 * Readings in between are ignored.
 *
 * The suggested threshold T (weak = signal below T) is the one that best separates the two groups.
 * A missed switch (staying on a bad connection) counts double compared with an unneeded check,
 * because the engine still requires a clear improvement before it actually switches.
 * Only counts are stored, never locations or network names.
 */
class ThresholdTuner {

    companion object {
        const val MIN_DBM = -95
        const val MAX_DBM = -40
        private const val BUCKETS = MAX_DBM - MIN_DBM + 1
        const val SUGGEST_MIN = -85
        const val SUGGEST_MAX = -60
        const val MIN_SAMPLES_PER_GROUP = 8
        /** Above this total, all counts are halved so recent behaviour matters more. */
        const val MAX_TOTAL = 3000
        private const val MISSED_SWITCH_WEIGHT = 2
    }

    private val switchHelps = IntArray(BUCKETS)
    private val stayIsFine = IntArray(BUCKETS)

    val switchHelpsCount: Int get() = switchHelps.sum()
    val stayIsFineCount: Int get() = stayIsFine.sum()
    val total: Int get() = switchHelpsCount + stayIsFineCount

    /** Returns true if the reading was recorded. */
    fun add(currentRssi: Int, otherRssi: Int, minImprovementDb: Int): Boolean {
        if (currentRssi !in -126..0 || otherRssi !in -126..0) return false
        val i = currentRssi.coerceIn(MIN_DBM, MAX_DBM) - MIN_DBM
        val diff = otherRssi - currentRssi
        when {
            diff >= minImprovementDb -> switchHelps[i]++
            diff <= 0 -> stayIsFine[i]++
            else -> return false
        }
        if (total > MAX_TOTAL) {
            for (k in 0 until BUCKETS) {
                switchHelps[k] /= 2
                stayIsFine[k] /= 2
            }
        }
        return true
    }

    /** Suggested threshold in dBm, or null until both groups have enough readings. */
    fun suggest(): Int? {
        if (switchHelpsCount < MIN_SAMPLES_PER_GROUP || stayIsFineCount < MIN_SAMPLES_PER_GROUP) return null
        var bestError = Int.MAX_VALUE
        val best = ArrayList<Int>()
        for (t in SUGGEST_MIN..SUGGEST_MAX) {
            var error = 0
            for (k in 0 until BUCKETS) {
                val rssi = k + MIN_DBM
                error += if (rssi >= t) MISSED_SWITCH_WEIGHT * switchHelps[k] else stayIsFine[k]
            }
            if (error < bestError) {
                bestError = error
                best.clear()
            }
            if (error == bestError) best.add(t)
        }
        // Several thresholds can be equally good: take the middle one for a safety margin both ways.
        return best[best.size / 2]
    }

    fun reset() {
        switchHelps.fill(0)
        stayIsFine.fill(0)
    }

    fun serialize(): String = switchHelps.joinToString(",") + "|" + stayIsFine.joinToString(",")

    fun load(data: String) {
        reset()
        val parts = data.split("|")
        if (parts.size != 2) return
        val a = parts[0].split(",").map { it.toIntOrNull() ?: return }
        val b = parts[1].split(",").map { it.toIntOrNull() ?: return }
        if (a.size != BUCKETS || b.size != BUCKETS) return
        for (k in 0 until BUCKETS) {
            switchHelps[k] = a[k].coerceAtLeast(0)
            stayIsFine[k] = b[k].coerceAtLeast(0)
        }
    }
}
