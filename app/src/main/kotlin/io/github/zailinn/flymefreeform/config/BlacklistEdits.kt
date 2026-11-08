package io.github.zailinn.flymefreeform.config

/** 对最新快照应用单项意图，不用旧 UI 列表覆盖后台已确认的变更。 */
internal object BlacklistEdits {
    fun apply(current: List<String>, packageName: String, excluded: Boolean): List<String> {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return current
        if (excluded && pkg in current) return current.distinct()
        val without = current.filterNot { it == pkg }
        return (if (excluded) without + pkg else without).distinct()
    }
}
