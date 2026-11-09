package io.github.zailinn.flymefreeform.hook.launch

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.zailinn.flymefreeform.hook.ModuleEnvironmentState
import io.github.zailinn.flymefreeform.hook.ProcessConfiguration
import io.github.zailinn.flymefreeform.hook.launch.decision.*
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.ACTIVITY_STARTER_CLASS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.ZOOM_MANAGER_CLASS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.LAUNCH_AT_RIGHT
import io.github.zailinn.flymefreeform.log.ModuleLog
import io.github.zailinn.flymefreeform.log.VerboseLog

/** 仅反射读取启动快照、执行纯判定结果；所有跨锁副作用经过统一投递出口。 */
internal class ActivityStarterZoomInjector(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState? = null,
    private val interAppTracker: InterAppLaunchTracker? = null,
) {
    private var installedHooks = 0
    private val preempting = ThreadLocal<Boolean>()
    private val controlCenter = ThreadLocal<Pair<Any, MutableMap<String, Boolean>>>()
    private val pendingReopens = PendingLaunches()
    private val memories = LaunchMemories(interAppTracker?.memory ?: LaunchMemory())
    private fun memory(input: StarterInput) = memories.forUser(input.userId)
    private val lockProbe = StarterLockProbe(SystemClock::uptimeMillis) { module.log(Log.WARN, TAG, it) }
    private val effects = StarterSideEffects(lockProbe) { code, error -> module.log(Log.WARN, TAG, code, error) }
    private val queries = StarterQueryCache(effects, pendingReopens::clear)
    private val platform = StarterLaunchEnvironment(configuration, environment, queries)
    private fun now() = SystemClock.uptimeMillis()

    fun install(classLoader: ClassLoader) {
        StarterLockProbe.current = lockProbe
        // 不在安装时预热：此时 AMS 尚未就绪，registerReceiver 抛 NPE；首次启动经过钩子时再预热。
        val starter =
            try {
                classLoader.loadClass(ACTIVITY_STARTER_CLASS)
            } catch (exception: ClassNotFoundException) {
                module.log(Log.WARN, TAG, "ZOOM_STARTER_UNAVAILABLE", exception)
                return
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "ZOOM_STARTER_LINKAGE_FAILED", exception)
                return
            }
        val targets =
            starter.declaredMethods.filter { method ->
                method.name == "execute" || method.name == "executeRequest"
            }
        if (targets.isEmpty()) {
            module.log(Log.WARN, TAG, "ZOOM_STARTER_METHODS_MISSING", null)
            return
        }
        targets.forEach { method ->
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.zoom.starter." + method.name + "." + method.parameterCount)
                .intercept { chain ->
                    lockProbe.attach(chain.thisObject)
                    val previousSource = controlCenter.get()
                    val ownsSource = method.name == "execute"
                    if (ownsSource) controlCenter.set(chain.thisObject!! to mutableMapOf())
                    try {
                    queries.warmUp()
                    runCatching { trace(chain.thisObject, method.name) }
                    val input = runCatching { readSnapshot(chain.thisObject, method.name) }
                        .onFailure { module.log(Log.WARN, TAG, "ZOOM_PREEMPT_FAILED", it) }.getOrNull()
                    val preempted = input != null && runCatching {
                        executePreempt(input, LaunchDecider.decidePreempt(input.snapshot, environmentFor(input), memory(input), now()))
                    }.onFailure { module.log(Log.WARN, TAG, "ZOOM_PREEMPT_FAILED", it) }.getOrDefault(false)
                    if (preempted) 0 else {
                        if (input != null) runCatching {
                            val injectionInput = readSnapshot(chain.thisObject, method.name)
                            if (injectionInput != null) {
                                traceEntry(injectionInput)
                                executeInject(injectionInput, LaunchDecider.decideInject(injectionInput.snapshot, environmentFor(injectionInput), memory(injectionInput), now()))
                            }
                        }.onFailure { module.log(Log.WARN, TAG, "ZOOM_INJECT_FAILED", it) }
                        chain.proceed()
                    }
                    } finally {
                        if (ownsSource) {
                            if (previousSource == null) controlCenter.remove() else controlCenter.set(previousSource)
                        }
                    }
                }
            installedHooks++
        }
        starter.declaredClasses.firstOrNull { it.simpleName == "Request" }?.declaredMethods
            ?.filter { it.name == "resolveActivity" }?.forEach { method ->
                method.isAccessible = true
                module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val values = controlCenter.get()?.second
                            val request = chain.thisObject
                            if (values != null && request != null && lockProbe.mayQueryOutsideLock(outerExecute = true)) {
                                val intent = readField(request, "intent") as? Intent
                                val target = intent?.component?.packageName ?: intent?.getPackage()
                                if (target != null) values[target] = queries.readControlCenter(target)
                            }
                        }
                        result
                    }
            }
        module.log(Log.INFO, TAG, "ZOOM_STARTER_HOOK_INSTALLED_" + installedHooks, null)
    }

    private data class StarterInput(val starter: Any, val request: Any?, val intent: Intent,
        val options: Bundle?, val snapshot: LaunchSnapshot, val userId: Int)

    /** 只读 starter 与 Request 字段；不在构造快照时执行系统查询。 */
    private fun readSnapshot(starter: Any?, stage: String): StarterInput? {
        if (starter == null || !configuration.snapshot.enabled) return null
        val request = readField(starter, "mRequest")
        val intent = readField(starter, "mIntent") as? Intent
            ?: request?.let { readField(it, "intent") } as? Intent ?: return null
        val rawUid = intField(starter, "mCallingUid")
        val uid = rawUid.takeIf { it > 0 } ?: request?.let { intField(it, "callingUid") } ?: 0
        val activity = (request?.let { readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo)?.activityInfo
            ?: request?.let { readField(it, "activityInfo") } as? android.content.pm.ActivityInfo
        val resolved = activity?.packageName
        val options = optionsBundle(starter, request)
        val snapshot = LaunchSnapshot(
            stage = stage, action = intent.action, dataString = intent.dataString, flags = intent.flags,
            componentPackage = intent.component?.packageName, componentClass = intent.component?.className,
            explicitPackage = intent.getPackage(), resolvedTarget = resolved,
            callingPackage = request?.let { readField(it, "callingPackage") } as? String,
            callingUid = uid, rawCallingUid = rawUid, realCallingUid = intField(starter, "mRealCallingUid"),
            requestRealCallingUid = request?.let { intField(it, "realCallingUid") } ?: -1,
            callingPid = request?.let { intField(it, "callingPid") } ?: 0,
            hasResultTo = request?.let { readField(it, "resultTo") } != null,
            requestCode = request?.let { intField(it, "requestCode") } ?: 0,
            hasPendingIntent = request?.let { readField(it, "originatingPendingIntent") } != null,
            hasIntentGrants = request?.let { readField(it, "intentGrants") } != null,
            zoomSourceExtra = intent.getIntExtra(ZoomLaunchSpec.EXTRA_SOURCE, 0),
            zoomAppliedExtra = intent.getIntExtra(ZoomLaunchSpec.EXTRA_APPLIED, 0) == 1,
            quickSettingsExtra = intent.getBooleanExtra(LaunchRules.EXTRA_QUICK_SETTINGS, false),
            optionsAlreadyZoom = ZoomLaunchSpec.alreadyZoom(options),
            optionsFullscreen = ZoomLaunchSpec.explicitFullscreen(options), preempting = preempting.get() == true,
        )
        // 先用本次启动自带的信息补缓存，再做判定：目标的 ApplicationInfo 已由系统解析好，
        // 发起方的包名与 uid 已由系统核实；均为字段读取，不调用系统服务。
        queries.seedFromLaunch(activity?.applicationInfo, snapshot.callingPackage, snapshot.callingUid, snapshot.realCallingUid)
        return StarterInput(starter, request, intent, options, snapshot,
            request?.let { readField(it, "userId") as? Int } ?: -1)
    }

    private fun environmentFor(input: StarterInput): LaunchEnvironment = object : LaunchEnvironment by platform {
        override val enabled get() = input.userId >= 0 && platform.enabled
        override fun resolveImplicitTarget(): String? = input.snapshot.resolvedTarget
        /** AOSP execute 在进入 mGlobalLock 前调用；以探针为准，持锁时禁止，探针不可用时仅最外层 execute 查询。 */
        override fun fromControlCenter(pkg: String): Boolean {
            val observation = controlCenter.get()?.takeIf { it.first === input.starter }?.second ?: return false
            observation[pkg]?.let { return it }
            if (!lockProbe.mayQueryOutsideLock(input.snapshot.stage == "execute")) return false
            return queries.readControlCenter(pkg).also { observation[pkg] = it }
        }
    }

    private fun applyEvents(decision: LaunchDecision, memory: LaunchMemory) {
        decision.events.forEach { event -> when (event) {
            is LaunchEvent.Log -> ModuleLog.debug(event.tag) { event.code }
            is LaunchEvent.ReturnUpdate -> memory.apply(event.check)
        } }
        if (decision is LaunchDecision.PassThrough) decision.log?.let { code -> ModuleLog.debug(TAG) { code } }
    }

    private fun record(caller: String, target: String, memory: LaunchMemory) {
        if (interAppTracker == null) return
        memory.record(caller, target, now())
        ModuleLog.debug("FFINTER") { "INTER_APP_RECORD " + caller + "->" + target }
    }

    private fun executePreempt(input: StarterInput, decision: LaunchDecision): Boolean {
        val memory = memory(input)
        applyEvents(decision, memory)
        return when (decision) {
            is LaunchDecision.SwallowDuplicate -> true
            is LaunchDecision.ReopenInZoom -> {
                val replay = StarterRequestReplay.capture(input.starter, input.request, input.intent)
                if (!effects.canCloseZoomTask() || replay == null) {
                    memory.markZoomed(decision.target, false, now())
                    ModuleLog.debug(TAG) { "ZOOM_REOPEN_UNAVAILABLE_" + input.snapshot.stage + "_" + decision.target }
                    false
                } else {
                    val key = input.userId.toString() + "|" + decision.target
                    val fingerprint = decision.linkKey ?: input.intent.toUri(0)
                    val ticket = pendingReopens.acquire(key, fingerprint) ?: return true
                    val settings = configuration.snapshot
                    ModuleLog.debug(TAG) { "ZOOM_REOPEN_SCHEDULED_" + input.snapshot.stage + "_" + decision.target }
                    effects.reopenInZoom(decision.target, input.userId, LaunchRules.REOPEN_DELAY_MS,
                        valid = { configuration.snapshot == settings && platform.enabled && platform.zoomLaunch &&
                            platform.deviceUnlocked(false) && !platform.isExcluded(decision.target) &&
                            effects.isUserVisible(input.userId) },
                        start = { zoom ->
                            preempting.set(true)
                            try { replay.start(if (zoom) ZoomLaunchSpec.bundle(null) else null) }
                            finally { preempting.remove() }
                        },
                        current = { pendingReopens.current(key, ticket) },
                        complete = { started, zoomStarted ->
                            pendingReopens.finish(key, ticket)
                            if (started && zoomStarted) {
                                memory.markLink(decision.linkKey, now())
                                record(decision.callingPackage, decision.target, memory)
                                memory.markZoomed(decision.target, false, now())
                            }
                        })
                    true
                }
            }
            is LaunchDecision.Preempt -> {
                effects.collapseShadeImmediately()
                effects.requestBannerCollapse()
                guardedStart(input.intent, decision.target, input.userId).also { started ->
                    if (started) {
                        ModuleLog.debug(TAG) { "ZOOM_PREEMPTED_" + decision.target }
                        memory.markZoomed(decision.target, decision.selfRelaunch, now())
                        if (decision.linkKey != null) {
                            memory.markLink(decision.linkKey, now())
                            record(decision.callingPackage, decision.target, memory)
                        }
                    }
                }
            }
            else -> false
        }
    }

    private fun guardedStart(intent: Intent, target: String, userId: Int): Boolean {
        preempting.set(true)
        return try { startZoomWindow(intent, target, userId) } finally { preempting.set(false) }
    }

    private fun executeInject(input: StarterInput, decision: LaunchDecision) {
        val memory = memory(input)
        applyEvents(decision, memory)
        if (decision !is LaunchDecision.Inject) return
        val reason = decision.reason
        input.intent.putExtra(ZoomLaunchSpec.EXTRA_APPLIED, 1)
        if (input.snapshot.optionsAlreadyZoom) {
            ModuleLog.debug(TAG) { "ZOOM_NATIVE_CHANNEL_" + reason }
            return
        }
        // 只写小窗参数，不并入原参数：原参数可能带桌面 / SystemUI 的远程动画、launchTaskId 等受限字段，
        // 以 callingUid 重新包装会在 SafeActivityOptions 权限检查处抛 SecurityException，启动失败。
        // 读出原参数仅用于判定（alreadyZoom / 写明全屏），与设备上一直运行的写入行为保持一致。
        val bundle = ZoomLaunchSpec.bundle(null)
        val pid = input.snapshot.callingPid
        val uid = input.snapshot.callingUid
        val applied = runCatching { callSetActivityOptions(input.starter, bundle, pid, uid) }
            .getOrDefault(false)
        if (!applied) runCatching { writeRequestOptions(input.starter, input.request, bundle, pid, uid) }
            .onFailure { module.log(Log.WARN, TAG, "ZOOM_OPTIONS_WRITE_FAILED", it) }
        ModuleLog.debug(TAG) { "ZOOM_INJECTED_" + reason }
        if (decision.markSelfRelaunch && decision.selfTarget != null) memory.markZoomed(decision.selfTarget, true, now())
        if (reason == "notification") effects.collapseShade(LaunchRules.SHADE_COLLAPSE_DELAY_MS)
        decision.recordInterApp?.let { (caller, target) -> record(caller, target, memory) }
    }

    private fun traceEntry(input: StarterInput) {
        val s = input.snapshot
        if (!configuration.snapshot.enabled || s.zoomAppliedExtra || !VerboseLog.enabled) return
        ModuleLog.debug(TAG) { "ZOOM_ENTRY_c" + s.callingUid + "_r" + s.realCallingUid + "_rr" + s.requestRealCallingUid +
            "_pi" + (if (s.hasPendingIntent) 1 else 0) + "_" + (s.componentPackage ?: s.explicitPackage) +
            "_" + s.action + "_e" + s.zoomSourceExtra +
            "_cc" + (if (environmentFor(input).fromControlCenter(s.componentPackage ?: s.explicitPackage ?: "")) 1 else 0) }
    }

    private fun trace(starter: Any?, stage: String) {
        if (starter == null) return
        if (!VerboseLog.enabled) return
        val request = readField(starter, "mRequest")
        val intent =
            (readField(starter, "mIntent") as? Intent)
                ?: (request?.let { readField(it, "intent") } as? Intent)
        val resolveInfo =
            request?.let { readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo
        val target =
            intent?.component?.packageName
                ?: resolveInfo?.activityInfo?.packageName
                ?: intent?.getPackage()
        val parts =
            listOf(
                "m=" + stage,
                "cp=" + (request?.let { readField(it, "callingPackage") } as? String).orEmpty(),
                "cu=" + intField(starter, "mCallingUid"),
                "ru=" + intField(starter, "mRealCallingUid"),
                "rr=" + (request?.let { intField(it, "realCallingUid") } ?: -1),
                "tg=" + target.orEmpty(),
                "ac=" + (intent?.let { it.action }.orEmpty()),
                "pi=" + (if (request?.let { readField(it, "originatingPendingIntent") } != null) 1 else 0),
                "rt=" + (if (request?.let { readField(it, "resultTo") } != null) 1 else 0),
                "gr=" + (if (request?.let { readField(it, "intentGrants") } != null) 1 else 0),
                "fl=" + (intent?.let { "0x" + Integer.toHexString(it.flags) }.orEmpty()),
            )
        ModuleLog.debug(TAG) { "ZOOM_TRACE " + parts.joinToString(" ") }
    }

    /** 可在 WM 锁内调用：按计划 A2 既有 ColorOS 实测，重入 ATMS 同一把 WM 锁；广播已移出。 */
    private fun startZoomWindow(intent: Intent, targetPackage: String, userId: Int): Boolean =
        runCatching {
            if (userId < 0) return false
            val managerClass = Class.forName(ZOOM_MANAGER_CLASS)
            val instance = managerClass.getMethod("getInstance").invoke(null) ?: return false
            val start =
                managerClass.getMethod(
                    "startZoomWindow",
                    Intent::class.java,
                    Bundle::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            val result =
                start.invoke(instance, intent, zoomBundle(), userId, targetPackage) as? Int
            result != null && result >= 0
        }.getOrDefault(false)

    private fun zoomBundle(): Bundle =
        ZoomLaunchSpec.bundle(null).apply {
            putInt("android.activity.splashScreenStyle", 1)
            putInt("start_zoom_at_other_side", LAUNCH_AT_RIGHT)
        }

    /**
     * 读出本次启动的原参数，仅供判定。
     *
     * 钩子触发时 starter.mOptions 尚未赋值（AOSP 在 executeRequest 内部的 setInitialState 才写入），
     * 实际来源是 Request.activityOptions，类型为 SafeActivityOptions，自身没有 toBundle。
     * 按 AOSP SafeActivityOptions.mergeActivityOptions 的做法合并：原参数（mOriginalOptions）在前，
     * PendingIntent 发送方的参数（mCallerOptions，如 SystemUI 点通知时写的参数）覆盖在后。
     */
    private fun optionsBundle(starter: Any, request: Any?): Bundle? {
        val options =
            readField(starter, "mOptions")
                ?: request?.let { readField(it, "activityOptions") }
                ?: return null
        toBundle(options)?.let { return it }
        val original = readField(options, "mOriginalOptions")?.let(::toBundle)
        val caller = readField(options, "mCallerOptions")?.let(::toBundle)
        if (original == null && caller == null) return null
        return Bundle().apply {
            original?.let(::putAll)
            caller?.let(::putAll)
        }
    }

    private fun toBundle(options: Any): Bundle? = runCatching {
        val method = options.javaClass.getMethod("toBundle")
        method.isAccessible = true
        method.invoke(options) as? Bundle
    }.getOrNull()

    private fun callSetActivityOptions(
        starter: Any,
        bundle: Bundle,
        callingPid: Int,
        callingUid: Int,
    ): Boolean {
        val method =
            starter.javaClass.declaredMethods.firstOrNull { candidate ->
                candidate.name == "setActivityOptions" &&
                    candidate.parameterCount == 3 &&
                    candidate.parameterTypes[0] == Bundle::class.java
            } ?: return false
        method.isAccessible = true
        method.invoke(starter, bundle, callingPid, callingUid)
        return true
    }

    /**
     * setActivityOptions 不可用时，以调用方身份构造 SafeActivityOptions 直接写入 Request.activityOptions。
     * 不写 starter.mOptions：该字段在 executeRequest 内会被 Request 中的参数重新赋值，写了也不生效。
     * 与 [StarterRequestReplay] 的回退写法一致。
     */
    private fun writeRequestOptions(starter: Any, request: Any?, bundle: Bundle, pid: Int, uid: Int) {
        requireNotNull(request) { "request unavailable" }
        val fromBundle =
            Class.forName("com.android.server.wm.SafeActivityOptions", false, starter.javaClass.classLoader)
                .getDeclaredMethod("fromBundle", Bundle::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        fromBundle.isAccessible = true
        val options = fromBundle.invoke(null, bundle, pid, uid)
        var current: Class<*>? = request.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField("activityOptions")
                field.isAccessible = true
                field.set(request, options)
                return
            } catch (exception: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("activityOptions")
    }

    private fun readField(instance: Any, name: String): Any? = fieldOf(instance.javaClass, name)?.get(instance)

    private fun intField(instance: Any, name: String): Int = fieldOf(instance.javaClass, name)?.getInt(instance) ?: 0

    /**
     * 按「类 + 字段名」缓存沿继承链找到的字段，找不到也记下（[FieldSlot.field] 为 null）：
     * 每次启动要读十几个字段、可能经过两层钩子各读两次快照，逐次 getDeclaredField 并抛 NoSuchFieldException 很贵。
     * 只缓存反射元数据，不缓存任何字段值；类随 system_server 常驻，不会卸载。
     */
    private fun fieldOf(type: Class<*>, name: String): java.lang.reflect.Field? {
        val key = FieldKey(type, name)
        fields[key]?.let { return it.field }
        var current: Class<*>? = type
        var found: java.lang.reflect.Field? = null
        while (current != null && found == null) {
            found = try {
                current.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                null
            }
            current = current.superclass
        }
        fields[key] = FieldSlot(found)
        return found
    }

    private data class FieldKey(val type: Class<*>, val name: String)

    private class FieldSlot(val field: java.lang.reflect.Field?)

    private val fields = java.util.concurrent.ConcurrentHashMap<FieldKey, FieldSlot>()

    private companion object { const val TAG = "FlymeFreeform" }
}
