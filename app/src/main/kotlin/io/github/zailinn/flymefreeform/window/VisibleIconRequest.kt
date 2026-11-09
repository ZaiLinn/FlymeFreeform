package io.github.zailinn.flymefreeform.window

/** 一次可见格子的加载会话；离开后拒绝迟到结果，并释放图像引用。仅在 UI 线程调用。 */
internal class VisibleIconRequest<T>(private val changed: (T?) -> Unit) {
    private var active = true
    private var started = false
    private var cancel: (() -> Unit)? = null

    fun start(cached: T?, request: ((T) -> Unit) -> (() -> Unit)) {
        check(active && !started)
        started = true
        changed(cached)
        if (cached != null) return
        val cancellation = request { value -> if (active) changed(value) }
        if (active) cancel = cancellation else cancellation()
    }

    fun close() {
        if (!active) return
        active = false
        cancel?.invoke()
        cancel = null
        changed(null)
    }
}
