package io.github.zailinn.flymefreeform.apps

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import io.github.zailinn.flymefreeform.platform.coloros.SidebarEntryProtocol
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SidebarCatalogStatus { Idle, Loading, Ready, Unavailable }
internal data class SidebarCatalogState(
    val entries: List<SelectableEntry> = emptyList(),
    val status: SidebarCatalogStatus = SidebarCatalogStatus.Idle,
)

/**
 * 仅在页面可见时发起请求。第一次读到完整目录后常驻内存（随 Application 存活）：
 * 之后的请求在后台静默进行，成功则替换，失败或超时时保留原目录，
 * 状态始终为 [SidebarCatalogStatus.Ready]，工具与快捷入口不因重新读取而变灰。
 * 只有从未读到目录时才显示读取中或不可用。
 *
 * 页面暂停时不取消正在进行的请求：系统端收到后照常回传，取消只会让恢复时重发的请求
 * 撞上系统端节流。恢复时若请求仍在进行，直接沿用它；被节流（[SidebarEntryProtocol.BUSY]）时稍后重发。
 */
internal class SidebarEntryRepository(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val reply = Messenger(Handler(handler.looper, ::receive))
    private val mutableState = MutableStateFlow(SidebarCatalogState())
    val state = mutableState.asStateFlow()
    private var id: String? = null
    private var deadline = 0L
    private var transfer: CatalogTransfer<SelectableEntry, io.github.zailinn.flymefreeform.config.PinnedTarget>? = null
    private val timeout = Runnable { fail() }
    private val resend = Runnable { send() }

    fun refresh() {
        // 合并正在进行的请求，不取消重发。
        if (id != null) return
        id = UUID.randomUUID().toString()
        deadline = SystemClock.uptimeMillis() + SidebarEntryProtocol.TIMEOUT_MS
        transfer = CatalogTransfer(SidebarEntryProtocol.MAX_ENTRIES, SelectableEntry::target)
        if (mutableState.value.status != SidebarCatalogStatus.Ready) {
            mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Loading)
        }
        handler.postAtTime(timeout, deadline)
        send()
    }

    private fun send() {
        val request = id ?: return
        try {
            context.sendBroadcast(Intent(SidebarEntryProtocol.CATALOG_ACTION).setPackage("android").apply {
                putExtra(SidebarEntryProtocol.ID, request)
                putExtra(SidebarEntryProtocol.DEADLINE, deadline)
                putExtra(SidebarEntryProtocol.REPLY, reply)
            }, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        } catch (_: RuntimeException) { fail() }
    }

    private fun finish() {
        id = null
        transfer?.cancel()
        transfer = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(resend)
    }

    private fun receive(message: Message): Boolean {
        if (id == null || message.sendingUid != Process.SYSTEM_UID || message.arg1 != SidebarEntryProtocol.VERSION) return true
        try {
            val data = message.peekData() ?: return true
            if (data.getString(SidebarEntryProtocol.ID) != id) return true
            when (message.what) {
                SidebarEntryProtocol.ITEM -> {
                    val entry = SidebarEntryProtocol.readEntry(data, (Process.myUid() / 100_000))
                    if (entry == null || transfer?.append(data.getInt(SidebarEntryProtocol.INDEX, -1), entry) != true) fail()
                }
                SidebarEntryProtocol.COMPLETE -> {
                    val entries = transfer?.complete(data.getInt(SidebarEntryProtocol.COUNT, -1))
                    if (entries == null) fail() else {
                        finish()
                        mutableState.value = SidebarCatalogState(entries, SidebarCatalogStatus.Ready)
                    }
                }
                SidebarEntryProtocol.FAILED -> fail()
                SidebarEntryProtocol.BUSY ->
                    // 同一 ID 重发；剩余时间不够再等一轮就按失败处理。
                    if (SystemClock.uptimeMillis() + SidebarEntryProtocol.BUSY_RETRY_MS < deadline) {
                        handler.removeCallbacks(resend)
                        handler.postDelayed(resend, SidebarEntryProtocol.BUSY_RETRY_MS)
                    } else fail()
            }
        } catch (_: RuntimeException) { fail() }
        return true
    }

    private fun fail() {
        finish()
        // 已有目录时静默失败，继续用缓存。
        if (mutableState.value.status != SidebarCatalogStatus.Ready) {
            mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Unavailable)
        }
    }
}
