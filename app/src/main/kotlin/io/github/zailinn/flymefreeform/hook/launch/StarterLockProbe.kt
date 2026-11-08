package io.github.zailinn.flymefreeform.hook.launch

import java.lang.ref.WeakReference

/** 只观察当前线程的 WM 锁持有状态；探针失效或违规均不抛异常。 */
internal class StarterLockProbe(
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {
    @Volatile private var lock = WeakReference<Any>(null)
    @Volatile private var attempted = false
    private val reported = HashMap<String, Long>()

    fun attach(starter: Any?) {
        if (attempted) return
        synchronized(this) {
            if (attempted) return
            val global = runCatching {
                val service = field(starter, "mService")
                field(service, "mGlobalLock")
            }.getOrNull()
            lock = WeakReference(global)
            attempted = true
            if (global == null) log("STARTER_LOCK_PROBE_UNAVAILABLE")
        }
    }

    /** 纯 JVM 锁测试入口，与反射取得的锁采用同一种弱引用。 */
    internal fun attachLock(value: Any) { lock = WeakReference(value); attempted = true }

    fun holdsWmLock(): Boolean? = lock.get()?.let { Thread.holdsLock(it) }

    fun assertOffStarter(name: String) {
        if (holdsWmLock() != true) return
        val now = clock()
        val emit = synchronized(reported) {
            val previous = reported[name]
            if (previous != null && now - previous < 10_000L) false
            else { reported[name] = now; true }
        }
        if (emit) log("STARTER_LOCK_VIOLATION_" + name)
    }

    private fun field(instance: Any?, name: String): Any? {
        var type: Class<*>? = instance?.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) { type = type.superclass }
        }
        return null
    }

    companion object {
        /** 仅限 system_server 内共享探针；不用于跨进程通信。 */
        @Volatile var current: StarterLockProbe? = null
    }
}
