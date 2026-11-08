package io.github.zailinn.flymefreeform.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionPolicyTest {
    @Test
    fun firstUsePrefersRecentAppsAndFillsRemainingSlots() {
        val recent = (1..7).map { "recent-$it" }
        assertEquals(
            recent.take(6),
            AppSelectionPolicy.radialItems(false, emptyList(), recent, listOf("other"), { it }, 6),
        )
        assertEquals(
            listOf("recent", "other"),
            AppSelectionPolicy.radialItems(false, emptyList(), listOf("recent"), listOf("recent", "other"), { it }, 6),
        )
    }

    @Test
    fun savedPinsPreferPinnedEntriesThenRecentApps() {
        assertEquals(
            listOf("available", "recent"),
            AppSelectionPolicy.radialItems(true, listOf("available"), listOf("recent"), listOf("other"), { it }, 6),
        )
        assertEquals(
            listOf("recent"),
            AppSelectionPolicy.radialItems(true, emptyList(), listOf("recent"), listOf("other"), { it }, 6),
        )
    }

    @Test
    fun innerArcsUseCustomPinsOrFallBackToRecents() {
        val arcs =
            AppSelectionPolicy.innerArcItems(
                capacities = listOf(5, 4, 3),
                customPins = listOf(emptyList(), listOf("x", "outer", "y"), emptyList()),
                customized = listOf(false, true, false),
                recent = listOf("outer", "r1", "r2", "x", "r3", "r4", "r5", "r6", "r7", "r8"),
                used = listOf("outer"),
                identity = { it },
            )
        // 第 2 圈未自定义：最近使用补满，跳过外圈已有的与第 3 圈固定的条目。
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), arcs[0])
        // 第 3 圈自定义：只放固定项，外圈已有的跳过，放不满就空着。
        assertEquals(listOf("x", "y"), arcs[1])
        // 第 4 圈未自定义：接着补最近使用。
        assertEquals(listOf("r6", "r7", "r8"), arcs[2])
    }

    @Test
    fun customizedArcWithOnlyMissingPinsStaysEmpty() {
        val arcs =
            AppSelectionPolicy.innerArcItems(
                capacities = listOf(5),
                customPins = listOf(emptyList<String>()),
                customized = listOf(true),
                recent = listOf("r1"),
                used = emptyList(),
                identity = { it },
            )
        assertEquals(listOf(emptyList<String>()), arcs)
    }
}
