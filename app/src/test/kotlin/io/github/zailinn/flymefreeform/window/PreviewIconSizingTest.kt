package io.github.zailinn.flymefreeform.window

import org.junit.Assert.*
import org.junit.Test

class PreviewIconSizingTest {
    @Test fun renderBudgetCoversDisplayAndBodyMagnification() {
        assertEquals(720, PreviewIconSizing.renderSize(96f * 3f * 1.25f))
        assertEquals(1024, PreviewIconSizing.renderSize(160f * 4f * 1.25f))
        assertTrue(PreviewIconSizing.renderSize(800f) >= 800)
    }
    @Test fun invalidOrExtremeDimensionsRemainBounded() {
        assertEquals(1, PreviewIconSizing.renderSize(Float.NaN))
        assertEquals(1, PreviewIconSizing.renderSize(-1f))
        assertEquals(1024, PreviewIconSizing.renderSize(Float.MAX_VALUE))
    }
}
