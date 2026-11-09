package io.github.zailinn.flymefreeform.window

/** 手势中保留目标身份与顺序；目标未变时采用整份新快照，包含加载器与面板。 */
internal object RadialCatalogUpdate {
    fun <T, K> accept(current: T, next: T, targets: (T) -> List<K>): T? {
        val previous = targets(current)
        return next.takeIf { previous.isEmpty() || previous == targets(next) }
    }
}
