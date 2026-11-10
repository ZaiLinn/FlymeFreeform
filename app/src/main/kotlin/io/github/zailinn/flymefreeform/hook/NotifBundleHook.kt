package io.github.zailinn.flymefreeform.hook

import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.zailinn.flymefreeform.hook.launch.ZoomLaunchSpec
import io.github.zailinn.flymefreeform.hook.launch.LaunchIntentSnapshot
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules
import io.github.zailinn.flymefreeform.hook.launch.decision.ProtectedLaunch
import io.github.zailinn.flymefreeform.log.ModuleLog

/**
 * 通知点击小窗：ColorOS 在 SystemUI 侧用 setNotificationTypeStarterLog(Bundle, NotificationEntry)
 * 组装启动参数，随后把 Bundle 交给 system_server 的 ActivityStarter。
 *
 * 在方法执行前改写这个 Bundle 即可决定窗口形态：
 *  - extra_window_mode = 100（自由窗口）
 *  - android:activity.mZoomLaunchFlags = 5
 *  - androidx.activity.LaunchScenario = 1
 *
 * 同时必须删掉 Bundle 里所有 remote + animation/transition 键。这些键让 SystemUI
 * 把本次启动的转场交给横幅那一层做，删掉后横幅不再参与转场，窗口直接起在自由窗层，
 * 因此不会被横幅压住 —— 这是「点开不被横幅盖住」的关键。
 *
 * 通知所属应用已在前台全屏时不改写，只写明全屏（[ZoomLaunchSpec.markFullscreen]），
 * 横幅与转场保持系统原样，system_server 据此不再补写小窗参数。
 */
