package io.github.zailinn.flymefreeform.window

import org.junit.Assert.*
import org.junit.Test

class VisibleIconRequestTest {
    @Test fun leavingRowReleasesImageAndCancelsRequest() {
        var displayed: Any? = null
        var receive: ((Any) -> Unit)? = null
        var cancellations = 0
        val session = VisibleIconRequest<Any> { displayed = it }
        session.start(null) { callback -> receive = callback; { cancellations++ } }
        val icon = Any()
        val deliver = requireNotNull(receive)
        deliver(icon)
        assertSame(icon, displayed)
        session.close()
        assertNull(displayed)
        assertEquals(1, cancellations)
        deliver(Any())
        assertNull(displayed)
        session.close()
        assertEquals(1, cancellations)
    }

    @Test fun loaderReplacementStartsRequestEvenWhenOldRowHadAnImage() {
        var oldImage: String? = null
        val old = VisibleIconRequest<String> { oldImage = it }
        old.start("old") { fail("cached row must not render again"); {} }
        var newImage: String? = null
        var receive: ((String) -> Unit)? = null
        val next = VisibleIconRequest<String> { newImage = it }
        old.close()
        next.start(null) { callback -> receive = callback; {} }
        assertNull(oldImage)
        assertNotNull(receive)
        receive!!("updated")
        assertEquals("updated", newImage)
        old.close()
        assertEquals("updated", newImage)
        next.close()
    }

    @Test fun returningToCachedRowShowsImageWithoutAnotherRequest() {
        var displayed: String? = null
        val session = VisibleIconRequest<String> { displayed = it }
        session.start("cached") { fail("cache hit must not issue a request"); {} }
        assertEquals("cached", displayed)
        session.close()
        assertNull(displayed)
    }

    @Test fun queuedOldCallbackCannotOverwriteNewLoaderResult() {
        var oldCallback: ((String) -> Unit)? = null
        var oldImage: String? = null
        val old = VisibleIconRequest<String> { oldImage = it }
        old.start(null) { callback -> oldCallback = callback; {} }
        old.close()
        var nextImage: String? = null
        val next = VisibleIconRequest<String> { nextImage = it }
        next.start("new") { fail("cached"); {} }
        oldCallback!!("late old image")
        assertNull(oldImage)
        assertEquals("new", nextImage)
        next.close()
    }

    @Test fun traversingManyRowsKeepsOnlyVisibleImages() {
        val held = mutableMapOf<Int, Any>()
        val visible = ArrayDeque<VisibleIconRequest<Any>>()
        repeat(500) { row ->
            val session = VisibleIconRequest<Any> { image ->
                if (image == null) held.remove(row) else held[row] = image
            }
            session.start(Any()) { fail("cached"); {} }
            visible.addLast(session)
            if (visible.size > 24) visible.removeFirst().close()
            assertTrue(held.size <= 24)
        }
        visible.forEach { it.close() }
        assertTrue(held.isEmpty())
    }
}
