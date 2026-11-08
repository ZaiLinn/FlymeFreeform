package io.github.zailinn.flymefreeform.config

import android.content.Context
import android.content.pm.PackageManager
import io.github.zailinn.flymefreeform.platform.coloros.SidebarEntryProtocol

/** 包被卸载、PM 查询失败与未知发送者均拒绝，不让 checked exception 逃出接收端。 */
internal object SettingsBroadcastSender {
    fun accepts(context: Context, uid: Int): Boolean = TrustedSender.matches(uid) {
        context.packageManager.getApplicationInfo(SidebarEntryProtocol.MODULE_PACKAGE,
            PackageManager.ApplicationInfoFlags.of(0)).uid
    }
}
