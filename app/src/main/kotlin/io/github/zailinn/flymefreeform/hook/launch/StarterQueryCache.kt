package io.github.zailinn.flymefreeform.hook.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import io.github.zailinn.flymefreeform.apps.CacheRevision
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules
import io.github.zailinn.flymefreeform.log.ModuleLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PM 查询可能进入 AMS，只在专用工作线程执行；starter 只读缓存，未知按原路径放行。
 * 先登记包变化监听，再预热；失效与写回按同一代数校验，拒绝旧查询重新填入缓存。
 * 启动目标只取当前 Request 的实际解析结果，不用跨用户、跨启动的 Intent 解析缓存。
 *
 * 缓存按包名记录「是否第三方」与主用户 UID，两者与当前用户无关；查询带 MATCH_UNINSTALLED_PACKAGES，
 * 只装在工作资料 / 分身里的应用也能命中。用户解锁、切换时只增量补充，不清空，
 * 否则重新预热的几百毫秒里所有应用都按「未知」处理，链接不开小窗、SystemUI 来源校验失败。
 *
 * 缩短开机冷窗口的两条补充：每次启动用系统已解析的目标 ApplicationInfo 与已核实的发起方 uid
 * 就地补缓存（[seedFromLaunch]）；预热先单查 SystemUI 与桌面，再读全量列表。
 * UID 一律按 appId（去掉用户号）保存，与主用户查询结果一致。
 */
internal class StarterQueryCache(private val effects: StarterSideEffects, private val onUserChanged: () -> Unit) {
    private data class App(val uid: Int, val thirdParty: Boolean)
    private val apps = ConcurrentHashMap<String, App>()
    /** 仅 SystemUI 与桌面：启动时直接观察到的 uid，PM 结果到位前使用。 */
    private val observedUids = ConcurrentHashMap<String, Int>()
    @Volatile private var warmUpRequestedAt = 0L
    private val owners = ConcurrentHashMap<Int, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val revision = CacheRevision()
    private val warming = AtomicBoolean(false)
    @Volatile private var initialized = false
    private var receiverRegistered = false

    fun warmUp() {
        if (initialized || !warming.compareAndSet(false, true)) return
        val requestedAt = SystemClock.uptimeMillis().also { warmUpRequestedAt = it }
        val accepted = effects.background("query_warmup") {
            try {
                val context = StarterSideEffects.systemContext() ?: return@background
                register(context)
                val generation = revision.current()
                val pm = context.packageManager
                // 来源校验依赖这两个 uid，先单查，不等全量列表。
                PRIORITY_PACKAGES.forEach { pkg ->
                    val info = runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull()
                    if (info != null) revision.commit(generation) { apps[pkg] = appOf(info) }
                }
                val priorityAt = SystemClock.uptimeMillis()
                @Suppress("DEPRECATION") val installed = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                val loaded = installed.associate { it.packageName to appOf(it) }
                if (!revision.commit(generation) { apps.putAll(loaded) }) return@background
                val appsAt = SystemClock.uptimeMillis()
                installed.map { appId(it.uid) }.distinct().forEach { uid ->
                    val owner = pm.getPackagesForUid(uid)?.firstOrNull()
                    if (owner != null) revision.commit(generation) { owners[uid] = owner }
                }
                initialized = revision.isCurrent(generation)
                val doneAt = SystemClock.uptimeMillis()
                // 常开：开机冷窗口长度只能在设备上量；每次预热（开机、用户解锁 / 切换）记一行。
                ModuleLog.info("STARTER_QUERY_WARMUP_READY_PRIORITY_" + (priorityAt - requestedAt) +
                    "MS_APPS_" + (appsAt - requestedAt) + "MS_" + loaded.size +
                    "_OWNERS_" + (doneAt - requestedAt) + "MS")
            } finally { warming.set(false) }
        }
        if (!accepted) warming.set(false)
    }

    /**
     * 用本次启动自带的信息补缓存；只读字段、只写内存，可在 starter 钩子内（含持 WM 锁时）调用。
     *
     * - 目标：Request 解析出的 ActivityInfo.applicationInfo，与 PM 查询同源，缺失时补入；
     * - 发起方：仅 SystemUI 与桌面，且须 callingUid == realCallingUid（由该进程直接发起，
     *   包名已由系统按 uid 核实），记下 uid 供来源校验，PM 结果到位后以 PM 为准。
     */
    fun seedFromLaunch(target: ApplicationInfo?, callingPackage: String?, callingUid: Int, realCallingUid: Int) {
        if (target != null && target.packageName != null && !apps.containsKey(target.packageName)) {
            apps.putIfAbsent(target.packageName, appOf(target))
        }
        if (callingPackage != null && callingPackage in PRIORITY_PACKAGES && callingUid > 0 &&
            callingUid == realCallingUid && !apps.containsKey(callingPackage)
        ) {
            observedUids.putIfAbsent(callingPackage, appId(callingUid))
        }
    }

    private fun appOf(info: ApplicationInfo) = App(appId(info.uid), info.flags and ApplicationInfo.FLAG_SYSTEM == 0)
    private fun appId(uid: Int) = if (uid < 0) uid else uid % LaunchRules.PER_USER_RANGE

    private fun register(context: Context) {
        if (receiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                revision.invalidate {
                    apps.remove(pkg)
                    observedUids.remove(pkg)
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
                // 新用户可能带来只装在该用户里的应用；补充预热，保留现有条目。
                initialized = false
                warmUp()
            }
        }, IntentFilter(Intent.ACTION_USER_UNLOCKED).apply {
            addAction("android.intent.action.USER_SWITCHED")
        }, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    fun uid(pkg: String): Int { ensureApp(pkg); return apps[pkg]?.uid ?: observedUids[pkg] ?: 0 }
    fun isThirdParty(pkg: String): Boolean { ensureApp(pkg); return apps[pkg]?.thirdParty ?: false }
    private fun ensureApp(pkg: String) {
        if (apps.containsKey(pkg)) return
        query("app:$pkg") {
            val generation = revision.current()
            val info = StarterSideEffects.systemContext()?.packageManager
                ?.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) ?: return@query
            revision.commit(generation) { apps[pkg] = appOf(info) }
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

    private companion object {
        val PRIORITY_PACKAGES = setOf(LaunchRules.SYSTEM_UI_PACKAGE, LaunchRules.LAUNCHER_PACKAGE)
    }

    private fun query(key: String, block: () -> Unit) {
        if (!pending.add(key)) return
        val accepted = effects.background("query") {
            try { block() } finally { pending.remove(key) }
        }
        if (!accepted) pending.remove(key)
    }
}
