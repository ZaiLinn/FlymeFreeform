package io.github.zailinn.flymefreeform.config

import org.junit.Assert.*
import org.junit.Test

class BlacklistEditsTest {
    @Test fun queuedAddsAndRemovesMergeIntoLatestSnapshot() {
        var current = listOf("old.app")
        current = BlacklistEdits.apply(current, "first.app", true)
        current = BlacklistEdits.apply(current, "second.app", true)
        current = BlacklistEdits.apply(current, "old.app", false)
        assertEquals(listOf("first.app", "second.app"), current)
    }
    @Test fun rapidToggleAndDuplicateDeliveryAreIdempotent() {
        var current = emptyList<String>()
        current = BlacklistEdits.apply(current, "first.app", true)
        current = BlacklistEdits.apply(current, "first.app", false)
        current = BlacklistEdits.apply(current, "second.app", true)
        current = BlacklistEdits.apply(current, "second.app", true)
        assertEquals(listOf("second.app"), current)
    }
}
