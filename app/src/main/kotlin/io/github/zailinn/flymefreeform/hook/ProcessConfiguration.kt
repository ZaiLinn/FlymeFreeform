package io.github.zailinn.flymefreeform.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.config.ModuleSettingsSnapshot
import io.github.zailinn.flymefreeform.config.PinnedTargetCodec
import io.github.zailinn.flymefreeform.config.SettingsTransfer
import io.github.zailinn.flymefreeform.log.ModuleLog
import io.github.zailinn.flymefreeform.platform.coloros.SidebarEntryProtocol
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Hook 进程内只读配置快照；任何类型损坏都会让该进程整体失败关闭。 */
internal class ProcessConfiguration(
    private val log: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    @Volatile
    var snapshot: ModuleSettingsSnapshot = FAIL_CLOSED
        private set

    @Volatile
    var isAvailable: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(ModuleSettingsSnapshot) -> Unit>()
    private var preferences: SharedPreferences? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { changed, _ -> refresh(changed, false) }

    fun start(remotePreferences: SharedPreferences) {
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = remotePreferences
        remotePreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh(remotePreferences, true)
    }

    fun observe(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeObserver(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners -= listener
    }

    private val broadcastRegistered = AtomicBoolean(false)

    /**
     * 在本进程登记设置广播接收端；重复调用只登记一次。
     *
     * system_server 与侧边栏进程各自已有接收端，这里供 SystemUI、桌面等没有接收端的进程使用。
     */
    fun listenForSettingsBroadcast(context: Context) {
        if (!broadcastRegistered.compareAndSet(false, true)) return
        val appContext = context.applicationContext ?: context
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || sentFromUid != moduleUid(appContext)) return
                    applySettingsBroadcast(intent)
                }
            }
        try {
            appContext.registerReceiver(
                receiver,
                IntentFilter(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        } catch (exception: RuntimeException) {
            broadcastRegistered.set(false)
            log(Log.WARN, "SETTINGS_BROADCAST_OBSERVER_UNAVAILABLE", exception)
        }
    }

    /**
     * 把设置页广播转成新快照并热生效；调用方须已核对发送方 UID。
     *
     * 新版设置页在广播里带完整快照（[SettingsTransfer.EXTRA_SETTINGS]）；没有该字段时
     * 只按旧字段更新固定项与总开关，兼容尚未更新的发送端。
     */
    fun applySettingsBroadcast(intent: Intent): Boolean {
        if (intent.action != ModulePreferences.ACTION_PINNED_TARGETS_CHANGED) return false
        val full = SettingsTransfer.fromBundle(intent.getBundleExtra(SettingsTransfer.EXTRA_SETTINGS))
        if (full != null) {
            ModuleLog.debug("FlymeFreeform") { "SETTINGS_BROADCAST_APPLIED_FULL" }
            override(full)
            return true
        }
        val encoded = intent.getStringExtra(ModulePreferences.EXTRA_PINNED_TARGETS) ?: return false
        val current = snapshot
        override(
            current.copy(
                enabled = intent.getBooleanExtra(ModulePreferences.EXTRA_MODULE_ENABLED, current.enabled),
                pinsSaved = intent.getBooleanExtra(ModulePreferences.EXTRA_PINS_SAVED, current.pinsSaved),
                pinnedTargets = PinnedTargetCodec.decodeList(encoded),
                innerArcTargets =
                    ModuleSettingsSnapshot.decodeInnerArcTargets(
                        intent.getStringArrayListExtra(ModulePreferences.EXTRA_INNER_ARC_TARGETS),
                    ) ?: current.innerArcTargets,
            ),
        )
        return true
    }

    private fun moduleUid(context: Context): Int =
        try {
            context.packageManager.getApplicationInfo(
                SidebarEntryProtocol.MODULE_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            ).uid
        } catch (_: RuntimeException) {
            -1
        } catch (_: PackageManager.NameNotFoundException) {
            -1
        }

    /** 接收经调用方 UID 校验的运行时推送，避免等待目标进程重新加载。 */
    fun override(next: ModuleSettingsSnapshot) {
        isAvailable = true
        io.github.zailinn.flymefreeform.log.VerboseLog.update(next.verboseLogEnabled)
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { listener -> listener(next) }
    }

    private fun refresh(preferences: SharedPreferences, initial: Boolean) {
        val next =
            try {
                ModuleSettingsSnapshot.readFrom(preferences).also { isAvailable = true }
            } catch (exception: ClassCastException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_TYPE_MISMATCH", exception)
                FAIL_CLOSED
            } catch (exception: RuntimeException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_READ_FAILED", exception)
                FAIL_CLOSED
            }
        io.github.zailinn.flymefreeform.log.VerboseLog.update(next.verboseLogEnabled)
        val changed = next != snapshot
        snapshot = next
        // 仅在进程启动时记录一次状态。此后每次配置变更都会进入本方法，
        // 以 INFO 级别输出会产生大量重复记录，故降为详细日志。
        if (initial) {
            log(
                Log.INFO,
                if (next.enabled && isAvailable) "MODULE_STATE_ENABLED" else "MODULE_STATE_DISABLED",
                null,
            )
        } else if (changed) {
            ModuleLog.debug("FlymeFreeform") { "MODULE_STATE_CHANGED_" + (if (next.enabled && isAvailable) "ON" else "OFF") }
        }
        if (initial || changed) {
            listeners.forEach { listener -> listener(next) }
        }
    }

    private companion object {
        val FAIL_CLOSED =
            ModuleSettingsSnapshot(
                enabled = false,
                leftCornerEnabled = false,
                rightCornerEnabled = false,
            )
    }
}
