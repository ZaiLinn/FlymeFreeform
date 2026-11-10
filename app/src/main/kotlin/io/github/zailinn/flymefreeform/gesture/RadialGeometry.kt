package io.github.zailinn.flymefreeform.gesture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class GesturePoint(val x: Float, val y: Float)

internal data class RadialLayout(
    val side: CornerSide,
    val origin: GesturePoint,
    val radius: Float,
    val itemCenters: List<GesturePoint>,
    /**
     * 手指离圆心小于该距离时不选中任何条目。只在有内侧圆弧时生效：
     * 最内一圈离角落很近，没有这段空白，手势刚识别时手指就已落进命中圈，立即松手会误启动应用。
     */
    val minSelectDistance: Float = 0f,
)

internal object RadialGeometry {
    /**
     * 圆心相对安全区底边上移的距离（基准 dp）：整个扇形一起抬高，最下面一排图标离屏幕底边更远。
     * 15 对齐 Flyme：其截图圆心在底边上方约 40px（1080 宽，约 15 基准 dp）。扇形窗口实测拿不到导航栏底部边距，
     * 不上移时圆心就在屏幕底边。调用方把它乘上自己的像素比例后从 [layout] 的 height 里减掉；左右位置不变。
     */
    const val BOTTOM_LIFT_BASE_DP = 15f

    private const val SPAN_DEGREES = 84f
    private const val SAFE_DEGREES = 3f

    /**
     * [itemCount] 是默认圆弧（最外侧）的槽位数，含「更多」。
     *
     * [innerArcCounts] 依次是第 2、3、4 条圆弧上的快捷项数。各圈半径取 [innerArcRadii]（与 [innerArcCounts] 同序）；
     * 未给出时每条圆弧比上一条向内收 [arcSpacing]。
     * 返回的 itemCenters 顺序为：默认圆弧的快捷项 → 内侧圆弧的快捷项（由外向内）→「更多」，
     * 因此下标与快捷项列表一一对应，最后一格仍是「更多」。
     */
    fun layout(
        side: CornerSide,
        width: Float,
        height: Float,
        radius: Float,
        itemCount: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        innerArcCounts: List<Int> = emptyList(),
        arcSpacing: Float = 0f,
        iconDiameter: Float = 0f,
        innerArcRadii: List<Float> = emptyList(),
    ): RadialLayout {
        fun arcRadius(arcIndex: Int): Float =
            (innerArcRadii.getOrNull(arcIndex) ?: (radius - arcSpacing * (arcIndex + 1))).coerceAtLeast(0f)
        val origin = GesturePoint(offsetX + if (side == CornerSide.Left) 0f else width, offsetY + height)
        if (itemCount <= 0) return RadialLayout(side, origin, radius, emptyList())
        val outer = arcCenters(side, origin, radius, itemCount)
        if (innerArcCounts.none { it > 0 }) return RadialLayout(side, origin, radius, outer)
        val inner =
            innerArcCounts.flatMapIndexed { arcIndex, count ->
                if (count <= 0) emptyList() else arcCenters(side, origin, arcRadius(arcIndex), count)
            }
        // 角落空白区：最内一圈往里再留一个圈距给它自己，更靠近角落才视为未选中。
        // 以图标内沿为界时两圈的第二圈只剩约 50 基准 dp 宽，难以选中；
        // 留一个圈距后各圈数下边界仍在 50 基准 dp 以上，手势刚识别时（离角落约 15dp）不会误选。
        val innermostArc = innerArcCounts.indexOfLast { it > 0 }
        val innermostRadius = arcRadius(innermostArc)
        // 「一个圈距」取最内一圈与它外侧一圈的实际半径差（各圈圈距可以不同）。
        val lastSpacing = (if (innermostArc == 0) radius else arcRadius(innermostArc - 1)) - innermostRadius
        val deadZone =
            minOf(innermostRadius - lastSpacing, innermostRadius - iconDiameter / 2f).coerceAtLeast(0f)
        return RadialLayout(side, origin, radius, outer.dropLast(1) + inner + outer.last(), deadZone)
    }

    private fun arcCenters(
        side: CornerSide,
        origin: GesturePoint,
        radius: Float,
        itemCount: Int,
    ): List<GesturePoint> {
        val step = SPAN_DEGREES / itemCount
        return List(itemCount) { index ->
            // “更多”占最靠近角落的 0 号槽；应用按列表顺序从外侧向角落排列，
            // 因此列表第一个应用使用最外侧的槽位，避免设置页与扇形视觉顺序相反。
            val slot = itemCount - index - 1
            val angle =
                if (side == CornerSide.Left) {
                    -SAFE_DEGREES - (slot + 0.5f) * step
                } else {
                    -180f + SAFE_DEGREES + (slot + 0.5f) * step
                }
            val radians = angle * PI.toFloat() / 180f
            GesturePoint(
                x = origin.x + cos(radians) * radius,
                y = origin.y + sin(radians) * radius,
            )
        }
    }

    fun selection(
        layout: RadialLayout,
        x: Float,
        y: Float,
        previous: Int?,
        enterRadius: Float,
        keepRadius: Float,
    ): Int? {
        if (layout.radius <= 0f) return null
        if (layout.minSelectDistance > 0f &&
            hypot(x - layout.origin.x, y - layout.origin.y) < layout.minSelectDistance
        ) {
            return null
        }
        // 不再做弧线收边：只按「离哪个图标最近」判定，手指偏离弧线也能接着选。
        // keepRadius <= 0 表示没有保持圈：换格只看谁更近（两格中线处切换），不做迟滞。
        if (previous != null && keepRadius > 0f && previous in layout.itemCenters.indices) {
            val center = layout.itemCenters[previous]
            if (hypot(x - center.x, y - center.y) <= keepRadius) return previous
        }
        return layout.itemCenters
            .indices
            .minByOrNull { index ->
                val center = layout.itemCenters[index]
                hypot(x - center.x, y - center.y)
            }
            ?.takeIf { index ->
                val center = layout.itemCenters[index]
                hypot(x - center.x, y - center.y) <= enterRadius
            }
    }

    fun polarAngle(layout: RadialLayout, point: GesturePoint): Float =
        atan2(point.y - layout.origin.y, point.x - layout.origin.x)
}
