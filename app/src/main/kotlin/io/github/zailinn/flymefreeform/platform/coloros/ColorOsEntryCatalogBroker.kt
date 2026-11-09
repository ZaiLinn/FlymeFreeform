package io.github.zailinn.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.config.PinnedTarget
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 设置页只能请求目录；启动能力不通过广播暴露。注册与 system_server 同生命周期。 */
internal class ColorOsEntryCatalogBroker(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
    private val onCatalog: (List<SelectableEntry>) -> Unit,
) {
    private val connection = ColorOsSidebarEntryConnection(context, handler, log)
    private val store = SidebarCatalogStore(log)
    /** 已收到侧边栏的真实目录（或目录已被作废）：之后到达的本地副本不再采用。 */
    private var liveReceived = false
    /** 切换用户时递增：之前开始读取的本地副本属于旧用户，不再采用。 */
    private var userGeneration = 0
    /** 最近一次发布的目录；包变化时在它上面增删，不先清空。 */
    private var current: List<SelectableEntry> = emptyList()
    /** 读取途中有包变化：这次结果可能已过时，完成后再读一次。 */
    private var reloadAfterCurrent = false
    private var retryIndex = 0
    /** 主动取消时连接会以失败回调，此时不安排重试。 */
    private var cancelling = false
    private val retry = Runnable { requestCatalog() }
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "FlymeFreeform-CatalogRelay") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val callbacks = mutableListOf<(List<SelectableEntry>?) -> Unit>()
    private var refreshing = false
    private var launching = false
    private var registered = false
    private var lastRequestAt = -1_000L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SidebarEntryProtocol.CATALOG_ACTION || sentFromPackage != SidebarEntryProtocol.MODULE_PACKAGE || sentFromUid < 0) return
            val sender = sentFromUid
            try {
                val id = intent.getStringExtra(SidebarEntryProtocol.ID) ?: return
                val deadline = intent.getLongExtra(SidebarEntryProtocol.DEADLINE, 0)
                val reply = intent.getParcelableExtra(SidebarEntryProtocol.REPLY, Messenger::class.java) ?: return
                val now = SystemClock.uptimeMillis()
                if (!SidebarProtocol.isValidRequestId(id) || !SidebarProtocol.isValidDeadline(deadline, now, SidebarEntryProtocol.TIMEOUT_MS)) return
                if (now - lastRequestAt < THROTTLE_MS) {
                    // 明确告诉客户端稍后重发，否则它要白等到超时。
                    try { reply.send(SidebarEntryProtocol.message(SidebarEntryProtocol.BUSY, id)) }
                    catch (_: android.os.RemoteException) {}
                    return
                }
                lastRequestAt = now
                worker.execute {
                    try {
                        val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
                        // 系统按签名权限过滤广播后，仍核对实际 UID 与当前用户，拒绝同证书其他包。
                        val userContext = Context::class.java.getMethod("createContextAsUser", android.os.UserHandle::class.java, Int::class.javaPrimitiveType)
                            .invoke(this@ColorOsEntryCatalogBroker.context, android.os.UserHandle.getUserHandleForUid(sender), 0) as Context
                        val expected = userContext.packageManager.getApplicationInfo(
                            SidebarEntryProtocol.MODULE_PACKAGE, PackageManager.ApplicationInfoFlags.of(0),
                        ).uid
                        if (sender != expected || sender / 100_000 != user || SystemClock.uptimeMillis() >= deadline) return@execute
                        handler.post {
                            refresh { entries ->
                                sendCatalog(reply, id, deadline, entries)
                            }
                        }
                    } catch (exception: Exception) { handler.post { log(Log.WARN, "ENTRY_CATALOG_REQUEST_FAILED", null) } }
                }
            } catch (exception: RuntimeException) { log(Log.WARN, "ENTRY_CATALOG_REQUEST_INVALID", null) }
        }
    }

    fun start() {
        if (registered) return
        try {
            context.registerReceiver(receiver, IntentFilter(SidebarEntryProtocol.CATALOG_ACTION),
                SidebarEntryProtocol.CATALOG_PERMISSION, handler, Context.RECEIVER_EXPORTED)
            registered = true
        } catch (exception: RuntimeException) {
            log(Log.WARN, "ENTRY_CATALOG_RECEIVER_UNAVAILABLE", exception)
        }
        // 重启后先显示上次的目录；真实目录到达后整体替换。
        loadStored()
    }

    private fun loadStored() {
        val generation = userGeneration
        store.load { entries ->
            handler.post {
                if (!liveReceived && generation == userGeneration) {
                    current = entries
                    onCatalog(entries)
                }
            }
        }
    }

    /**
     * 切换用户：旧用户的条目立即作废（在途读取取消、目录清空），先换上新用户的本地副本，
     * [reload] 时向新用户的侧边栏重读。侧边栏连接自己会在切换时断开，下次请求按新用户重连。
     */
    fun userSwitched(reload: Boolean) {
        handler.removeCallbacks(retry)
        retryIndex = 0
        cancelQuietly()
        refreshing = false
        reloadAfterCurrent = false
        userGeneration++
        liveReceived = false
        if (current.isNotEmpty()) {
            current = emptyList()
            onCatalog(emptyList())
        }
        loadStored()
        if (reload) refresh()
    }

    fun refresh(onComplete: ((List<SelectableEntry>?) -> Unit)? = null) {
        if (onComplete != null) {
            if (callbacks.size >= 2) { onComplete(null); return }
            callbacks += onComplete
        }
        // 外部发起的刷新重新计重试次数。
        handler.removeCallbacks(retry)
        retryIndex = 0
        requestCatalog()
    }

    private fun requestCatalog() {
        if (refreshing) return
        refreshing = true
        if (!connection.request { entries, _ -> publish(entries) }) publish(null)
    }

    fun launch(target: PinnedTarget.Sidebar, beforeLaunch: () -> Boolean) {
        connection.cancel()
        launching = true
        if (!connection.request(target, beforeLaunch) { _, dispatched ->
                launching = false
                if (io.github.zailinn.flymefreeform.log.VerboseLog.enabled) {
                    log(
                        Log.DEBUG,
                        "SYSTEM_ENTRY_" + (if (dispatched) "DISPATCHED_" else "NOT_DISPATCHED_") + target.alias,
                        null,
                    )
                }
                if (!dispatched) log(Log.WARN, "PINNED_ENTRY_NOT_DISPATCHED", null)
            }) { launching = false; log(Log.WARN, "PINNED_ENTRY_BUSY", null) }
    }

    fun cancelLaunch() { if (launching) connection.cancel() }

    fun cancel() {
        handler.removeCallbacks(retry)
        retryIndex = 0
        cancelQuietly()
    }

    /** 模块关闭：除取消外还解除对侧边栏服务的绑定（见 [ColorOsSidebarEntryConnection.release]）。 */
    fun release() {
        handler.removeCallbacks(retry)
        retryIndex = 0
        cancelling = true
        try { connection.release() } finally { cancelling = false }
    }

    /**
     * 有包安装、更新或卸载。旧目录继续显示，不先清空：清空后要等侧边栏回传才恢复，
     * 读取失败时固定的工具会一直消失。卸载的包立即移除其条目；[reload] 时后台重读，读到后整体替换。
     */
    fun packageChanged(packageName: String?, removed: Boolean, reload: Boolean) {
        // 副本早于这次变化，之后不再采用。
        liveReceived = true
        if (removed && !packageName.isNullOrEmpty()) {
            val kept = current.filterNot { it.packageName == packageName }
            if (kept.size != current.size) {
                current = kept
                onCatalog(kept)
            }
        }
        if (!reload) return
        if (refreshing) reloadAfterCurrent = true else refresh()
    }

    private fun publish(entries: List<SelectableEntry>?) {
        refreshing = false
        val reloadNow = reloadAfterCurrent
        reloadAfterCurrent = false
        if (entries != null) {
            liveReceived = true
            retryIndex = 0
            current = entries
            onCatalog(entries)
            store.save(entries)
        } else if (!cancelling && retryIndex < RETRY_DELAYS_MS.size) {
            // 超时或侧边栏未就绪：按退避再试，避免一次失败后要等下次解锁或改固定项才重读。
            val delay = RETRY_DELAYS_MS[retryIndex++]
            log(Log.WARN, "ENTRY_CATALOG_RETRY_" + retryIndex + "_IN_" + delay + "MS", null)
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, delay)
        }
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it(entries) }
        if (reloadNow && !cancelling) refresh()
    }

    private fun cancelQuietly() {
        cancelling = true
        try { connection.cancel() } finally { cancelling = false }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)
        const val THROTTLE_MS = 500L
    }

    private fun sendCatalog(reply: Messenger, id: String, deadline: Long, entries: List<SelectableEntry>?) {
        try {
            worker.execute {
                try {
                    if (entries != null) {
                        for ((index, entry) in entries.withIndex()) {
                            if (SystemClock.uptimeMillis() >= deadline) return@execute
                            reply.send(SidebarEntryProtocol.item(id, index, entry))
                        }
                    }
                    if (SystemClock.uptimeMillis() < deadline) reply.send(SidebarEntryProtocol.message(
                        if (entries == null) SidebarEntryProtocol.FAILED else SidebarEntryProtocol.COMPLETE, id,
                    ).apply { data.putInt(SidebarEntryProtocol.COUNT, entries?.size ?: 0) })
                } catch (_: android.os.RemoteException) {
                    // 页面或 App 已离开，剩余目录不再发送。
                    log(Log.DEBUG, "ENTRY_CATALOG_REPLY_DISCONNECTED", null)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            log(Log.WARN, "ENTRY_CATALOG_REPLY_QUEUE_FULL", null)
        }
    }
}
