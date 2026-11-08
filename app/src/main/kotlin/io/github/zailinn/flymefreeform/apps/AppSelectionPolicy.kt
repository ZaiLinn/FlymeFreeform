package io.github.zailinn.flymefreeform.apps

internal object AppSelectionPolicy {
    fun <T> radialItems(
        pinsSaved: Boolean,
        availablePins: List<T>,
        recent: List<T>,
        all: List<T>,
        identity: (T) -> Any,
        limit: Int,
    ): List<T> =
        if (pinsSaved) {
            (availablePins + recent).distinctBy(identity).take(limit)
        } else {
            (recent + all).distinctBy(identity).take(limit)
        }

    /**
     * 内侧圆弧逐圈取条目。某圈有自定义固定项时只放这些固定项（放不满就空着）；
     * 没有自定义时用最近使用的应用补满。已在外侧出现的条目不再重复。
     *
     * [customized] 区分「用户固定了但都失效」与「从未自定义」：前者保持空圈，不偷偷换成最近使用。
     * 自动补满的圈会跳过其他圈固定的条目，固定项优先留在用户指定的那一圈。
     */
    fun <T> innerArcItems(
        capacities: List<Int>,
        customPins: List<List<T>>,
        customized: List<Boolean>,
        recent: List<T>,
        used: List<Any>,
        identity: (T) -> Any,
    ): List<List<T>> {
        val taken = used.toHashSet()
        // 其他圈明确固定的条目只留给那一圈，自动补满的圈不能先拿走。
        val reserved =
            capacities.indices
                .filter { customized.getOrElse(it) { false } }
                .flatMap { customPins.getOrElse(it) { emptyList() } }
                .mapTo(HashSet(), identity)
        return capacities.mapIndexed { index, capacity ->
            val custom = customized.getOrElse(index) { false }
            val source = if (custom) customPins.getOrElse(index) { emptyList() } else recent
            source
                .filter { identity(it) !in taken && (custom || identity(it) !in reserved) }
                .distinctBy(identity)
                .take(capacity.coerceAtLeast(0))
                .also { picked -> picked.forEach { taken += identity(it) } }
        }
    }

    fun <T> panelItems(
        recent: List<T>,
        all: List<T>,
        excluded: Set<Any>,
        identity: (T) -> Any,
    ): List<T> =
        (recent + all).distinctBy(identity).filterNot { identity(it) in excluded }
}
