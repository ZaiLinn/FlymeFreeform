package io.github.zailinn.flymefreeform.hook.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PM 查询不能假定在 WM 锁内安全：AOSP Android 15 ComputerEngine 的跨用户/最近任务
 * 查询会进入 ActivityManager。全部查询在主线程投递中执行，钩子只读缓存；冷缓存按未知放行。
 * 包广播后清空并重建，失败不缓存，避免启动早期上下文未就绪永久固定为未知。
 */
internal class StarterQueryCache(private val effects: StarterSideEffects) {
    private data class App(val uid: Int, val thirdParty: Boolean)
    private val apps = ConcurrentHashMap<String, App>()
    private val owners = ConcurrentHashMap<Int, String>()
    private val resolutions = ConcurrentHashMap<Intent.FilterComparison, String>()
    private val controlCenter = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val warming = AtomicBoolean(false)
    @Volatile private var initialized = false
    private var receiverRegistered = false

    fun warmUp() {
        if (initialized || !warming.compareAndSet(false, true)) return
        effects.post("query_warmup") { // starter-safety: allow 统一副作用出口，仅投递，不在当前线程查询
            try {
                val context = StarterSideEffects.systemContext() ?: return@post
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val installed = pm.getInstalledApplications(0)
                installed.forEach { info -> apps[info.packageName] = App(info.uid, info.flags and ApplicationInfo.FLAG_SYSTEM == 0) }
                installed.map { it.uid }.distinct().forEach { uid ->
                    pm.getPackagesForUid(uid)?.firstOrNull()?.let { owners[uid] = it }
                }
                if (!receiverRegistered) {
                    context.registerReceiver(object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            apps.clear(); owners.clear(); resolutions.clear()
                            initialized = false
                            warmUp()
                        }
                    }, IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_CHANGED)
                        addAction(Intent.ACTION_PACKAGE_REMOVED)
                        addDataScheme("package")
                    }, Context.RECEIVER_EXPORTED)
                    context.registerReceiver(object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            initialized = false
                            warmUp()
                        }
                    }, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_EXPORTED)
                    receiverRegistered = true
                }
                initialized = true
            } finally { warming.set(false) }
        }
    }

    fun uid(pkg: String): Int { ensureApp(pkg); return apps[pkg]?.uid ?: 0 }
    fun isThirdParty(pkg: String): Boolean { ensureApp(pkg); return apps[pkg]?.thirdParty ?: false }
    private fun ensureApp(pkg: String) {
        if (apps.containsKey(pkg)) return
        query("app:$pkg") {
            val info = StarterSideEffects.systemContext()?.packageManager?.getApplicationInfo(pkg, 0) ?: return@query
            apps[pkg] = App(info.uid, info.flags and ApplicationInfo.FLAG_SYSTEM == 0)
        }
    }
    fun owner(uid: Int): String? {
        if (uid <= 0) return null
        if (!owners.containsKey(uid)) query("uid:$uid") {
            StarterSideEffects.systemContext()?.packageManager?.getPackagesForUid(uid)?.firstOrNull()?.let { owners[uid] = it }
        }
        return owners[uid]
    }
    fun resolve(intent: Intent): String? {
        val key = Intent.FilterComparison(Intent(intent))
        resolutions[key]?.let { return it }
        query("resolve:${key.hashCode()}") {
            val target = StarterSideEffects.systemContext()?.packageManager?.resolveActivity(key.intent, 0)?.activityInfo?.packageName
            if (target != null) {
                if (resolutions.size >= 256) resolutions.clear()
                resolutions[key] = target
            }
        }
        return null
    }
    /** 厂商来源查询没有完整锁序证据，只在主线程刷新；短期缓存避免来源标记长期残留。 */
    fun fromControlCenter(pkg: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val cached = controlCenter[pkg]?.takeIf { now - it.second < 100L }
        query("control:$pkg") {
            val value = runCatching {
                val type = Class.forName("com.android.server.OplusListManagerImpl")
                val instance = type.getMethod("getInstance").invoke(null)
                type.getMethod("isFromControlCenterPkg", String::class.java).invoke(instance, pkg) as? Boolean ?: false
            }.getOrDefault(false)
            if (controlCenter.size >= 256) controlCenter.clear()
            controlCenter[pkg] = value to android.os.SystemClock.uptimeMillis()
        }
        return cached?.first ?: false
    }

    private fun query(key: String, block: () -> Unit) {
        if (!pending.add(key)) return
        effects.post("query") { // starter-safety: allow 统一副作用出口，仅投递，不在当前线程查询
            try { runCatching(block) } finally { pending.remove(key) }
        }
    }
}
