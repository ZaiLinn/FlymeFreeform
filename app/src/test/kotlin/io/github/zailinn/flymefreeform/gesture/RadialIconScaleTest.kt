package io.github.zailinn.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class RadialIconScaleTest {
    @Test
    fun innerArcsKeepFullSizeUpToThreeArcsAndShrinkOnlyWithFour() {
        assertEquals(1f, RadialIconScale.arcScale(0, 4), 0f)
        assertEquals(1f, RadialIconScale.arcScale(1, 1), 0f)
        assertEquals(1f, RadialIconScale.arcScale(1, 2), 0f)
        assertEquals(1f, RadialIconScale.arcScale(2, 3), 0f)
        assertEquals(0.95f, RadialIconScale.arcScale(1, 4), 1e-6f)
        assertEquals(0.857375f, RadialIconScale.arcScale(3, 4), 1e-6f)
    }

    @Test
    fun bodyFillCompensatesTransparentPaddingWithinLimit() {
        assertEquals(1f / 0.86f, RadialIconScale.bodyFill(0.86f), 1e-6f)
        assertEquals(1f, RadialIconScale.bodyFill(1f), 0f)
        assertEquals(RadialIconScale.MAX_BODY_FILL, RadialIconScale.bodyFill(0.3f), 0f)
        assertEquals(1f, RadialIconScale.bodyFill(0f), 0f)
        assertEquals(1f, RadialIconScale.bodyFill(1.5f), 0f)
    }
}
