package io.github.zailinn.flymefreeform.hook.launch.decision

/** 已接管的启动必须结束于一次成功启动或显式失败；状态变化时保留原启动参数。 */
internal object DeferredLaunch {
    fun run(delayMs: Long, valid: () -> Boolean, close: () -> Boolean,
        schedule: (Long, () -> Unit) -> Unit, start: (Boolean) -> Boolean,
        complete: (Boolean, Boolean, Boolean) -> Unit, current: () -> Boolean = { true }) {
        fun attempt(zoom: Boolean) = runCatching { start(zoom) }.getOrDefault(false)
        if (!current()) { complete(false, false, false); return }
        if (!valid()) { complete(false, false, attempt(false)); return }
        val closed = runCatching(close).getOrDefault(false)
        schedule(if (closed) delayMs else 0L) {
            if (!current()) { complete(closed, false, false); return@schedule }
            val zoom = valid()
            val zoomStarted = zoom && attempt(true)
            val started = zoomStarted || attempt(false)
            complete(closed, zoomStarted, started)
        }
    }
}
