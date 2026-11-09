package io.github.zailinn.flymefreeform.apps

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CacheRevisionTest {
    @Test fun queuedOldCallbackIsRejectedAfterCacheInvalidation() {
        val gate = CacheRevision()
        val requested = gate.current()
        val cache = mutableMapOf<String, String>()
        assertTrue(gate.commit(requested) { cache["app"] = "old" })
        var displayed: String? = null
        val callback = { if (gate.isCurrent(requested)) displayed = "old" }
        gate.invalidate { cache.clear() }
        callback()
        assertNull(displayed)
        assertTrue(cache.isEmpty())
        val next = gate.current()
        assertTrue(gate.commit(next) { cache["app"] = "new" })
        assertEquals("new", cache["app"])
    }

    @Test fun invalidationBetweenRenderingAndCommitRejectsOldIcon() {
        val gate = CacheRevision()
        val requested = gate.current()
        val cache = mutableMapOf("other" to "unchanged")
        gate.invalidate { cache.remove("app") }
        assertFalse(gate.commit(requested) { cache["app"] = "stale" })
        assertEquals(mapOf("other" to "unchanged"), cache)
    }

    @Test fun renderingStartedBeforeInvalidationCannotRepopulateCache() {
        val gate = CacheRevision()
        val cache = mutableMapOf<String, String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val worker = Thread {
            val revision = gate.current()
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            accepted.set(gate.commit(revision) { cache["app"] = "old shape" })
        }
        worker.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        gate.invalidate { cache.clear() }
        assertTrue(gate.commit(gate.current()) { cache["app"] = "new shape" })
        release.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertFalse(accepted.get())
        assertEquals("new shape", cache["app"])
    }
}
