package io.github.zailinn.flymefreeform.config

import io.github.zailinn.flymefreeform.gesture.RadialArcPlan

/**
 * 各圈固定项的整体操作。下标 0 是默认圆弧，1–3 是内侧各圈（含当前未启用的圈）。
 */
internal object ArcPins {
    /**
     * 把 [target] 移到第 [toArc] 圈末尾，并从其余各圈移除。
     * 目标圈已含该条目、已满或下标越界时返回 null（不做任何改动）。
     * 源圈因此变空时保持空列表：内侧圈随即按补位方式填充。
     */
    fun <T> move(arcs: List<List<T>>, target: T, toArc: Int): List<List<T>>? {
        val destination = arcs.getOrNull(toArc) ?: return null
        if (target in destination) return null
        if (destination.size >= RadialArcPlan.pinCapacity(toArc)) return null
        return arcs.mapIndexed { index, targets ->
            if (index == toArc) targets + target else targets.filterNot { it == target }
        }
    }
}
