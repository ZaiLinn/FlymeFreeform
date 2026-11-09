package io.github.zailinn.flymefreeform.platform.coloros

import org.junit.Assert.*
import org.junit.Test

class UserScopedCacheTest {
    private data class Context(val userId: Int)

    @Test fun secondaryUserQueriesUseTheirOwnContextAndReuseIt() {
        val created = mutableListOf<Int>()
        val contexts = UserScopedCache { id -> created += id; Context(id) }
        val primary = contexts.get(0)
        val secondary = contexts.get(10)
        assertEquals(0, primary.userId)
        assertEquals(10, secondary.userId)
        assertSame(secondary, contexts.get(10))
        assertEquals(listOf(0, 10), created)
    }

    @Test fun switchingBackDoesNotReusePreviousUsersObject() {
        val contexts = UserScopedCache(::Context)
        val initial = contexts.get(0)
        contexts.get(10)
        val returned = contexts.get(0)
        assertEquals(0, returned.userId)
        assertNotSame(initial, returned)
    }

    @Test fun failedCreationDoesNotCacheAnObjectForWrongUser() {
        var available = false
        val contexts = UserScopedCache { id ->
            check(id == 0 || available)
            Context(id)
        }
        contexts.get(0)
        assertThrows(IllegalStateException::class.java) { contexts.get(10) }
        available = true
        assertEquals(10, contexts.get(10).userId)
    }
}
