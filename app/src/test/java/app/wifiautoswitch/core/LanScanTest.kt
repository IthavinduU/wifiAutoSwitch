package app.wifiautoswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScanTest {

    @Test fun ipConversionRoundTrip() {
        for (ip in listOf("192.168.1.1", "10.0.0.254", "172.16.5.9", "0.0.0.0", "255.255.255.255")) {
            assertEquals(ip, Ipv4.toStr(Ipv4.toInt(ip)!!))
        }
        assertEquals(null, Ipv4.toInt("192.168.1"))
        assertEquals(null, Ipv4.toInt("192.168.1.300"))
        assertEquals(null, Ipv4.toInt("fe80::1"))
    }

    @Test fun slash24_gives253OtherHosts() {
        val own = Ipv4.toInt("192.168.1.37")!!
        val t = Ipv4.scanTargets(own, 24)
        assertEquals(253, t.size)
        assertEquals("192.168.1.1", Ipv4.toStr(t.first()))
        assertEquals("192.168.1.254", Ipv4.toStr(t.last()))
        assertFalse(own in t)
    }

    @Test fun biggerSubnet_isLimitedToOwnSlash24() {
        val t = Ipv4.scanTargets(Ipv4.toInt("10.0.5.20")!!, 16)
        assertEquals(253, t.size)
        assertTrue(t.all { Ipv4.toStr(it).startsWith("10.0.5.") })
    }

    @Test fun smallerSubnet_respected() {
        val t = Ipv4.scanTargets(Ipv4.toInt("192.168.8.130")!!, 25)
        assertEquals(125, t.size)
        assertEquals("192.168.8.129", Ipv4.toStr(t.first()))
        assertEquals("192.168.8.254", Ipv4.toStr(t.last()))
    }

    @Test fun sortKeyOrdersAcrossSign() {
        assertTrue(Ipv4.sortKey("10.0.0.2") < Ipv4.sortKey("192.168.1.5"))
        assertTrue(Ipv4.sortKey("192.168.1.5") < Ipv4.sortKey("192.168.1.20"))
    }

    @Test fun neighbourTableParsing() {
        val out = """
            192.168.1.1 dev wlan0 lladdr AA:BB:CC:00:11:22 router REACHABLE
            192.168.1.20 dev wlan0 lladdr 3a:1b:2c:3d:4e:5f STALE
            192.168.1.21 dev wlan0  FAILED
            192.168.1.22 dev wlan0  INCOMPLETE
            192.168.1.23 dev rmnet0 lladdr 00:11:22:33:44:55 REACHABLE
            192.168.1.24 dev wlan0 lladdr 00:00:00:00:00:00 STALE
            fe80::1 dev wlan0 lladdr aa:bb:cc:00:11:22 router STALE
            192.168.1.20 dev wlan0 lladdr 3a:1b:2c:3d:4e:5f STALE
        """.trimIndent()
        val n = NeighborTable.parse(out, "wlan0")
        assertEquals(listOf("192.168.1.1", "192.168.1.20"), n.map { it.ip })
        assertEquals("aa:bb:cc:00:11:22", n[0].mac)
        assertEquals("REACHABLE", n[0].state)
    }

    @Test fun neighbourParsingWithoutInterfaceFilter() {
        val n = NeighborTable.parse("10.0.0.5 dev eth0 lladdr 00:11:22:33:44:55 DELAY", null)
        assertEquals(1, n.size)
    }

    @Test fun privateMacDetection() {
        assertTrue(MacInfo.isPrivate("3a:1b:2c:3d:4e:5f"))   // 0x3a has bit 1 set
        assertTrue(MacInfo.isPrivate("da:00:00:00:00:01"))
        assertFalse(MacInfo.isPrivate("00:11:22:33:44:55"))
        assertFalse(MacInfo.isPrivate("ac:de:48:00:11:22"))
    }

    @Test fun knownDevices_firstScanMarksNothingNew_thenDetectsNewcomer() {
        var known = KnownDevices.parse("")
        val first = setOf("aa:aa:aa:aa:aa:01", "aa:aa:aa:aa:aa:02")
        assertTrue(KnownDevices.newOnes(known, "Home", first).isEmpty())
        known = KnownDevices.remember(known, "Home", first)

        val second = first + "bb:bb:bb:bb:bb:03"
        assertEquals(setOf("bb:bb:bb:bb:bb:03"), KnownDevices.newOnes(known, "Home", second))

        val restored = KnownDevices.parse(KnownDevices.serialize(KnownDevices.remember(known, "Home", second)))
        assertTrue(KnownDevices.newOnes(restored, "Home", second).isEmpty())
        // Another network is tracked separately.
        assertTrue(KnownDevices.newOnes(restored, "Office\tWiFi", setOf("cc:cc:cc:cc:cc:cc")).isEmpty())
    }
}
