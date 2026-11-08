package io.github.zailinn.flymefreeform.hook.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import io.github.zailinn.flymefreeform.apps.CacheRevision
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PM 查询可能进入 AMS，只在专用工作线程执行；starter 只读缓存，未知按原路径放行。
 * 先登记包变化监听，再预热；失效与写回按同一代数校验，拒绝旧查询重新填入缓存。
 * 启动目标只取当前 Request 的实际解析结果，不用跨用户、跨启动的 Intent 解析缓存。
 */
internal class StarterQueryCache(private val effects: StarterSideEffects, private val onUserChanged: () -> Unit) {
    private data class App(val uid: Int, val thirdParty: Boolean)
    private val apps = ConcurrentHashMap<String, App>()
    private val owners = ConcurrentHashMap<Int, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val revision = CacheRevision()
    private val warming = AtomicBoolean(false)
    @Volatile private var initialized = false
    private var receiverRegistered = false

    fun warmUp() {
        if (initialized || !warming.compareAndSet(false, true)) return
        val accepted = effects.background("query_warmup") {
            try {
                val context = StarterSideEffects.systemContext() ?: return@background
                register(context)
                val generation = revision.current()
                val pm = context.packageManager
                @Suppress("DEPRECATION") val installed = pm.getInstalledApplications(0)
                val loaded = installed.associate { it.packageName to App(it.uid, it.flags and ApplicationInfo.FLAG_SYSTEM == 0) }
                if (!revision.commit(generation) { apps.putAll(loaded) }) return@background
                installed.map { it.uid }.distinct().forEach { uid ->
                    val owner = pm.getPackagesForUid(uid)?.firstOrNull()
                    if (owner != null) revision.commit(generation) { owners[uid] = owner }
                }
                initialized = revision.isCurrent(generation)
            } finally { warming.set(false) }
        }
        if (!accepted) warming.set(false)
    }

    private fun register(context: Context) {
        if (receiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                revision.invalidate {
                    apps.remove(pkg)
                    owners.entries.removeIf { it.value == pkg }
                }
                ensureApp(pkg)
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }, Context.RECEIVER_EXPORTED)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.intent.action.USER_SWITCHED") onUserChanged()
                revision.invalidate { apps.clear(); owners.clear() }
                initialized = false
                warmUp()
            }
        }, IntentFilter(Intent.ACTION_USER_UNLOCKED).apply {
            addAction("android.intent.action.USER_SWITCHED")
        }, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    fun uid(pkg: String): Int { ensureApp(pkg); return apps[pkg]?.uid ?: 0 }
    fun isThirdParty(pkg: String): Boolean { ensureApp(pkg); return apps[pkg]?.thirdParty ?: false }
    private fun ensureApp(pkg: String) {
        if (apps.containsKey(pkg)) return
        query("app:$pkg") {
            val generation = revision.current()
            val info = StarterSideEffects.systemContext()?.packageManager?.getApplicationInfo(pkg, 0) ?: return@query
            revision.commit(generation) { apps[pkg] = App(info.uid, info.flags and ApplicationInfo.FLAG_SYSTEM == 0) }
        }
    }
    fun owner(uid: Int): String? {
        if (uid <= 0) return null
        if (!owners.containsKey(uid)) query("uid:$uid") {
            val generation = revision.current()
            val owner = StarterSideEffects.systemContext()?.packageManager?.getPackagesForUid(uid)?.firstOrNull()
            if (owner != null) revision.commit(generation) { owners[uid] = owner }
        }
        return owners[uid]
    }
    /** 仅供探针确认未持 WM 锁的当前启动调用；不使用跨启动的短期来源缓存。 */
    fun readControlCenter(pkg: String): Boolean = runCatching {
        val type = Class.forName("com.android.server.OplusListManagerImpl")
        val instance = type.getMethod("getInstance").invoke(null)
        type.getMethod("isFromControlCenterPkg", String::class.java).invoke(instance, pkg) as? Boolean ?: false
    }.getOrDefault(false)

    private fun query(key: String, block: () -> Unit) {
        if (!pending.add(key)) return
        val accepted = effects.background("query") {
            try { block() } finally { pending.remove(key) }
        }
        if (!accepted) pending.remove(key)
    }
}
