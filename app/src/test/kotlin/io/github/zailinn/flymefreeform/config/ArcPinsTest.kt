package io.github.zailinn.flymefreeform.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArcPinsTest {
    private val arcs = listOf(listOf("a", "b"), listOf("x"), emptyList(), listOf("h1", "h2"))

    @Test
    fun movesEntryToEndOfTargetArcAndRemovesItElsewhere() {
        assertEquals(
            listOf(listOf("a"), listOf("x", "b"), emptyList(), listOf("h1", "h2")),
            ArcPins.move(arcs, "b", 1),
        )
    }

    @Test
    fun emptiedSourceArcStaysEmptySoItFallsBackToFill() {
        assertEquals(
            listOf(listOf("a", "b", "x"), emptyList(), emptyList(), listOf("h1", "h2")),
            ArcPins.move(arcs, "x", 0),
        )
    }

    @Test
    fun entriesFromDisabledArcsCanBeMovedIn() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("x", "h2"), emptyList(), listOf("h1")),
            ArcPins.move(arcs, "h2", 1),
        )
    }

    @Test
    fun refusesFullTargetDuplicateOrUnknownArc() {
        val fullSecond = listOf(listOf("a"), listOf("1", "2", "3", "4", "5"), emptyList(), emptyList())
        assertNull(ArcPins.move(fullSecond, "a", 1))
        assertNull(ArcPins.move(arcs, "x", 1))
        assertNull(ArcPins.move(arcs, "a", 4))
    }
}
