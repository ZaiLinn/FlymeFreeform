package io.github.zailinn.flymefreeform.gesture

import kotlin.math.pow

/**
 * 扇形各圈图标的绘制大小。
 *
 * - 应用图标的可见本体铺满图标直径：系统图标四周带透明留白，按本体占位图边长的比例 [bodyFill] 放大，
 *   默认圆弧（第 0 圈）的应用图标因此与「更多」圆盘一样大；
 * - 三条以内圆弧时各圈一样大（与 Flyme 一致，圈距 57.2 足够）；四条时圈距压到 47.4，
 *   内侧圆弧每向内一圈缩小到上一圈的 [FOUR_ARC_STEP]，避免圈与圈相切。
 *
 * 四条时与改动前相同。只改绘制大小，不改圆心、圈距与命中判定。
 */
internal object RadialIconScale {
    const val FOUR_ARC_STEP = 0.95f

    /** 留白补偿的上限，避免本体异常小的图标被放得过大。 */
    const val MAX_BODY_FILL = 1.25f

    /** 第 [arc] 圈（0 为默认圆弧）相对默认圆弧的大小；[arcCount] 为当前圆弧条数。 */
    fun arcScale(arc: Int, arcCount: Int): Float =
        if (arc <= 0 || arcCount < RadialArcPlan.MAX_ARC_COUNT) 1f else FOUR_ARC_STEP.pow(arc)

    /**
     * 让本体铺满直径的放大倍数。[bodyFraction] 为不透明本体包围盒边长占位图边长的比例；
     * 取不到或不在 (0, 1] 时不放大。
     */
    fun bodyFill(bodyFraction: Float): Float =
        if (bodyFraction > 0f && bodyFraction <= 1f) (1f / bodyFraction).coerceAtMost(MAX_BODY_FILL) else 1f
}
