package io.github.zailinn.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelAlphabetTest {
    private val pinyin = mapOf('微' to 'w', '支' to 'z', '安' to 'a', '淘' to 't')
    private val han = { c: Char -> pinyin[c] }

    @Test
    fun bucketsByLatinPinyinAndOther() {
        assertEquals('W', PanelAlphabet.bucketOf("微信", han))
        assertEquals('A', PanelAlphabet.bucketOf("安全中心", han))
        assertEquals('Q', PanelAlphabet.bucketOf("qq", han))
        assertEquals('B', PanelAlphabet.bucketOf("  Bilibili", han))
        assertEquals('C', PanelAlphabet.bucketOf("ｃool", han))
        assertEquals(PanelAlphabet.OTHER, PanelAlphabet.bucketOf("12306", han))
        assertEquals(PanelAlphabet.OTHER, PanelAlphabet.bucketOf("", han))
        assertEquals(PanelAlphabet.OTHER, PanelAlphabet.bucketOf("龘", han))
    }

    @Test
    fun targetFallsForwardThenBackward() {
        val firsts = PanelAlphabet.firstIndices(listOf('A', 'A', 'C', 'W', '#'), offset = 10)
        assertEquals(mapOf('A' to 10, 'C' to 12, 'W' to 13, '#' to 14), firsts)
        assertEquals(12, PanelAlphabet.targetFor('C', firsts))
        assertEquals(12, PanelAlphabet.targetFor('B', firsts))
        assertEquals(14, PanelAlphabet.targetFor('X', firsts))
        val noOther = PanelAlphabet.firstIndices(listOf('A', 'C'))
        assertEquals(1, PanelAlphabet.targetFor('Z', noOther))
        assertNull(PanelAlphabet.targetFor('A', emptyMap()))
    }
}
