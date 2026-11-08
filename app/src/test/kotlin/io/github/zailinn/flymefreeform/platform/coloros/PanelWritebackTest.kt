package io.github.zailinn.flymefreeform.platform.coloros

import org.junit.Assert.*
import org.junit.Test

class PanelWritebackTest {
    @Test fun searchAndNativeModeAreCheckedWhenCallbackRuns() {
        val gate = PanelWriteback(); val adapter = Any(); var query = ""; var native = false; var writes = 0
        gate.bind(adapter, { query.isEmpty() && !native }, { writes++ })
        val completion = gate.completion()
        query = "calculator"; completion(); assertEquals(0, writes)
        query = ""; native = true; completion(); assertEquals(0, writes)
        native = false; completion(); assertEquals(1, writes)
    }
    @Test fun oldPanelCallbackCannotWriteIntoNewSession() {
        val gate = PanelWriteback(); val old = Any(); val next = Any(); var writes = 0
        gate.bind(old, { true }, { writes++ })
        val completion = gate.completion()
        gate.detach(old)
        gate.bind(next, { true }, { writes++ })
        completion(); assertEquals(0, writes)
        gate.detach(old)
        assertTrue(gate.accepts(next))
        gate.completion()(); assertEquals(1, writes)
        gate.detach(next)
        assertFalse(gate.accepts(next))
    }
}
