package io.github.zailinn.flymefreeform.window

import org.junit.Assert.*
import org.junit.Test

class RadialCatalogUpdateTest {
    private data class Catalog(
        val targets: List<String>,
        val previewLoader: Any? = null,
        val panelLoader: Any? = null,
        val panel: List<String> = emptyList(),
        val provisional: Boolean = false,
    )

    private fun accept(old: Catalog, next: Catalog) = RadialCatalogUpdate.accept(old, next, Catalog::targets)

    @Test fun provisionalSnapshotUpgradesWithBothLoadersAndPanel() {
        val stored = Catalog(listOf("app"), provisional = true)
        val real = Catalog(listOf("app"), Any(), Any(), listOf("another app"))
        val adopted = accept(stored, real)
        assertSame(real, adopted)
        assertSame(real.previewLoader, adopted!!.previewLoader)
        assertSame(real.panelLoader, adopted.panelLoader)
        assertEquals(real.panel, adopted.panel)
        assertFalse(adopted.provisional)
    }

    @Test fun emptyGestureReceivesFirstRealCatalog() {
        val real = Catalog(listOf("app"), Any(), Any())
        assertSame(real, accept(Catalog(emptyList()), real))
    }

    @Test fun laterPanelPublicationIsAcceptedWithoutChangingTargets() {
        val radial = Catalog(listOf("a", "b"), Any(), Any())
        val complete = radial.copy(panel = listOf("c", "d"))
        assertSame(complete, accept(radial, complete))
    }

    @Test fun changedTargetUnderFingerWaitsForNextGesture() {
        assertNull(accept(Catalog(listOf("a")), Catalog(listOf("b"))))
    }

    @Test fun reorderedTargetsAndRemovalAreNotAdoptedMidGesture() {
        val old = Catalog(listOf("a", "b"))
        assertNull(accept(old, Catalog(listOf("b", "a"))))
        assertNull(accept(old, Catalog(emptyList())))
    }
}
