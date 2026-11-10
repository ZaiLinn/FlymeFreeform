package io.github.zailinn.flymefreeform.gesture

/**
 * 多圆弧扇形的容量规划（纯逻辑，App 与 system_server 共用）。
 *
 * 第 1 条是默认圆弧：放「数量上限」内的快捷项与「更多」入口，位置与单圆弧时完全一致。
 * 新增圆弧依次向内收拢，容量逐条递减（5、4、3），越往内容量越少；「更多」只出现在第 1 条。
 * 每条内侧圆弧的容量同时不超过外侧一条减 1：「数量上限」调小时内圈随之收缩，
 * 不会出现外圈 2 项、内圈 5 项的倒挂；容量降到 0 的圆弧不再出现。
 * 每圈实际放什么由目录按各圈固定项或最近使用决定（见 AppSelectionPolicy.innerArcItems）；
 * 目录未给出分圈结果时，[distribute] 按列表顺序先填满默认圆弧，再依次填入内侧圆弧。
 */
internal object RadialArcPlan {
    const val MIN_ARC_COUNT = 1
    const val MAX_ARC_COUNT = 4

    /** 第 2～4 条圆弧各自的容量。 */
    private val INNER_ARC_CAPACITIES = intArrayOf(5, 4, 3)

    /** 默认圆弧可放的快捷项上限，与「数量上限」的最大值一致。 */
    const val OUTER_ARC_MAX_ITEMS = 6

    /** 所有圆弧加起来最多能放的快捷项数（不含「更多」）。 */
    val MAX_TOTAL_ITEMS: Int = OUTER_ARC_MAX_ITEMS + INNER_ARC_CAPACITIES.sum()

    fun coerceArcCount(value: Int): Int = value.coerceIn(MIN_ARC_COUNT, MAX_ARC_COUNT)

    /** 生效的圆弧条数：关闭多圆弧时恒为 1。 */
    fun effectiveArcCount(multiArcEnabled: Boolean, arcCount: Int): Int =
        if (multiArcEnabled) coerceArcCount(arcCount) else MIN_ARC_COUNT

    /**
     * 内侧圆弧（第 2 条起）的容量列表。[outerLimit] 是默认圆弧的快捷项上限，
     * 每条内侧圆弧取「基准容量」与「外侧一条减 1」中的较小值，降到 0 即截止。
     */
    fun innerCapacities(arcCount: Int, outerLimit: Int = OUTER_ARC_MAX_ITEMS): List<Int> {
        val result = mutableListOf<Int>()
        var outer = outerLimit.coerceAtLeast(0)
        for (base in INNER_ARC_CAPACITIES.take(coerceArcCount(arcCount) - 1)) {
            val capacity = minOf(base, outer - 1)
            if (capacity <= 0) break
            result += capacity
            outer = capacity
        }
        return result
    }

    /** 扇形中最多显示的快捷项数（不含「更多」）。 */
    fun totalLimit(outerLimit: Int, arcCount: Int): Int =
        outerLimit.coerceAtLeast(0) + innerCapacities(arcCount, outerLimit).sum()

    /**
     * 第 [arcIndex] 条圆弧（0 为默认圆弧）可固定的项数：默认圆弧 6，内侧依次 5、4、3。
     * 与「数量上限」无关；上限调小时多出的固定项照常保存，只是扇形里不显示。
     */
    fun pinCapacity(arcIndex: Int): Int =
        if (arcIndex <= 0) OUTER_ARC_MAX_ITEMS else INNER_ARC_CAPACITIES.getOrElse(arcIndex - 1) { 0 }

    /**
     * 扇形各格所在的圈（0 为默认圆弧），下标与 RadialGeometry.layout 的 itemCenters 一致：
     * 默认圆弧快捷项 → 内侧各圈（由外向内）→「更多」（属于默认圆弧）。
     */
    fun arcOfIndex(arcItemCounts: List<Int>): IntArray {
        val result = IntArray(arcItemCounts.sum() + 1)
        var index = 0
        arcItemCounts.forEachIndexed { arc, count ->
            repeat(count.coerceAtLeast(0)) { result[index++] = arc }
        }
        return result
    }

    /**
     * 设置页预估的各圈条数（第 1 条不含「更多」），用于命中范围预览。默认圆弧按「数量上限」计（固定项不足时由补位填满）；
     * 内侧各圈有固定项时取固定项数，没有时补位开启则按容量、「不补位」则为 0。
     */
    fun expectedArcCounts(
        outerLimit: Int,
        arcCount: Int,
        innerPinCounts: List<Int>,
        fillInner: Boolean,
    ): List<Int> =
        listOf(outerLimit.coerceAtLeast(0)) +
            innerCapacities(arcCount, outerLimit).mapIndexed { index, capacity ->
                val pins = innerPinCounts.getOrElse(index) { 0 }
                when {
                    pins > 0 -> minOf(pins, capacity)
                    fillInner -> capacity
                    else -> 0
                }
            }

