package io.github.zailinn.flymefreeform.apps

import io.github.zailinn.flymefreeform.config.ModulePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconShapesTest {
    private fun disc(size: Int, radius: Float): (Int, Int) -> Int = { x, y ->
        val dx = x + 0.5f - size / 2f
        val dy = y + 0.5f - size / 2f
        if (dx * dx + dy * dy <= radius * radius) 255 else 0
    }

    @Test
    fun filledDiscIsRoundBadge() {
        assertTrue(IconShapes.isRoundBadge(100, 100, disc(100, 50f)))
    }

    @Test
    fun opaqueSquareIsNotRoundBadge() {
        assertFalse(IconShapes.isRoundBadge(100, 100) { _, _ -> 255 })
    }

    @Test
    fun ringWithTransparentEdgeIsNotRoundBadge() {
        // 圆只占画面中间一小块时，边缘内侧取样点是透明的：交给包围盒裁切后再判定。
        assertFalse(IconShapes.isRoundBadge(100, 100, disc(100, 30f)))
    }

    @Test
    fun roundedSquareAppIconIsNotRoundBadge() {
        // ColorOS 应用图标是圆角方形；圆角比例 0.25–0.35 都不能被当成圆形底的工具图标。
        for (fraction in listOf(0.25f, 0.3f, 0.35f)) {
            val size = 100
            val radius = size * fraction
            val alpha = { x: Int, y: Int ->
                val cx = (x + 0.5f).coerceIn(radius, size - radius)
                val cy = (y + 0.5f).coerceIn(radius, size - radius)
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                if (dx * dx + dy * dy <= radius * radius) 255 else 0
            }
            assertFalse("fraction $fraction", IconShapes.isRoundBadge(size, size, alpha))
        }
    }

    @Test
    fun opaqueBoundsFindsPaddedDisc() {
        val bounds = IconShapes.opaqueBounds(100, 100, disc(100, 40f))
        assertEquals(listOf(10, 10, 89, 89), bounds)
    }

    @Test
    fun opaqueBoundsOfEmptyImageIsNull() {
        assertNull(IconShapes.opaqueBounds(10, 10) { _, _ -> 0 })
    }

    @Test
    fun cornerFractionFollowsShapeAndRejectsUnknown() {
        assertEquals(0.5f, IconShapes.cornerFraction(ModulePreferences.ICON_SHAPE_CIRCLE))
        assertEquals(IconShapes.SQUARE_CORNER_FRACTION, IconShapes.cornerFraction(ModulePreferences.ICON_SHAPE_SQUARE))
        assertEquals(0.5f, IconShapes.cornerFraction(9))
    }
}
