package app.wifiautoswitch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdTunerTest {

    /** Upstairs desk: strong current signal, other router weaker. Downstairs: current weak, other strong. */
    private fun house(t: ThresholdTuner, stayFrom: Int, stayTo: Int, helpFrom: Int, helpTo: Int) {
        repeat(5) {
            for (r in stayFrom downTo stayTo) t.add(r, r - 15, 12)
            for (r in helpFrom downTo helpTo) t.add(r, -50, 12)
        }
    }

    @Test fun notEnoughData_noSuggestion() {
        val t = ThresholdTuner()
        repeat(7) { t.add(-50, -70, 12); t.add(-80, -50, 12) }
        assertEquals(null, t.suggest())
    }

    @Test fun cleanSeparation_suggestsMiddleOfGap() {
        val t = ThresholdTuner()
        house(t, stayFrom = -50, stayTo = -60, helpFrom = -72, helpTo = -80)
        // Any T in -71..-60 separates perfectly; the middle is chosen.
        val s = t.suggest()!!
        assertTrue("got $s", s in -67..-64)
    }

    @Test fun weakerSignalBeforeOtherIsBetter_suggestsLowerThreshold() {
        val t = ThresholdTuner()
        house(t, stayFrom = -50, stayTo = -78, helpFrom = -82, helpTo = -88)
        val s = t.suggest()!!
        assertTrue("got $s", s in -81..-78)
    }

    @Test fun stillWorkableWithNoise() {
        val t = ThresholdTuner()
        house(t, stayFrom = -50, stayTo = -62, helpFrom = -70, helpTo = -80)
        repeat(3) { t.add(-55, -40, 12) }   // odd reading: other briefly much stronger upstairs
        repeat(3) { t.add(-78, -80, 12) }   // odd reading: downstairs still fine
        val s = t.suggest()!!
        assertTrue("got $s", s in -69..-63)
    }

    @Test fun ambiguousReadingsAreIgnored() {
        val t = ThresholdTuner()
        assertFalse(t.add(-70, -65, 12))    // 5 dB better: neither group
        assertTrue(t.add(-70, -58, 12))
        assertTrue(t.add(-70, -70, 12))
        assertFalse(t.add(-127, -50, 12))   // invalid
        assertEquals(2, t.total)
    }

    @Test fun serializeRoundTrip() {
        val t = ThresholdTuner()
        house(t, -50, -60, -72, -80)
        val copy = ThresholdTuner().apply { load(t.serialize()) }
        assertEquals(t.total, copy.total)
        assertEquals(t.suggest(), copy.suggest())
    }

    @Test fun corruptDataLoadsEmpty() {
        val t = ThresholdTuner()
        t.load("garbage")
        assertEquals(0, t.total)
        t.load("1,2|3")
        assertEquals(0, t.total)
    }

    @Test fun oldReadingsDecay() {
        val t = ThresholdTuner()
        repeat(4000) { t.add(-55, -70, 12) }
        assertTrue(t.total <= ThresholdTuner.MAX_TOTAL)
    }

    @Test fun suggestionStaysInSafeRange() {
        val t = ThresholdTuner()
        repeat(20) { t.add(-45, -60, 12); t.add(-50, -30, 12) }
        val s = t.suggest()!!
        assertTrue(s in ThresholdTuner.SUGGEST_MIN..ThresholdTuner.SUGGEST_MAX)
    }
}
