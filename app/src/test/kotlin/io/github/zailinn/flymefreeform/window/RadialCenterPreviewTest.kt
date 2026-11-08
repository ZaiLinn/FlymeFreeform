package io.github.zailinn.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadialCenterPreviewTest {
    @Test
    fun cardCentersInSafeAreaWithoutLabel() {
        val layout = RadialCenterPreview.layout(1440f, 2600f, OverlaySafeInsets(top = 100f, bottom = 60f), density = 3f, labelWidth = null)
        assertEquals(288f, layout.iconSize, 1e-3f)
        assertEquals(720f, layout.iconCenterX, 1e-3f)
        assertEquals(100f + 2440f / 2f, layout.iconCenterY, 1e-3f)
        assertEquals(720f - 144f - 72f, layout.left, 1e-3f)
        assertNull(layout.labelBaseline)
    }

    @Test
    fun iconShrinksInNarrowAreaAndLabelWidensCard() {
        assertEquals(180f, RadialCenterPreview.iconSize(400f, 1000f, density = 3f), 1e-3f)
        assertEquals(144f, RadialCenterPreview.iconSize(1440f, 2600f, density = 3f, iconDp = 48f), 1e-3f)
        assertEquals(648f, RadialCenterPreview.iconSize(1440f, 2600f, density = 3f, iconDp = 300f), 1e-3f)
        val layout = RadialCenterPreview.layout(1440f, 2600f, OverlaySafeInsets(), density = 3f, labelWidth = 500f, labelAscent = -40f, labelDescent = 10f)
        assertEquals(500f + 144f, layout.right - layout.left, 1e-3f)
        val height = 72f + 288f + 43.2f + 50f + 72f
        assertEquals(height, layout.bottom - layout.top, 1e-3f)
        assertEquals(layout.iconCenterY + 144f + 43.2f + 40f, layout.labelBaseline!!, 1e-3f)
    }

    @Test
    fun labelScalesWithIconWithinLimits() {
        assertEquals(16f, RadialCenterPreview.labelSp(96f), 1e-3f)
        assertEquals(12f, RadialCenterPreview.labelSp(48f), 1e-3f)
        assertEquals(24f, RadialCenterPreview.labelSp(160f), 1e-3f)
    }
}
