package io.github.zailinn.flymefreeform.window

import io.github.zailinn.flymefreeform.config.ModulePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class MoreButtonColorsTest {
    @Test fun lightPlatesUseDarkDots() {
        assertEquals(MoreButtonColors.DARK_DOT, MoreButtonColors.dotFor(0xFFFFFFFF.toInt()))
        assertEquals(MoreButtonColors.DARK_DOT, MoreButtonColors.dotFor(0xFFFFEB3B.toInt()))
        assertEquals(MoreButtonColors.DARK_DOT, MoreButtonColors.dotFor(0xFF8BC34A.toInt()))
    }

    @Test fun darkPlatesUseLightDots() {
        assertEquals(MoreButtonColors.LIGHT_DOT, MoreButtonColors.dotFor(0xFF000000.toInt()))
        assertEquals(MoreButtonColors.LIGHT_DOT, MoreButtonColors.dotFor(0xFF1565C0.toInt()))
        assertEquals(MoreButtonColors.LIGHT_DOT, MoreButtonColors.dotFor(0xFFB71C1C.toInt()))
    }

    @Test fun translucentWhiteOverDimmedScreenUsesLightDots() {
        assertEquals(MoreButtonColors.LIGHT_DOT, MoreButtonColors.dotFor(0x33FFFFFF))
        assertEquals(MoreButtonColors.DARK_DOT, MoreButtonColors.dotFor(0xE6FFFFFF.toInt()))
    }

    @Test fun zeroFollowsSystemAndAlphaHasFloor() {
        assertEquals(0, ModulePreferences.coerceRadialMoreColor(0))
        assertEquals(0x33336699, ModulePreferences.coerceRadialMoreColor(0x00336699))
        assertEquals(0x80336699.toInt(), ModulePreferences.coerceRadialMoreColor(0x80336699.toInt()))
    }

    @Test fun pickedTransparentBlackStaysCustom() {
        assertEquals(0x33000000, ModulePreferences.customRadialMoreColor(0))
        assertEquals(0x33000000, ModulePreferences.coerceRadialMoreColor(ModulePreferences.customRadialMoreColor(0)))
    }
}