    /** 内侧圆弧条数上限（不含默认圆弧）。 */
    const val MAX_INNER_ARCS = MAX_ARC_COUNT - 1

    /**
     * 把 [itemCount] 个快捷项分到各条圆弧，返回每条圆弧上的快捷项数（第 1 条不含「更多」）。
     * 内侧圆弧为空时不出现在结果里。
     */
    fun distribute(itemCount: Int, outerLimit: Int, arcCount: Int): List<Int> {
        var remaining = itemCount.coerceAtLeast(0)
        val outer = minOf(remaining, outerLimit.coerceAtLeast(0))
        remaining -= outer
        val result = mutableListOf(outer)
        for (capacity in innerCapacities(arcCount, outerLimit)) {
            if (remaining <= 0) break
            val count = minOf(remaining, capacity)
            result += count
            remaining -= count
        }
        return result
    }

    /**
     * 内侧圆弧的半径间距（基准 dp）。三条以内按图标直径的 1.3 倍拉开；
     * 四条时整体收紧，保证最内一条仍能放下 3 个互不重叠的图标。
     */
    fun arcSpacingDp(arcCount: Int, outerRadiusDp: Float, iconDiameterDp: Float): Float {
        val count = coerceArcCount(arcCount)
        if (count <= 1) return 0f
        val preferred = iconDiameterDp * PREFERRED_SPACING_FACTOR
        val innermostMin = iconDiameterDp * INNERMOST_RADIUS_FACTOR
        val fitted = (outerRadiusDp - innermostMin) / (count - 1)
        return minOf(preferred, fitted).coerceAtLeast(iconDiameterDp)
    }

    /**
     * 第 2 条起各内侧圆弧的半径（基准 dp），由外向内。
     *
     * 三条以内各圈相距 [arcSpacingDp]。四条时圈距压到 47.4，而内圈图标逐圈缩小（[RadialIconScale.arcScale]），
     * 等距排开会外紧内松（相邻两圈图标边缘相距 4.5 / 7.0 / 9.3）；这里按相邻两圈图标的平均直径加同一段空隙分配圈距，
     * 并把最内圈往角落收到角落空白区（最内圈半径减最后一个圈距，见 RadialGeometry.layout）恰为 [MIN_DEAD_ZONE_DP]。
     * 结果圈距约 50.6 / 48.5 / 46.4，空隙约 7.7 / 8.1 / 8.3，外圈半径与图标大小不变。
     * 算出的空隙不合理或最内圈放不下时退回等距。
     */
    fun innerArcRadiiDp(arcCount: Int, outerRadiusDp: Float, iconDiameterDp: Float): List<Float> {
        val count = coerceArcCount(arcCount)
        if (count <= 1) return emptyList()
        val spacing = arcSpacingDp(count, outerRadiusDp, iconDiameterDp)
        val uniform = List(count - 1) { index -> outerRadiusDp - spacing * (index + 1) }
        if (count < MAX_ARC_COUNT) return uniform
        val diameters = List(count) { arc -> iconDiameterDp * RadialIconScale.arcScale(arc, count) }
        val averages = List(count - 1) { arc -> (diameters[arc] + diameters[arc + 1]) / 2f }
        // 空白区 = R − Σaverages − (count−1)·gap − (averages.last + gap) = MIN_DEAD_ZONE_DP。
        val gap = (outerRadiusDp - averages.sum() - averages.last() - MIN_DEAD_ZONE_DP) / count
        if (gap <= 0f) return uniform
        val radii = mutableListOf<Float>()
        var current = outerRadiusDp
        for (average in averages) {
            current -= average + gap
            radii += current
        }
        val innermost = radii.last()
        val innermostCapacity = innerCapacities(count).lastOrNull() ?: return uniform
        val chord = 2f * innermost * kotlin.math.sin(Math.toRadians(84.0 / innermostCapacity / 2.0)).toFloat()
        return if (chord >= diameters.last()) radii else uniform
    }

    /** 四条圆弧时角落空白区的下限（基准 dp）：手势刚识别时手指离角落约 15dp，留到 50 才不会一松手就误选最内一圈。 */
    private const val MIN_DEAD_ZONE_DP = 50f

    private const val PREFERRED_SPACING_FACTOR = 1.3f
    private const val INNERMOST_RADIUS_FACTOR = 2.27f
}
