package app.wifiautoswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetToolsTest {

    private fun ap(ssid: String, freq: Int, level: Int, width: Int = 20, centre: Int = 0, bssid: String = ssid + freq) =
        AccessPoint(ssid, bssid, freq, level, width, centre)

    // ------------------------------------------------ channels

    @Test fun channelNumbers() {
        assertEquals(1, WifiChannels.channel(2412))
        assertEquals(6, WifiChannels.channel(2437))
        assertEquals(14, WifiChannels.channel(2484))
        assertEquals(36, WifiChannels.channel(5180))
        assertEquals(149, WifiChannels.channel(5745))
        assertEquals(5, WifiChannels.channel(5975))
        assertEquals(Band.GHZ_5, WifiChannels.band(5180))
    }

    @Test fun crowdedChannel6_suggestsQuieterOne() {
        val aps = listOf(
            ap("Home", 2437, -45),                       // mine, channel 6
            ap("N1", 2437, -55), ap("N2", 2437, -60), ap("N3", 2442, -58), // neighbours on 6/7
            ap("N4", 2462, -85),                         // weak neighbour on 11
        )
        val report = ChannelAnalyzer.analyze(aps, setOf("Home")).single()
        val mine = report.own.single()
        assertEquals(6, mine.channel)
        assertTrue("best=${mine.bestChannel}", mine.bestChannel == 1 || mine.bestChannel == 11)
        assertTrue(mine.worthChanging)
    }

    @Test fun alreadyBestChannel_noChange() {
        val aps = listOf(ap("Home", 2412, -45), ap("N1", 2437, -55), ap("N2", 2462, -60))
        val mine = ChannelAnalyzer.analyze(aps, setOf("Home")).single().own.single()
        assertEquals(1, mine.bestChannel)
        assertFalse(mine.worthChanging)
    }

    @Test fun smallDifference_notWorthChanging() {
        val aps = listOf(ap("Home", 2412, -45), ap("N1", 2412, -90), ap("N2", 2462, -92))
        assertFalse(ChannelAnalyzer.analyze(aps, setOf("Home")).single().own.single().worthChanging)
    }

    @Test fun fiveGhz80MHz_comparesBlocks() {
        val aps = listOf(
            ap("Home", 5180, -50, width = 80, centre = 5210),
            ap("N1", 5200, -55, width = 80, centre = 5210),
            ap("N2", 5220, -60, width = 80, centre = 5210),
        )
        val mine = ChannelAnalyzer.analyze(aps, setOf("Home")).single().own.single()
        assertEquals(149, mine.bestChannel)
        assertTrue(mine.worthChanging)
    }

    @Test fun twoOwnRouters_eachSeeTheOtherAsInterference() {
        val aps = listOf(ap("Up", 2437, -40), ap("Down", 2437, -50))
        val own = ChannelAnalyzer.analyze(aps, setOf("Up", "Down")).single().own
        assertEquals(2, own.size)
        assertTrue(own.all { it.bestChannel != 6 })
    }

    @Test fun bandsReportedSeparately() {
        val r = ChannelAnalyzer.analyze(listOf(ap("A", 2412, -50), ap("B", 5180, -50)), emptySet())
        assertEquals(listOf(Band.GHZ_2_4, Band.GHZ_5), r.map { it.band })
    }

    // ------------------------------------------------ outages

    @Test fun shortBlip_notLogged() {
        val t = OutageTracker()
        t.update(0, "Up", true)
        t.update(10_000, "Up", false)
        assertEquals(null, t.update(25_000, "Up", true))
    }

    @Test fun realOutage_logged() {
        val t = OutageTracker()
        t.update(0, "Up", true)
        t.update(60_000, "Up", false)
        assertEquals(60_000L, t.ongoingSince(200_000))
        val o = t.update(600_000, "Up", true)!!
        assertEquals(60_000L, o.startMs)
        assertEquals(540_000L, o.durationMs)
        assertEquals(null, t.ongoingSince(700_000))
    }

    @Test fun freshConnectionStillChecking_notAnOutage() {
        val t = OutageTracker()
        t.update(0, "Up", false)                 // Android still validating
        assertEquals(null, t.update(4_000, "Up", true))
    }

    @Test fun connectedWithNoInternetFromStart_counts() {
        val t = OutageTracker()
        t.update(0, "Up", false)
        val o = t.update(120_000, "Up", true)!!
        assertEquals(0L, o.startMs)
    }

    @Test fun leavingNetworkDuringOutage_endsIt() {
        val t = OutageTracker()
        t.update(0, "Up", true)
        t.update(10_000, "Up", false)
        val o = t.update(100_000, "Down", true)!!   // e.g. failover to the other router
        assertEquals("Up", o.network)
        assertEquals(90_000L, o.durationMs)
        assertEquals(null, t.update(200_000, null, false))
    }

    @Test fun outageLogRoundTripAndSummary() {
        val list = listOf(Outage(1_000, 61_000, "Up, stairs"), Outage(500_000, 800_000, "Down"))
        val back = OutageLog.parse(OutageLog.serialize(list))
        assertEquals(2, back.size)
        assertEquals("Up  stairs", back[0].network)
        assertEquals(1 to 300_000L, OutageLog.summary(back, 100_000))
        assertEquals("5 min", formatDuration(300_000))
        assertEquals("1 h 5 min", formatDuration(3_900_000))
    }

    // ------------------------------------------------ speed

    @Test fun mbpsMath() {
        assertEquals(80.0, SpeedMath.mbps(10_000_000, 1_000), 0.001)
        assertEquals(15L, SpeedMath.median(listOf(30, 10, 15)))
        assertEquals(null, SpeedMath.median(emptyList()))
    }

    @Test fun speedHistoryRoundTripAndAverages() {
        var list = emptyList<SpeedResult>()
        list = SpeedHistory.add(list, SpeedResult(1, "Upstairs", 90.0, 40.0, 12, 3))
        list = SpeedHistory.add(list, SpeedResult(2, "Upstairs", 70.0, null, 14, 5))
        list = SpeedHistory.add(list, SpeedResult(3, "Downstairs", 30.0, 20.0, null, 9))
        val back = SpeedHistory.parse(SpeedHistory.serialize(list))
        assertEquals(3, back.size)
        assertEquals(null, back[1].upMbps)
        val avg = SpeedHistory.averages(back).associateBy { it.network }
        assertEquals(80.0, avg.getValue("Upstairs").downMbps!!, 0.001)
        assertEquals(40.0, avg.getValue("Upstairs").upMbps!!, 0.001)
        assertEquals(null, avg.getValue("Downstairs").pingMs)
    }

    @Test fun speedHistoryKeepsLast30() {
        var list = emptyList<SpeedResult>()
        repeat(40) { list = SpeedHistory.add(list, SpeedResult(it.toLong(), "Up", 1.0, 1.0, 1, 1)) }
        assertEquals(30, list.size)
        assertEquals(10L, list.first().timeMs)
    }
}
