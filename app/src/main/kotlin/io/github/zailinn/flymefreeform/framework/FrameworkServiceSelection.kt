package io.github.zailinn.flymefreeform.framework

import java.util.IdentityHashMap

/**
 * 只剔除已失效对象或包装同一底层连接的重复对象；多个不同有效连接仍保留。
 * 候选按优先级排列，最新绑定放在前面；无法确认底层身份时按独立对象处理。
 */
internal object FrameworkServiceSelection {
    fun <T : Any> obsolete(
        candidates: List<T>,
        alive: (T) -> Boolean,
        identity: (T) -> Any?,
    ): List<T> {
        val seen = IdentityHashMap<Any, Unit>()
        return candidates.filter { candidate ->
            if (!alive(candidate)) true
            else seen.put(identity(candidate) ?: candidate, Unit) != null
        }
    }
}
