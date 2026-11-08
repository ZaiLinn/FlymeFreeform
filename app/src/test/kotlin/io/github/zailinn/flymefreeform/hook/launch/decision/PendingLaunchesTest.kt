package io.github.zailinn.flymefreeform.hook.launch.decision

import org.junit.Assert.*
import org.junit.Test

class PendingLaunchesTest {
    @Test fun duplicateIsCollapsedButNewLinkSupersedesOldWork() {
        val pending = PendingLaunches()
        val first = pending.acquire("0|app", "A")!!
        assertNull(pending.acquire("0|app", "A"))
        val next = pending.acquire("0|app", "B")!!
        assertFalse(pending.current("0|app", first))
        pending.finish("0|app", first)
        assertTrue(pending.current("0|app", next))
    }
    @Test fun returningToSameFingerprintDoesNotReactivateOldTask() {
        val pending = PendingLaunches()
        val first = pending.acquire("0|app", "A")!!
        pending.acquire("0|app", "B")!!
        val latest = pending.acquire("0|app", "A")!!
        assertFalse(pending.current("0|app", first))
        assertTrue(pending.current("0|app", latest))
        pending.clear()
        assertFalse(pending.current("0|app", latest))
    }
    @Test fun profileHasIndependentPendingLaunch() {
        val pending = PendingLaunches()
        val primary = pending.acquire("0|app", "A")!!
        val profile = pending.acquire("10|app", "A")!!
        assertTrue(pending.current("0|app", primary))
        assertTrue(pending.current("10|app", profile))
    }
}
