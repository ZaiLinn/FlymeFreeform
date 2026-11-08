package io.github.zailinn.flymefreeform.hook.launch.decision

import org.junit.Assert.*
import org.junit.Test

class LaunchMemoriesTest {
    @Test fun profileDoesNotSwallowPrimaryUsersLinkOrReturn() {
        val memories = LaunchMemories()
        memories.forUser(0).markLink("caller|target|link", 1000)
        memories.forUser(0).markZoomed("target", false, 1000)
        memories.forUser(0).record("caller", "target", 1000)
        assertFalse(memories.forUser(10).duplicate("caller|target|link", 1010))
        assertFalse(memories.forUser(10).selfRelaunch("target", 1010))
        assertFalse(memories.forUser(10).checkReturning("target", "caller", 1010).returning)
        assertTrue(memories.forUser(0).duplicate("caller|target|link", 1010))
    }
    @Test fun primaryTrackerRemainsSharedAndUserHistoryIsStable() {
        val primary = LaunchMemory(); val memories = LaunchMemories(primary)
        assertSame(primary, memories.forUser(0))
        val profile = memories.forUser(999)
        assertSame(profile, memories.forUser(999))
        assertNotSame(primary, profile)
    }
}
