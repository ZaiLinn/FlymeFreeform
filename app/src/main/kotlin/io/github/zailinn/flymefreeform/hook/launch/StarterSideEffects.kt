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
    fun post(name: String, block: () -> Unit) { handler.post { execute(name, block) } }
    fun postDelayed(name: String, delayMs: Long, block: () -> Unit) {
        handler.postDelayed({ execute(name, block) }, delayMs)
    }
    fun postAtFront(name: String, block: () -> Unit) {
        handler.postAtFrontOfQueue { execute(name, block) }
    }

    private fun execute(name: String, block: () -> Unit) {
        lockProbe.assertOffStarter(name)
        try { block() } catch (error: RuntimeException) { log("STARTER_EFFECT_FAILED_" + name, error) }
    }

    fun canCloseZoomTask(): Boolean = ZoomTaskCloser.closer != null

    fun reopenInZoom(intent: Intent, target: String, delayMs: Long, start: (Intent) -> Boolean) {
        val closer = ZoomTaskCloser.closer ?: return
        post("reopen_close") {
            val closed = runCatching { closer(target) }.getOrDefault(false)
            postDelayed("reopen_start", if (closed) delayMs else 0L) {
                val started = start(intent)
                ModuleLog.debug(TAG) { "ZOOM_REOPENED_closed" + closed + "_started" + started + "_" + target }
            }
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
