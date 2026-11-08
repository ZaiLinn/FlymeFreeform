package io.github.zailinn.flymefreeform.framework

import org.junit.Assert.*
import org.junit.Test

class FrameworkServiceSelectionTest {
    private class Service(val binder: Any? = Any(), var alive: Boolean = true)
    private fun obsolete(vararg services: Service) = FrameworkServiceSelection.obsolete(services.toList(), { it.alive }, { it.binder })

    @Test fun deadOldServiceDoesNotBlockNewBinding() {
        val old = Service(alive = false); val current = Service()
        assertEquals(listOf(old), obsolete(current, old))
        assertTrue(obsolete(current).isEmpty())
    }
    @Test fun twoWrappersForSameBinderKeepNewestCandidate() {
        val binder = Any(); val old = Service(binder); val current = Service(binder)
        assertEquals(listOf(old), obsolete(current, old))
    }
    @Test fun distinctLiveBindersRemainDistinct() {
        assertTrue(obsolete(Service(), Service()).isEmpty())
    }
    @Test fun missingBinderIdentityNeverDiscardsUnknownLiveServices() {
        assertTrue(obsolete(Service(null), Service(null)).isEmpty())
    }
    @Test fun laterDeathClearsCandidateOnNextReconciliation() {
        val old = Service(); val current = Service()
        assertTrue(obsolete(current, old).isEmpty())
        old.alive = false
        assertEquals(listOf(old), obsolete(current, old))
    }
    @Test fun binderIdentityUsesReferenceRatherThanMetadataEquality() {
        val first = String(charArrayOf('a')); val second = String(charArrayOf('a'))
        assertEquals(first, second); assertNotSame(first, second)
        assertTrue(obsolete(Service(first), Service(second)).isEmpty())
    }
}
