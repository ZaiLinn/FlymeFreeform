package io.github.zailinn.flymefreeform.gesture

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialArcPlanTest {
    @Test
    fun innerArcsShrinkInCapacity() {
        assertEquals(emptyList<Int>(), RadialArcPlan.innerCapacities(1))
        assertEquals(listOf(5), RadialArcPlan.innerCapacities(2))
        assertEquals(listOf(5, 4), RadialArcPlan.innerCapacities(3))
        assertEquals(listOf(5, 4, 3), RadialArcPlan.innerCapacities(4))
        assertEquals(listOf(5, 4, 3), RadialArcPlan.innerCapacities(9))
    }

    @Test
    fun innerArcsNeverHoldMoreThanTheArcOutsideThem() {
        assertEquals(listOf(3, 2, 1), RadialArcPlan.innerCapacities(4, outerLimit = 4))
        assertEquals(listOf(1), RadialArcPlan.innerCapacities(4, outerLimit = 2))
        assertEquals(emptyList<Int>(), RadialArcPlan.innerCapacities(4, outerLimit = 1))
        assertEquals(listOf(5, 4, 3), RadialArcPlan.innerCapacities(4, outerLimit = 6))
        for (outer in 1..6) {
            val capacities = listOf(outer) + RadialArcPlan.innerCapacities(4, outer)
            capacities.zipWithNext().forEach { (outside, inside) -> assertTrue(inside < outside) }
        }
    }

    @Test
    fun limitsFollowArcCount() {
        assertEquals(6, RadialArcPlan.totalLimit(6, 1))
        assertEquals(15, RadialArcPlan.totalLimit(6, 3))
        assertEquals(5, RadialArcPlan.totalLimit(3, 2))
        assertEquals(1, RadialArcPlan.totalLimit(1, 4))
        assertEquals(listOf(6, 5, 4, 3, 0), (0..4).map(RadialArcPlan::pinCapacity))
        assertEquals(RadialArcPlan.MAX_TOTAL_ITEMS, (0..3).sumOf(RadialArcPlan::pinCapacity))
        assertEquals(1, RadialArcPlan.effectiveArcCount(false, 4))
        assertEquals(4, RadialArcPlan.effectiveArcCount(true, 4))
    }

    @Test
    fun itemsFillDefaultArcFirstThenInnerArcs() {
        assertEquals(listOf(4), RadialArcPlan.distribute(4, 6, 3))
        assertEquals(listOf(6, 5, 2), RadialArcPlan.distribute(13, 6, 3))
        assertEquals(listOf(6, 5, 4), RadialArcPlan.distribute(40, 6, 3))
        assertEquals(listOf(3, 2, 1), RadialArcPlan.distribute(15, 3, 4))
        assertEquals(listOf(0), RadialArcPlan.distribute(0, 6, 4))
    }

    @Test
    fun innermostArcStillFitsItsIconsWithoutOverlap() {
        for (arcCount in 2..4) {
            val spacing = RadialArcPlan.arcSpacingDp(arcCount, 242f, 44f)
            assertTrue(spacing >= 44f)
            val capacities = RadialArcPlan.innerCapacities(arcCount)
            capacities.forEachIndexed { index, capacity ->
                val radius = 242f - spacing * (index + 1)
                val stepRadians = 84f / capacity * PI.toFloat() / 180f
                val chord = 2f * radius * sin(stepRadians / 2f)
                assertTrue("arcs=$arcCount inner=$index chord=$chord", chord >= 44f)
            }
        }
    }

    @Test
    fun multiArcLayoutKeepsListOrderAndMoreLast() {
        val counts = RadialArcPlan.distribute(13, 6, 3)
        val layout =
            RadialGeometry.layout(
                side = CornerSide.Right,
                width = 1000f,
                height = 2000f,
                radius = 242f,
                itemCount = counts.first() + 1,
                innerArcCounts = counts.drop(1),
                arcSpacing = 57f,
            )
        assertEquals(14, layout.itemCenters.size)
        fun distance(point: GesturePoint) = hypot(point.x - layout.origin.x, point.y - layout.origin.y)
        layout.itemCenters.take(6).forEach { assertEquals(242f, distance(it), 0.01f) }
        layout.itemCenters.subList(6, 11).forEach { assertEquals(185f, distance(it), 0.01f) }
        layout.itemCenters.subList(11, 13).forEach { assertEquals(128f, distance(it), 0.01f) }
        // 「更多」仍在默认圆弧最靠近底边的槽位。
        assertEquals(242f, distance(layout.itemCenters.last()), 0.01f)
        assertTrue(layout.itemCenters.last().y > layout.itemCenters.take(6).maxOf { it.y })
        // 单圆弧布局不受新参数影响。
        val single = RadialGeometry.layout(CornerSide.Right, 1000f, 2000f, 242f, 7)
        assertEquals(single.itemCenters, RadialGeometry.layout(CornerSide.Right, 1000f, 2000f, 242f, 7, innerArcCounts = emptyList(), arcSpacing = 57f).itemCenters)
    }

    @Test
    fun cornerDeadZoneBlocksSelectionUntilInnermostArc() {
        val spacing = RadialArcPlan.arcSpacingDp(4, 242f, 44f)
        val counts = RadialArcPlan.distribute(18, 6, 4)
        val layout =
            RadialGeometry.layout(
                side = CornerSide.Right,
                width = 400f,
                height = 890f,
                radius = 242f,
                itemCount = counts.first() + 1,
                innerArcCounts = counts.drop(1),
                arcSpacing = spacing,
                iconDiameter = 44f,
            )
        val innermost = 242f - spacing * 3
        assertEquals(innermost - spacing, layout.minSelectDistance, 0.01f)
        assertTrue(layout.minSelectDistance >= 50f)
        // 手势刚识别时手指离角落约 15dp：即便命中范围拉到最大 140dp 也不选中。
        assertEquals(null, RadialGeometry.selection(layout, 400f - 14f, 890f - 4f, null, 140f, 0f))
        // 落在最内一圈图标上仍可选中。
        val target = layout.itemCenters[15]
        assertEquals(15, RadialGeometry.selection(layout, target.x, target.y, null, 100f, 0f))
        // 单圆弧不设空白区，保持原有手感。
        assertEquals(0f, RadialGeometry.layout(CornerSide.Right, 400f, 890f, 242f, 7, iconDiameter = 44f).minSelectDistance, 0f)
    }

    @Test
    fun innermostArcKeepsOneSpacingOfReachTowardCorner() {
        for (arcCount in 2..4) {
            val spacing = RadialArcPlan.arcSpacingDp(arcCount, 242f, 44f)
            val counts = RadialArcPlan.distribute(18, 6, arcCount)
            val layout =
                RadialGeometry.layout(
                    side = CornerSide.Right,
                    width = 400f,
                    height = 890f,
                    radius = 242f,
                    itemCount = counts.first() + 1,
                    innerArcCounts = counts.drop(1),
                    arcSpacing = spacing,
                    iconDiameter = 44f,
                )
            val innermost = 242f - spacing * (arcCount - 1)
            assertEquals("arcs=$arcCount", innermost - spacing, layout.minSelectDistance, 0.01f)
            assertTrue("arcs=$arcCount", layout.minSelectDistance >= 50f)
        }
    }

    @Test
    fun selectionReachesInnerArcItems() {
        val layout =
            RadialGeometry.layout(
                side = CornerSide.Left,
                width = 1000f,
                height = 2000f,
                radius = 600f,
                itemCount = 7,
                innerArcCounts = listOf(5),
                arcSpacing = 140f,
                iconDiameter = 100f,
            )
        layout.itemCenters.forEachIndexed { index, center ->
            assertEquals(index, RadialGeometry.selection(layout, center.x, center.y, null, 100f, 0f))
        }
    }

    @Test
    fun arcOfIndexFollowsLayoutOrderWithMoreOnDefaultArc() {
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 0), RadialArcPlan.arcOfIndex(listOf(2, 3, 1)).toList())
        assertEquals(listOf(0, 0, 0), RadialArcPlan.arcOfIndex(listOf(2, 0)).toList())
    }

    @Test
    fun expectedArcCountsReflectCustomPinsAndFillMode() {
        assertEquals(listOf(6, 2, 4, 3), RadialArcPlan.expectedArcCounts(6, 4, listOf(2, 0, 0), fillInner = true))
        assertEquals(listOf(6, 2, 0, 0), RadialArcPlan.expectedArcCounts(6, 4, listOf(2, 0, 0), fillInner = false))
        // 固定项超过本圈容量时按容量计；「数量上限」调小时内圈容量随之收缩。
        assertEquals(listOf(3, 2), RadialArcPlan.expectedArcCounts(3, 2, listOf(5), fillInner = true))
    }
}
