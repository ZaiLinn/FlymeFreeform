package io.github.zailinn.flymefreeform.hook.launch.decision

import org.junit.Assert.*
import org.junit.Test

class DeferredLaunchTest {
    @Test fun invalidatedBeforeCloseKeepsOriginalRequest() {
        val calls = mutableListOf<Boolean>()
        DeferredLaunch.run(400, { false }, { error("must not close") },
            { _, _ -> error("must not delay") }, { calls += it; true },
            { closed, zoom, started -> assertFalse(closed); assertFalse(zoom); assertTrue(started) })
        assertEquals(listOf(false), calls)
    }
    @Test fun settingsChangeDuringDelayUsesOriginalOptions() {
        var allowed = true
        var deferred: (() -> Unit)? = null
        val calls = mutableListOf<Boolean>()
        DeferredLaunch.run(400, { allowed }, { true },
            { delay, action -> assertEquals(400L, delay); deferred = action },
            { calls += it; true }, { _, zoom, started -> assertFalse(zoom); assertTrue(started) })
        allowed = false
        deferred!!()
        assertEquals(listOf(false), calls)
    }
    @Test fun failedZoomRetriesOriginalOnceAndReportsFailure() {
        val calls = mutableListOf<Boolean>()
        DeferredLaunch.run(400, { true }, { false }, { delay, action -> assertEquals(0L, delay); action() },
            { calls += it; if (it) error("vendor failure") else false },
            { _, zoom, started -> assertFalse(zoom); assertFalse(started) })
        assertEquals(listOf(true, false), calls)
    }
    @Test fun successfulZoomDoesNotStartTwice() {
        val calls = mutableListOf<Boolean>()
        DeferredLaunch.run(400, { true }, { true }, { _, action -> action() },
            { calls += it; true }, { _, zoom, started -> assertTrue(zoom); assertTrue(started) })
        assertEquals(listOf(true), calls)
    }
    @Test fun supersededDuringDelayDoesNotReplayOldRequest() {
        var current = true
        var deferred: (() -> Unit)? = null
        var completed = false
        DeferredLaunch.run(400, { true }, { true }, { _, action -> deferred = action },
            { error("superseded request must not start") },
            { _, _, started -> completed = true; assertFalse(started) }, { current })
        current = false
        deferred!!()
        assertTrue(completed)
    }

}
