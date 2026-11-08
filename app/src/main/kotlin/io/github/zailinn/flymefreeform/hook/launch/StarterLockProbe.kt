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
            val global = runCatching { findGlobalLock(starter) }.getOrNull()
            lock = WeakReference(global)
            attempted = true
            if (global == null) log("STARTER_LOCK_PROBE_UNAVAILABLE")
        }
    }

    /** 纯 JVM 锁测试入口，与反射取得的锁采用同一种弱引用。 */
    internal fun attachLock(value: Any) { lock = WeakReference(value); attempted = true }

    fun holdsWmLock(): Boolean? = lock.get()?.let { Thread.holdsLock(it) }

    /**
     * 控制中心等未知锁序查询的放行条件：探针确认未持锁时放行；确认持锁时禁止。
     * 探针不可用时只在最外层 execute 放行（AOSP 在进入 mGlobalLock 之前调用 execute 与 resolveActivity），
     * 与改造前「每次直接查询」相比只收紧不放宽，避免探针失效时控制中心入口整体失效。
     */
    fun mayQueryOutsideLock(outerExecute: Boolean): Boolean = when (holdsWmLock()) {
        false -> true
        true -> false
        null -> outerExecute
    }

    /** 依次尝试 ATMS 的常见持有路径，再按类型在 ATMS 上查找 WindowManagerGlobalLock。 */
    private fun findGlobalLock(starter: Any?): Any? {
        val services = listOf(field(starter, "mService"), field(field(starter, "mSupervisor"), "mService"),
            field(field(starter, "mController"), "mService")).filterNotNull()
        services.forEach { service -> field(service, "mGlobalLock")?.let { return it } }
        services.forEach { service ->
            var type: Class<*>? = service.javaClass
            while (type != null) {
                type.declaredFields.firstOrNull { it.type.name == GLOBAL_LOCK_CLASS }?.let {
                    it.isAccessible = true
                    return it.get(service)
                }
                type = type.superclass
            }
        }
        return null
    }

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
        private const val GLOBAL_LOCK_CLASS = "com.android.server.wm.WindowManagerGlobalLock"
    }
}
