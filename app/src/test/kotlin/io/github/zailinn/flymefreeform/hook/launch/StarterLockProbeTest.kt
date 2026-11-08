package io.github.zailinn.flymefreeform.hook.launch

import org.junit.Assert.*
import org.junit.Test

class StarterLockProbeTest {
    @Test fun detectsHeldLockAndThrottlesEachName() {
        var now = 0L
        val logs = mutableListOf<String>()
        val probe = StarterLockProbe({ now }, logs::add)
        assertNull(probe.holdsWmLock())
        val lock = Any()
        probe.attachLock(lock)
        assertEquals(false, probe.holdsWmLock())
        probe.assertOffStarter("outside")
        synchronized(lock) {
            assertEquals(true, probe.holdsWmLock())
            probe.assertOffStarter("first")
            probe.assertOffStarter("first")
            probe.assertOffStarter("second")
            now = 9_999L
            probe.assertOffStarter("first")
            now = 10_000L
            probe.assertOffStarter("first")
        }
        assertEquals(listOf("STARTER_LOCK_VIOLATION_first", "STARTER_LOCK_VIOLATION_second", "STARTER_LOCK_VIOLATION_first"), logs)
        assertEquals(false, probe.holdsWmLock())
    }

    private class Service(val mGlobalLock: Any)
    private class Starter(val mService: Service)
    @Test fun discoversLockAndReportsMissingOnlyOnce() {
        val logs = mutableListOf<String>()
        val lock = Any()
        val probe = StarterLockProbe({ 0L }, logs::add)
        val starter = Starter(Service(lock))
        probe.attach(starter)
        synchronized(lock) { assertEquals(true, probe.holdsWmLock()) }
        assertTrue(logs.isEmpty())
        val missing = StarterLockProbe({ 0L }, logs::add)
        missing.attach(Any()); missing.attach(starter)
        assertNull(missing.holdsWmLock())
        assertEquals(listOf("STARTER_LOCK_PROBE_UNAVAILABLE"), logs)
    }

    private class Supervisor(val mService: Service)
    private class NestedStarter(val mSupervisor: Supervisor)
    @Test fun discoversLockThroughSupervisor() {
        val lock = Any()
        val probe = StarterLockProbe({ 0L }) { fail(it) }
        probe.attach(NestedStarter(Supervisor(Service(lock))))
        synchronized(lock) { assertEquals(true, probe.holdsWmLock()) }
    }

    @Test fun unknownProbeOnlyAllowsOuterExecute() {
        val unknown = StarterLockProbe({ 0L }) {}
        unknown.attach(Any())
        assertTrue(unknown.mayQueryOutsideLock(outerExecute = true))
        assertFalse(unknown.mayQueryOutsideLock(outerExecute = false))
        val lock = Any()
        val known = StarterLockProbe({ 0L }) {}
        known.attachLock(lock)
        assertTrue(known.mayQueryOutsideLock(outerExecute = false))
        synchronized(lock) { assertFalse(known.mayQueryOutsideLock(outerExecute = true)) }
    }
}
