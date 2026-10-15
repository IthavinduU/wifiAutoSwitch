package app.wifiautoswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchingEngineTest {

    private fun engine() = SwitchingEngine(Thresholds()) // defaults: -75 dBm, +12 dB, 2 scans, 120 s

    private fun snap(now: Long, role: Role?, rssi: Int?, candRssi: Int? = null, scanAt: Long = now) =
        Snapshot(now, role, rssi, candRssi?.let { ScanReading(it, scanAt) })

    // Scenarios 1–3: upstairs weakens, downstairs strengthens, switch happens after two separate scans.
    @Test fun weakUpstairsStrongDownstairs_switchesAfterTwoSeparateScans() {
        val e = engine()
        val d1 = e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55))
        assertTrue(d1 is Decision.NeedScan && d1.confirming)
        val d2 = e.evaluate(snap(30_000, Role.UPSTAIRS, -83, -54))
        assertTrue(d2 is Decision.Switch)
        assertEquals(Role.DOWNSTAIRS, (d2 as Decision.Switch).target)
    }

    @Test fun reverseDirection_downstairsToUpstairs() {
        val e = engine()
        e.evaluate(snap(10_000, Role.DOWNSTAIRS, -80, -50))
        val d = e.evaluate(snap(25_000, Role.DOWNSTAIRS, -80, -50))
        assertEquals(Role.UPSTAIRS, (d as Decision.Switch).target)
    }

    @Test fun sameScanSeenTwice_countsOnlyOnce() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55, scanAt = 9_000))
        val d = e.evaluate(snap(12_000, Role.UPSTAIRS, -82, -55, scanAt = 9_000))
        assertTrue(d is Decision.NeedScan)
        // 2.4 GHz and 5 GHz entries from one scan arrive milliseconds apart: still one observation.
        val d2 = e.evaluate(snap(13_000, Role.UPSTAIRS, -82, -54, scanAt = 9_400))
        assertTrue(d2 is Decision.NeedScan)
    }

    // Scenario 4: both strong.
    @Test fun strongCurrentConnection_neverSwitchesEvenIfOtherIsMuchStronger() {
        val e = engine()
        repeat(5) { i -> assertTrue(e.evaluate(snap(10_000L + i * 20_000, Role.UPSTAIRS, -60, -35)) is Decision.Idle) }
    }

    // Scenario 5: both weak.
    @Test fun bothWeak_noSwitch() {
        val e = engine()
        repeat(4) { i -> assertTrue(e.evaluate(snap(10_000L + i * 20_000, Role.UPSTAIRS, -86, -80)) is Decision.WeakNoBetter) }
    }

    @Test fun candidateTooWeakInAbsoluteTerms_noSwitch() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -96, -84))
        assertFalse(e.evaluate(snap(30_000, Role.UPSTAIRS, -96, -84)) is Decision.Switch)
    }

    @Test fun slightlyStrongerAlternative_noSwitch() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -78, -70))
        assertTrue(e.evaluate(snap(30_000, Role.UPSTAIRS, -78, -70)) is Decision.WeakNoBetter)
    }

    // Scenario 6: destination disappears.
    @Test fun destinationDisappears_noSwitchWhileMissing() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55))
        val d = e.evaluate(snap(30_000, Role.UPSTAIRS, -82, candRssi = null))
        assertTrue(d is Decision.NeedScan)
        val d2 = e.evaluate(snap(60_000, Role.UPSTAIRS, -82, -55, scanAt = 10_000)) // only an old cached entry
        assertTrue(d2 is Decision.NeedScan)
    }

    // Scenario 14: stale / throttled scans.
    @Test fun staleScanResult_isNotUsed() {
        val e = engine()
        val d = e.evaluate(snap(100_000, Role.UPSTAIRS, -85, -50, scanAt = 60_000)) // 40 s old
        assertTrue(d is Decision.NeedScan && !d.confirming)
    }

    @Test fun futureTimestamp_isIgnored() {
        val e = engine()
        assertTrue(e.evaluate(snap(10_000, Role.UPSTAIRS, -85, -50, scanAt = 50_000)) is Decision.NeedScan)
    }

    @Test fun observationsOutsideWindow_doNotCount() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55))
        val d = e.evaluate(snap(150_000, Role.UPSTAIRS, -82, -55)) // first one is > 90 s old
        assertTrue(d is Decision.NeedScan)
    }

    @Test fun signalRecoversDuringConfirmation_resetsEvidence() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55))
        assertTrue(e.evaluate(snap(15_000, Role.UPSTAIRS, -65, -55)) is Decision.Idle) // temporary dip ended
        assertTrue(e.evaluate(snap(30_000, Role.UPSTAIRS, -82, -55)) is Decision.NeedScan) // must start again
    }

    @Test fun unknownSignal_doesNothing() {
        val e = engine()
        assertTrue(e.evaluate(snap(10_000, Role.UPSTAIRS, null, -50)) is Decision.Idle)
        assertTrue(e.evaluate(snap(10_000, Role.UPSTAIRS, -127, -50)) is Decision.Idle)
        assertTrue(e.evaluate(snap(10_000, null, -90, -50)) is Decision.Idle)
    }

    // Cooldown + scenario 13: repeated transitions between floors.
    @Test fun cooldownPreventsPingPong_thenAllowsReturnTrip() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55))
        assertTrue(e.evaluate(snap(30_000, Role.UPSTAIRS, -82, -55)) is Decision.Switch)
        e.onSwitchStarted()
        assertTrue(e.evaluate(snap(32_000, Role.UPSTAIRS, -82, -55)) is Decision.Hold)
        e.onSwitchSucceeded(40_000)

        // Now downstairs; user immediately walks back up. Within 120 s cooldown: hold.
        assertTrue(e.evaluate(snap(60_000, Role.DOWNSTAIRS, -84, -50)) is Decision.Hold)
        assertTrue(e.evaluate(snap(150_000, Role.DOWNSTAIRS, -84, -50)) is Decision.Hold)
        // After cooldown, the return trip is allowed (again needing two scans).
        assertTrue(e.evaluate(snap(170_000, Role.DOWNSTAIRS, -84, -50)) is Decision.NeedScan)
        val d = e.evaluate(snap(190_000, Role.DOWNSTAIRS, -84, -50))
        assertEquals(Role.UPSTAIRS, (d as Decision.Switch).target)
    }

    // Scenario 7: failed switching attempts.
    @Test fun failures_backOffExponentially_thenPause() {
        val e = engine()
        var now = 10_000L
        val expectedBackoffs = listOf(60_000L, 120_000L, 240_000L, 480_000L)
        for (backoff in expectedBackoffs) {
            e.onSwitchStarted()
            e.onSwitchFailed(now)
            assertTrue(e.evaluate(snap(now + backoff - 1_000, Role.UPSTAIRS, -82, -55)) is Decision.Hold)
            now += backoff
            assertFalse(e.evaluate(snap(now, Role.UPSTAIRS, -82, -55)) is Decision.Hold)
        }
        e.onSwitchStarted()
        e.onSwitchFailed(now) // 5th failure
        assertTrue(e.isPaused)
        assertTrue(e.evaluate(snap(now + 3_600_000, Role.UPSTAIRS, -82, -55)) is Decision.Hold)
        e.reset()
        assertFalse(e.evaluate(snap(now + 3_600_000, Role.UPSTAIRS, -82, -55)) is Decision.Hold)
    }

    @Test fun permanentFailure_pausesImmediately() {
        val e = engine()
        e.onSwitchStarted()
        e.onSwitchFailed(10_000, permanent = true)
        assertTrue(e.isPaused)
    }

    @Test fun successResetsFailureCount() {
        val e = engine()
        e.onSwitchStarted(); e.onSwitchFailed(10_000)
        e.onSwitchStarted(); e.onSwitchSucceeded(100_000)
        assertEquals(0, e.consecutiveFailures)
    }

    @Test fun assistedPrompt_waitsButEndsWhenUserChangesNetwork() {
        val e = engine()
        e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55))
        e.evaluate(snap(30_000, Role.UPSTAIRS, -82, -55))
        e.onSwitchStarted()
        e.onUserPrompted(30_000)
        assertTrue(e.evaluate(snap(100_000, Role.UPSTAIRS, -82, -55)) is Decision.Hold) // no nagging
        // User switched by hand; later walks back: prompt cooldown is gone.
        e.evaluate(snap(110_000, Role.DOWNSTAIRS, -50, -82))
        assertTrue(e.evaluate(snap(120_000, Role.DOWNSTAIRS, -84, -50)) is Decision.NeedScan)
    }

    @Test fun abandonedManualSwitch_doesNotCountAsFailure() {
        val e = engine()
        e.onSwitchStarted()
        e.onSwitchAbandoned()
        assertEquals(0, e.consecutiveFailures)
        assertFalse(e.isSwitching)
        assertFalse(e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55)) is Decision.Hold)
    }

    @Test fun singleObservationSetting_switchesOnFirstGoodScan() {
        val e = SwitchingEngine(Thresholds(requiredObservations = 1))
        assertTrue(e.evaluate(snap(10_000, Role.UPSTAIRS, -82, -55)) is Decision.Switch)
    }

    @Test fun thresholdsAreClamped() {
        val e = SwitchingEngine(Thresholds(weakDbm = 10, minImprovementDb = 0, requiredObservations = 0))
        assertEquals(-50, e.thresholds.weakDbm)
        assertEquals(3, e.thresholds.minImprovementDb)
        assertEquals(1, e.thresholds.requiredObservations)
    }

    @Test fun ssidNormalisation() {
        assertEquals("Home 5G", Ssid.normalize("\"Home 5G\""))
        assertEquals(null, Ssid.normalize("<unknown ssid>"))
        assertEquals(null, Ssid.normalize(""))
        assertEquals("a1b2", Ssid.normalize("a1b2"))
    }
}
