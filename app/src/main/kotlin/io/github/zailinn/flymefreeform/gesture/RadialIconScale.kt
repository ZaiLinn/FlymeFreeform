package io.github.zailinn.flymefreeform.gesture

import kotlin.math.pow

/**
 * 扇形各圈图标的绘制大小。
 *
 * - 应用图标的可见本体铺满图标直径：系统图标四周带透明留白，按本体占位图边长的比例 [bodyFill] 放大，
 *   默认圆弧（第 0 圈）的应用图标因此与「更多」圆盘一样大；
 * - 内侧圆弧每向内一圈缩小到上一圈的 [innerStep]，外大内小、逐圈递减。两条圆弧时差别小，用更大的步进
 *   （[TWO_ARC_STEP]）才看得出；三四条时用 [MULTI_ARC_STEP]，最内一圈不至于太小。
 *
 * 只改绘制大小，不改圆心、圈距与命中判定。
 */
internal object RadialIconScale {
    const val TWO_ARC_STEP = 0.92f
    const val MULTI_ARC_STEP = 0.95f

    /** 留白补偿的上限，避免本体异常小的图标被放得过大。 */
    const val MAX_BODY_FILL = 1.25f

    fun innerStep(arcCount: Int): Float = if (arcCount <= 2) TWO_ARC_STEP else MULTI_ARC_STEP

    /** 第 [arc] 圈（0 为默认圆弧）相对默认圆弧的大小；[arcCount] 为当前圆弧条数。 */
    fun arcScale(arc: Int, arcCount: Int): Float =
        if (arc <= 0 || arcCount <= 1) 1f else innerStep(arcCount).pow(arc)

    /**
     * 让本体铺满直径的放大倍数。[bodyFraction] 为不透明本体包围盒边长占位图边长的比例；
     * 取不到或不在 (0, 1] 时不放大。
     */
    fun bodyFill(bodyFraction: Float): Float =
        if (bodyFraction > 0f && bodyFraction <= 1f) (1f / bodyFraction).coerceAtMost(MAX_BODY_FILL) else 1f
}
