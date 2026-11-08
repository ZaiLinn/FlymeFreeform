package io.github.zailinn.flymefreeform.platform.coloros

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class CancellableWorkTest {
    @Test fun cancellationBeforeWorkerStartsReleasesPreparation() {
        val queued = ArrayDeque<Runnable>()
        val worker = CancellableWork(Executor { queued.addLast(it) })
        var active = true; var preparing = true; var actions = 0
        worker.submit({ active }, { actions++ }, { fail("cancel must not fail") }, { preparing = false })
        active = false
        queued.removeFirst().run()
        assertEquals(0, actions)
        assertFalse(preparing)
    }
    @Test fun rejectedQueueReleasesPreparationAndReportsOnce() {
        val worker = CancellableWork(Executor { throw RejectedExecutionException("full") })
        var failures = 0; var completions = 0
        worker.submit({ true }, { fail("rejected action must not run") },
            { assertTrue(it is RejectedExecutionException); failures++ }, { completions++ })
        assertEquals(1, failures)
        assertEquals(1, completions)
    }
    @Test fun checkedFailureCompletesBeforeNextRequest() {
        val queued = ArrayDeque<Runnable>(); val calls = mutableListOf<String>()
        val worker = CancellableWork(Executor { queued.addLast(it) })
        worker.submit({ true }, { throw IOException("Binder-like checked failure") },
            { calls += "failed" }, { calls += "released" })
        worker.submit({ true }, { calls += "next" }, { fail("unexpected") }, { calls += "done" })
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(listOf("failed", "released", "next", "done"), calls)
    }
    @Test fun fatalErrorStillReleasesState() {
        val queued = ArrayDeque<Runnable>(); var complete = false
        val worker = CancellableWork(Executor { queued.addLast(it) })
        worker.submit({ true }, { throw AssertionError("fatal") }, { fail("fatal must propagate") }, { complete = true })
        assertThrows(AssertionError::class.java) { queued.removeFirst().run() }
        assertTrue(complete)
    }
}
