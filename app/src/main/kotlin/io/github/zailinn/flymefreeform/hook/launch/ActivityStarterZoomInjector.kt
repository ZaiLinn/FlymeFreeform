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
        val resolved = (request?.let { readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo)?.activityInfo?.packageName
            ?: (request?.let { readField(it, "activityInfo") } as? android.content.pm.ActivityInfo)?.packageName
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
            optionsAlreadyZoom = ZoomLaunchSpec.alreadyZoom(options), preempting = preempting.get() == true,
        )
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
                            try { replay.start(if (zoom) ZoomLaunchSpec.bundle(input.options) else null) }
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
        val bundle = ZoomLaunchSpec.bundle(input.options)
        val applied = runCatching { callSetActivityOptions(input.starter, bundle, input.snapshot.callingPid, input.snapshot.callingUid) }
            .getOrDefault(false)
        if (!applied) runCatching { writeOptions(input.starter, bundle) }
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

    private fun optionsBundle(starter: Any, request: Any?): Bundle? {
        val options =
            readField(starter, "mOptions")
                ?: request?.let { readField(it, "activityOptions") }
                ?: return null
        return runCatching {
            val toBundle = options.javaClass.getMethod("toBundle")
            toBundle.isAccessible = true
            toBundle.invoke(options) as? Bundle
        }.getOrNull()
    }

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

    /** setActivityOptions 不可用时，将 ActivityOptions 直接写回 ActivityStarter。 */
    private fun writeOptions(starter: Any, bundle: Bundle) {
        val fromBundle =
            Class.forName("android.app.ActivityOptions").getMethod("fromBundle", Bundle::class.java)
        fromBundle.isAccessible = true
        val options = fromBundle.invoke(null, bundle)
        var current: Class<*>? = starter.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField("mOptions")
                field.isAccessible = true
                field.set(starter, options)
                return
            } catch (exception: NoSuchFieldException) {
                current = current.superclass
            }
        }
    }

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (exception: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun intField(instance: Any, name: String): Int {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                return field.getInt(instance)
            } catch (exception: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return 0
    }

    private companion object { const val TAG = "FlymeFreeform" }
}
