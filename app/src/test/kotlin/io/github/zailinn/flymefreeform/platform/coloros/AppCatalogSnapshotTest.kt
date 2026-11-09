package io.github.zailinn.flymefreeform.platform.coloros

import io.github.zailinn.flymefreeform.config.ModuleSettingsSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogSnapshotTest {
    @Test fun oldUserResultIsRejectedEvenWhenSettingsAreIdentical() {
        val settings = ModuleSettingsSnapshot()
        val primary = AppCatalogSnapshot(settings = settings, userId = 0)
        assertFalse(primary.usableFor(10, settings))
        assertTrue(primary.usableFor(0, settings))
    }

    @Test fun secondaryUserResultCanOnlyBePublishedForItsOwner() {
        val settings = ModuleSettingsSnapshot()
        val secondary = AppCatalogSnapshot(settings = settings, userId = 10)
        assertTrue(secondary.usableFor(10, settings))
        assertFalse(secondary.usableFor(0, settings))
        assertFalse(secondary.usableFor(11, settings))
    }

    @Test fun sameUserStillRejectsOutdatedSelectionAndIconShape() {
        val settings = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = settings, userId = 10)
        assertFalse(completed.usableFor(10, settings.copy(pinsSaved = true)))
        assertFalse(completed.usableFor(10, settings.copy(iconShape = settings.iconShape + 1)))
    }

    @Test
    fun lateCatalogCannotReplaceNewerSelection() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested))
        assertFalse(completed.matches(requested.copy(pinsSaved = true)))
    }

    @Test
    fun unrelatedGestureSettingsDoNotInvalidatePreparedIcons() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested.copy(cornerTriggerRangeDp = 100, enabled = true)))
    }
}
