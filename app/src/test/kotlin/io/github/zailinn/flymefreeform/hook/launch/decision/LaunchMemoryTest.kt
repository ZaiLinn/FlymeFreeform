package io.github.zailinn.flymefreeform.hook.launch.decision

import org.junit.Assert.*
import org.junit.Test

class LaunchMemoryTest {
    @Test fun duplicateWindowHasExclusiveUpperBoundary() {
        val m = LaunchMemory(); m.markLink("link", 1000)
        assertTrue(m.duplicate("link", 2499)); assertFalse(m.duplicate("link", 2500))
        assertFalse(m.duplicate("other", 2000)); assertFalse(m.duplicate("link", 2501))
    }
    @Test fun relayWindowAndHopLimitHaveExclusiveUpperBoundary() {
        val m = LaunchMemory(); m.markZoomed("x", false, 1000)
        assertTrue(m.selfRelaunch("x", 3999)); assertFalse(m.selfRelaunch("x", 4000))
        assertFalse(m.selfRelaunch("other", 2000)); assertFalse(m.selfRelaunch("x", 4001))
        m.markZoomed("x", true, 2000); assertTrue(m.selfRelaunch("x", 2001))
        m.markZoomed("x", true, 2100); assertFalse(m.selfRelaunch("x", 2101))
        m.markZoomed("x", false, 2200); assertTrue(m.selfRelaunch("x", 2201))
    }
    @Test fun returnCheckIsReadOnlyAndFingerprintExpiresAtTwoSeconds() {
        val m = LaunchMemory(); m.record("a", "b", 1000)
        val first = m.checkReturning("b", "a", 2000)
        assertTrue(first.returning); assertEquals(first, m.checkReturning("b", "a", 2000))
        m.apply(first)
        assertTrue(m.checkReturning("b", "a", 3999).returning)
        assertFalse(m.checkReturning("b", "a", 4000).returning)
    }
    @Test fun pendingReturnExpiresOnlyAfterOneHundredTwentySeconds() {
        val m = LaunchMemory(); m.record("a", "b", 1000)
        assertTrue(m.checkReturning("b", "a", 121000).returning)
        val expired = m.checkReturning("b", "a", 121001)
        assertFalse(expired.returning); assertEquals("INTER_APP_CLEAR_EXPIRED", expired.log)
        m.apply(expired); assertNull(m.checkReturning("b", "a", 121002).log)
    }
}
