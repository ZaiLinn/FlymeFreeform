package io.github.zailinn.flymefreeform.hook.launch

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.zailinn.flymefreeform.hook.ZoomTaskCloser
import io.github.zailinn.flymefreeform.log.ModuleLog

/**
 * starter 副作用的唯一投递出口。广播、面板和关窗操作可能获取 AMS 等跨模块锁，
 * 必须离开持有 WM 锁的 starter 线程；每次实际执行都检测锁状态并隔离运行时异常。
 */
internal class StarterSideEffects(
    private val lockProbe: StarterLockProbe,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val log: (String, Throwable?) -> Unit,
) {
    private val worker = java.util.concurrent.ThreadPoolExecutor(1, 1, 10,
        java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(512),
        { task -> Thread(task, "FlymeFreeform-StarterQueries") },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    fun background(name: String, block: () -> Unit) {
        try { worker.execute { execute(name, block) } }
        catch (error: java.util.concurrent.RejectedExecutionException) { throw error }
    }

    fun post(name: String, block: () -> Unit) { handler.post { execute(name, block) } }
    fun postDelayed(name: String, delayMs: Long, block: () -> Unit) {
        handler.postDelayed({ execute(name, block) }, delayMs)
    }
    fun postAtFront(name: String, block: () -> Unit) {
        handler.postAtFrontOfQueue { execute(name, block) }
    }

    private fun execute(name: String, block: () -> Unit) {
        lockProbe.assertOffStarter(name)
        try { block() } catch (error: Exception) { log("STARTER_EFFECT_FAILED_" + name, error) }
    }

    fun canCloseZoomTask(): Boolean = ZoomTaskCloser.closer != null

    fun reopenInZoom(target: String, delayMs: Long, valid: () -> Boolean,
        start: (Boolean) -> Boolean, current: () -> Boolean, complete: (Boolean, Boolean) -> Unit) {
        post("reopen_close") {
            io.github.zailinn.flymefreeform.hook.launch.decision.DeferredLaunch.run(
                delayMs, valid, { ZoomTaskCloser.closer?.invoke(target) == true },
                { delay, action -> postDelayed("reopen_start", delay, action) }, start,
                { closed, zoomStarted, started ->
                    complete(started, zoomStarted)
                    ModuleLog.debug(TAG) { "ZOOM_REOPENED_closed" + closed + "_started" + started + "_" + target }
                    if (!started) log("ZOOM_REOPEN_FAILED_" + target, null)
                }, current)
        }
    }

    /** AOSP Android 15 StatusBarManagerService.checkCanCollapseStatusBar 涉及服务权限检查，按不安全处理。 */
    fun collapseShadeImmediately() = postAtFront("shade_immediate") {
        runCatching {
            val bar = systemContext()?.getSystemService("statusbar")
            if (bar != null) {
                bar.javaClass.getMethod("collapsePanels").invoke(bar)
                ModuleLog.debug(TAG) { "ZOOM_SHADE_IMMEDIATE" }
            }
        }.onFailure { log("ZOOM_SHADE_IMMEDIATE_FAILED", it) }
    }

    fun collapseShade(delayMs: Long) = postDelayed("shade_delayed", delayMs) {
        runCatching {
            val bar = systemContext()?.getSystemService("statusbar")
            if (bar != null) {
                bar.javaClass.getMethod("collapsePanels").invoke(bar)
                ModuleLog.debug(TAG) { "ZOOM_SHADE_COLLAPSED" }
            } else { ModuleLog.debug(TAG) { "ZOOM_SHADE_UNAVAILABLE" } }
        }.onFailure { log("ZOOM_SHADE_COLLAPSE_FAILED", it) }
        Unit
    }

    fun requestBannerCollapse() = post("banner_collapse") {
        runCatching {
            systemContext()?.sendBroadcast(
                Intent("io.github.zailinn.flymefreeform.action.COLLAPSE_BANNER").setPackage("com.android.systemui"),
            )
            ModuleLog.debug(TAG) { "ZOOM_BANNER_COLLAPSE_REQUESTED" }
        }.onFailure { log("ZOOM_BANNER_COLLAPSE_FAILED", it) }
        Unit
    }

    companion object {
        private const val TAG = "FlymeFreeform"
        /** 仅反射读取 ActivityThread 已持有的上下文，不进行系统服务查询。 */
        fun systemContext(): Context? = runCatching {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getMethod("currentActivityThread").invoke(null)
            type.getMethod("getSystemContext").invoke(thread) as Context
        }.getOrNull()
    }
}
