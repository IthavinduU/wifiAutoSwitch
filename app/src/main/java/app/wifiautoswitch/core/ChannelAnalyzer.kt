package app.wifiautoswitch.core

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
}

/** One Wi-Fi access point from a scan. [centerFreqMhz] is the centre of a 40/80/160 MHz channel (0 if unknown). */
data class AccessPoint(
    val ssid: String?,
    val bssid: String,
    val frequencyMhz: Int,
    val level: Int,
    val widthMhz: Int,
    val centerFreqMhz: Int,
)

/** How crowded one channel is: networks whose main channel it is, and a weighted interference score. */
data class ChannelLoad(val channel: Int, val networks: Int, val score: Double)

/** One of the user's own networks on one band, with the least crowded alternative. */
data class OwnNetworkAdvice(
    val ssid: String,
    val band: Band,
    val channel: Int,
    val widthMhz: Int,
    val score: Double,
    val bestChannel: Int,
    val bestScore: Double,
) {
    /** Only suggest a change when it is clearly better, not for tiny differences. */
    val worthChanging: Boolean get() = bestChannel != channel && score - bestScore >= 2.0 && bestScore <= score * 0.7
}

data class BandReport(val band: Band, val loads: List<ChannelLoad>, val own: List<OwnNetworkAdvice>, val networkCount: Int)

object WifiChannels {
    fun band(freq: Int): Band? = when (freq) {
        in 2400..2500 -> Band.GHZ_2_4
        in 4900..5900 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> null
    }

    fun channel(freq: Int): Int? = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 4900..5900 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> null
    }

    fun frequency(band: Band, channel: Int): Int = when (band) {
        Band.GHZ_2_4 -> if (channel == 14) 2484 else 2407 + 5 * channel
        Band.GHZ_5 -> 5000 + 5 * channel
        Band.GHZ_6 -> 5950 + 5 * channel
    }

    /** Frequency range (MHz) an access point actually occupies. */
    fun span(ap: AccessPoint): IntRange {
        val w = ap.widthMhz.coerceAtLeast(20)
        val centre = if (w > 20 && ap.centerFreqMhz > 0) ap.centerFreqMhz else ap.frequencyMhz
        return (centre - w / 2)..(centre + w / 2)
    }
}

/**
 * Scores channels by how much neighbouring Wi-Fi overlaps them, weighting stronger neighbours more
 * (a network at -40 dBm interferes far more than one at -90 dBm).
 *
 * 2.4 GHz suggestions are limited to 1, 6 and 11 (the only non-overlapping channels).
 * 5 GHz suggestions use non-radar channels (36-48, 149-161) at the router's current width,
 * because radar (DFS) channels can make routers go silent for a minute when they switch.
 */
object ChannelAnalyzer {

    private data class Candidate(val channel: Int, val span: IntRange)

    private fun weight(level: Int): Double = (level + 100).coerceIn(0, 70) / 10.0

    private fun overlap(a: IntRange, b: IntRange): Int =
        (minOf(a.last, b.last) - maxOf(a.first, b.first)).coerceAtLeast(0)

    private fun interference(target: IntRange, others: List<AccessPoint>): Double =
        others.sumOf { ap -> weight(ap.level) * overlap(WifiChannels.span(ap), target) / 20.0 }

    private fun candidates(band: Band, widthMhz: Int): List<Candidate> {
        fun c(ch: Int, centre: Int, w: Int) = Candidate(ch, (centre - w / 2)..(centre + w / 2))
        return when (band) {
            Band.GHZ_2_4 -> listOf(1, 6, 11).map { c(it, WifiChannels.frequency(band, it), 20) }
            Band.GHZ_5 -> when {
                widthMhz >= 160 -> listOf(c(36, 5250, 160))
                widthMhz >= 80 -> listOf(c(36, 5210, 80), c(149, 5775, 80))
                widthMhz >= 40 -> listOf(c(36, 5190, 40), c(44, 5230, 40), c(149, 5765, 40), c(157, 5805, 40))
                else -> listOf(36, 40, 44, 48, 149, 153, 157, 161).map { c(it, WifiChannels.frequency(band, it), 20) }
            }
            Band.GHZ_6 -> emptyList()
        }
    }

    fun analyze(aps: List<AccessPoint>, ownSsids: Set<String>): List<BandReport> {
        val withBand = aps.mapNotNull { ap -> WifiChannels.band(ap.frequencyMhz)?.let { it to ap } }
        return Band.values().mapNotNull { band ->
            val inBand = withBand.filter { it.first == band }.map { it.second }
            if (inBand.isEmpty()) return@mapNotNull null

            val loads = inBand.groupBy { WifiChannels.channel(it.frequencyMhz) }
                .mapNotNull { (ch, list) ->
                    ch ?: return@mapNotNull null
                    val span = WifiChannels.frequency(band, ch).let { (it - 10)..(it + 10) }
                    ChannelLoad(ch, list.size, interference(span, inBand.filter { it !in list }))
                }
                .sortedBy { it.channel }

            val own = inBand.filter { it.ssid != null && it.ssid in ownSsids }
                .groupBy { it.ssid!! }
                .mapNotNull { (ssid, list) ->
                    val mine = list.maxByOrNull { it.level } ?: return@mapNotNull null
                    val channel = WifiChannels.channel(mine.frequencyMhz) ?: return@mapNotNull null
                    // Everything except this router itself counts as interference (including your other router).
                    val others = inBand.filter { it.ssid != ssid }
                    val current = interference(WifiChannels.span(mine), others)
                    val options = candidates(band, mine.widthMhz).map { it.channel to interference(it.span, others) }
                    val best = options.minWithOrNull(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first != channel })
                    OwnNetworkAdvice(
                        ssid, band, channel, mine.widthMhz, current,
                        best?.first ?: channel, best?.second ?: current,
                    )
                }

            BandReport(band, loads, own, inBand.size)
        }
    }
}