internal class NotifBundleHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val context: android.content.Context,
) {
    @Volatile
    private var headsUpManager: Any? = null

    fun install(classLoader: ClassLoader) {
        hookHeadsUpManager(classLoader)
        for (name in CANDIDATES) {
            val target = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            val method =
                target.declaredMethods.firstOrNull { candidate ->
                    candidate.name == METHOD_NAME && candidate.parameterCount == 2
                } ?: continue
            method.isAccessible = true
            val hooked = runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.notif.bundle." + target.simpleName)
                    .intercept { chain ->
                        runCatching { rewriteBundle(chain.thisObject, chain.args) }
                        chain.proceed()
                    }
            }.isSuccess
            if (hooked) {
                module.log(Log.INFO, TAG, "NOTIF_BUNDLE_HOOKED_" + target.simpleName)
                return
            }
        }
        module.log(Log.WARN, TAG, "NOTIF_BUNDLE_HOOK_UNAVAILABLE", null)
    }

    private fun rewriteBundle(starter: Any?, args: List<Any?>) {
        val settings = configuration.snapshot
        if (!settings.enabled || !settings.zoomLaunch) return
        val bundle = args.getOrNull(0) as? Bundle ?: return
        val entry = args.getOrNull(1)
        val pkg = packageOf(entry)
        // 与 system_server 其他入口共用保持全屏名单（系统界面、设置、权限控制器）。
        if (pkg != null && pkg in LaunchRules.SKIP_TARGETS) {
            ModuleLog.debug(TAG) { "NOTIF_BUNDLE_SKIPPED_SYSTEM " + pkg }
            return
        }
        // 排除列表内的应用保持系统原有启动方式。
        if (pkg != null && (LaunchRules.isZoomLaunchBlocked(pkg) || settings.isBlacklisted(pkg))) {
            ModuleLog.debug(TAG) { "NOTIF_BUNDLE_SKIPPED_EXCLUDED " + pkg }
            return
        }
        // 在删除转场、收起横幅和写入小窗参数前检查真实通知落点。
        // 读取失败时保持全屏，避免在无法识别用途时接管验证/支付流程。
        val protection = notificationProtection(entry)
        if (protection != null) {
            ZoomLaunchSpec.markFullscreen(bundle)
            ModuleLog.debug(TAG) { "NOTIF_BUNDLE_PROTECTED " + protection }
            return
        }
        if (targetInForeground(entry)) {
            ZoomLaunchSpec.markFullscreen(bundle)
            ModuleLog.debug(TAG) { "NOTIF_BUNDLE_FOREGROUND_FULLSCREEN " + pkg }
            return
        }

        // remote 转场按全屏目标计算动画尺寸，与小窗启动同时存在时会先铺满整屏再缩回小窗。
        // 这里一律剥离，改为模块自己收起通知面板，使窗口直接以最终尺寸出现。
        val headsUp = dismissHeadsUp(entry)
        val dropped = dropRemoteTransition(bundle)

        bundle.putInt(KEY_EXTRA_WINDOW_MODE, VALUE_FREEFORM)
        bundle.putInt(KEY_ZOOM_LAUNCH_FLAGS, 5)
        bundle.putInt(KEY_LAUNCH_SCENARIO, 1)
        bundle.putInt(KEY_WINDOWING_MODE, VALUE_FREEFORM)

        // 剥离转场后系统要等新窗口获得焦点才收起面板，应用先起来而面板仍展开。
        // 横幅场景面板本未展开，无需收起。
        val collapsed = if (headsUp) false else collapseShadeForActivityStart(starter)

        ModuleLog.debug(TAG) { "NOTIF_BUNDLE_ZOOM pkg=" + pkg +
                " headsUp=" + headsUp +
                " dropped=" + dropped +
                " shadeCollapsed=" + collapsed }
    }

    /**
     * HeadsUpManagerImpl 的实例只在构造时拿得到，这里挂构造函数保存引用。
     * 有了它才能在启动前把横幅移除（isHeadsUpEntry → removeNotification）。
     */
    private fun hookHeadsUpManager(classLoader: ClassLoader) {
        val target =
            runCatching { classLoader.loadClass(HEADS_UP_CLASS) }.getOrNull()
                ?: run {
                    module.log(Log.WARN, TAG, "NOTIF_HEADS_UP_CLASS_MISSING", null)
                    return
                }
        var hooks = 0
        target.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            runCatching {
                module
                    .hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.notif.headsup." + constructor.parameterCount)
                    .intercept { chain ->
                        val result = chain.proceed()
                        headsUpManager = chain.thisObject
                        result
                    }
                hooks++
            }
        }
        // 实例通常早于本钩子被 Dagger 创建，构造函数不会再触发；
        // showNotification 每次横幅弹出都会走，用它把实例抓下来。
        var showHooked = false
        runCatching {
            val show =
                target.declaredMethods.firstOrNull {
                    it.name == "showNotification" && it.parameterCount == 2
                } ?: return@runCatching
            show.isAccessible = true
            module
                .hook(show)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.notif.headsup.show")
                .intercept { chain ->
                    headsUpManager = chain.thisObject
                    chain.proceed()
                }
            showHooked = true
        }.onFailure { module.log(Log.WARN, TAG, "NOTIF_HEADS_UP_SHOW_HOOK_FAILED", it) }
        module.log(Log.INFO, TAG, "NOTIF_HEADS_UP_CTOR_HOOKS_" + hooks + "_SHOW_" + showHooked)
    }

    /**
     * 收起通知面板。
     *
     * 使用 ShadeController 中与「启动 Activity 时收起」同名的方法，
     * 与系统自身在启动外部界面时的收起路径一致，保留原生收起动画。
     */
    private fun collapseShadeForActivityStart(starter: Any?): Boolean {
        val controller = readField(starter, "shadeController") ?: return false
        return runCatching {
            controller.javaClass.getMethod("collapseShadeForActivityStart").invoke(controller)
            true
        }.onFailure {
            module.log(Log.WARN, TAG, "NOTIF_SHADE_COLLAPSE_FAILED", it as? RuntimeException)
        }.getOrDefault(false)
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    /** 正在以横幅显示时先移除并返回 true；条目在通知面板内时返回 false。 */
    private fun dismissHeadsUp(entry: Any?): Boolean {
        val manager = headsUpManager
        if (manager == null) {
            ModuleLog.debug(TAG) { "NOTIF_HEADS_UP_MANAGER_NULL" }
            return false
        }
        val key = keyOf(entry)
        if (key == null) {
            ModuleLog.debug(TAG) { "NOTIF_HEADS_UP_KEY_NULL" }
            return false
        }
        return runCatching {
            val isHeadsUp =
                manager.javaClass.methods
                    .firstOrNull { it.name == "isHeadsUpEntry" && it.parameterCount == 1 }
                    ?.invoke(manager, key) as? Boolean
            if (isHeadsUp != true) {
                ModuleLog.debug(TAG) { "NOTIF_HEADS_UP_NOT_HEADSUP " + key }
                return@runCatching false
            }
            val removed =
                manager.javaClass.methods
                    .firstOrNull { it.name == "removeNotification" && it.parameterCount == 3 }
                    ?.invoke(manager, key, true, "flymefreeform")
            ModuleLog.debug(TAG) { "NOTIF_HEADS_UP_DISMISSED " + key + " -> " + (removed ?: "void") }
            true
        }.onFailure { module.log(Log.WARN, TAG, "NOTIF_HEADS_UP_DISMISS_FAILED", it) }
            .getOrDefault(false)
    }

    private fun keyOf(entry: Any?): String? {
        if (entry == null) return null
        return runCatching {
            entry.javaClass.methods
                .firstOrNull { it.name == "getKey" && it.parameterCount == 0 }
                ?.invoke(entry) as? String
        }.getOrNull()
    }

    /**
     * 通知所属应用是否正以全屏显示在前台。
     *
     * 所属包取 StatusBarNotification 的发布包与 contentIntent 的创建包，任一与前台全屏任务的
     * 顶层包相同即成立。前台取 getRunningTasks 中第一个可见、窗口模式为全屏的任务：
     * 小窗（100）、分屏等任务跳过，桌面视为普通全屏任务。查询失败按不成立处理，保持小窗。
     */
    private fun targetInForeground(entry: Any?): Boolean {
        val packages = notificationPackages(entry)
        if (packages.isEmpty()) return false
        val foreground = runCatching { foregroundFullscreenPackage() }
            .onFailure { module.log(Log.WARN, TAG, "NOTIF_FOREGROUND_QUERY_FAILED", it) }
            .getOrNull()
        val matched = foreground != null && foreground in packages
        ModuleLog.debug(TAG) { "NOTIF_FOREGROUND " + foreground + " match=" + matched }
        return matched
    }

    private fun notificationPackages(entry: Any?): Set<String> {
        val sbn = runCatching { entry?.javaClass?.getMethod("getSbn")?.invoke(entry) }.getOrNull()
            as? android.service.notification.StatusBarNotification ?: return emptySet()
        val result = linkedSetOf(sbn.packageName)
        sbn.notification?.contentIntent?.creatorPackage?.let(result::add)
        return result
    }

    /** getIntent 会查询 AMS，仅在 SystemUI 通知入口使用，绝不移入 ActivityStarter 的 WM 锁内。 */
    private fun notificationProtection(entry: Any?): String? = runCatching {
        val sbn = entry?.javaClass?.getMethod("getSbn")?.invoke(entry)
            as? android.service.notification.StatusBarNotification
            ?: return@runCatching "UNKNOWN_INTENT"
        val pending = sbn.notification?.contentIntent ?: return@runCatching "UNKNOWN_INTENT"
        val intent = pendingIntentGetIntent?.invoke(pending) as? android.content.Intent
            ?: return@runCatching "UNKNOWN_INTENT"
        val snapshot = LaunchIntentSnapshot.read(intent)
        if (snapshot.preemptTarget?.let {
                LaunchRules.isZoomLaunchBlocked(it) || configuration.snapshot.isBlacklisted(it)
            } == true) return@runCatching "EXCLUDED_TARGET"
        ProtectedLaunch.reason(snapshot)
    }.getOrDefault("UNKNOWN_INTENT")

    private val pendingIntentGetIntent by lazy {
        runCatching { android.app.PendingIntent::class.java.getMethod("getIntent") }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun foregroundFullscreenPackage(): String? {
        val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        for (task in manager.getRunningTasks(FOREGROUND_TASK_SCAN)) {
            if (taskVisible(task) == false) continue
            if (windowingMode(task) != ZoomLaunchSpec.WINDOWING_MODE_FULLSCREEN) continue
            return task.topActivity?.packageName ?: task.baseActivity?.packageName
        }
        return null
    }

    /** TaskInfo.isVisible 为隐藏字段；读取失败返回 null，视为可见。 */
    private fun taskVisible(task: android.app.ActivityManager.RunningTaskInfo): Boolean? =
        runCatching { readField(task, "isVisible") as? Boolean }.getOrNull()

    private fun windowingMode(task: android.app.ActivityManager.RunningTaskInfo): Int? =
        runCatching { task.javaClass.getMethod("getWindowingMode").invoke(task) as? Int }.getOrNull()
            ?: runCatching {
                val configuration = readField(task, "configuration") ?: return@runCatching null
                val window = readField(configuration, "windowConfiguration") ?: return@runCatching null
                window.javaClass.getMethod("getWindowingMode").invoke(window) as? Int
            }.getOrNull()

    /** 返回被删掉的键，便于在日志里确认转场确实被剥掉。 */
    private fun dropRemoteTransition(bundle: Bundle): String {
        val keys = runCatching { bundle.keySet().toTypedArray() }.getOrDefault(emptyArray())
        val removed = StringBuilder()
        for (key in keys) {
            val lower = key.lowercase()
            if (!lower.contains("remote")) continue
            if (!lower.contains("animation") && !lower.contains("transition")) continue
            runCatching { bundle.remove(key) }
            if (removed.isNotEmpty()) removed.append(',')
            removed.append(key)
        }
        return if (removed.isEmpty()) "-" else removed.toString()
    }

    private fun packageOf(entry: Any?): String? {
        if (entry == null) return null
        return runCatching {
            val sbn = entry.javaClass.getMethod("getSbn").invoke(entry)
            sbn?.javaClass?.getMethod("getPackageName")?.invoke(sbn) as? String
        }.getOrNull()
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val METHOD_NAME = "setNotificationTypeStarterLog"
        const val HEADS_UP_CLASS =
            "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl"
        const val KEY_EXTRA_WINDOW_MODE = "extra_window_mode"
        const val KEY_ZOOM_LAUNCH_FLAGS = "android:activity.mZoomLaunchFlags"
        const val KEY_LAUNCH_SCENARIO = "androidx.activity.LaunchScenario"
        const val KEY_WINDOWING_MODE = "android:activity.windowingMode"
        const val VALUE_FREEFORM = 100
        /** 前台判定扫描的任务数：顶部可能先是小窗或分屏任务。 */
        const val FOREGROUND_TASK_SCAN = 6
        val CANDIDATES = listOf(
            "com.oplus.systemui.statusbar.phone.OplusStatusBarNotificationActivityStarterExImpl",
            "com.android.systemui.statusbar.phone.OplusStatusBarNotificationActivityStarterExImpl",
            "com.oplus.systemui.statusbar.phone.StatusBarNotificationActivityStarterExImpl",
        )
    }
}
