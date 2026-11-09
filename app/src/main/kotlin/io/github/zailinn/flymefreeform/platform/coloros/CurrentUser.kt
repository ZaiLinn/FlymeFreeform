package io.github.zailinn.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.Context
import android.os.UserHandle

/**
 * system_server 内的当前前台用户。system_server 自身属于 0 号用户，`Process.myUserHandle()`、
 * `UserHandle.myUserId()` 永远是 0；前台是系统分身等其他用户时，目录与启动都必须按这里取到的用户。
 * 取不到时退回 0 号用户（与此前行为一致）。
 */
internal object CurrentUser {
    private val getCurrentUser by lazy { runCatching { ActivityManager::class.java.getMethod("getCurrentUser") }.getOrNull() }
    private val createContextAsUser by lazy {
        runCatching {
            Context::class.java.getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    fun id(): Int = runCatching { getCurrentUser?.invoke(null) as? Int }.getOrNull() ?: 0

    /** 用户号换成 UserHandle：用公开的 getUserHandleForUid，避开隐藏的 UserHandle.of。 */
    fun handle(userId: Int = id()): UserHandle = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)

    /** 以当前用户身份访问 PackageManager / startActivity；失败时返回原 context。 */
    fun context(base: Context, userId: Int = id()): Context =
        if (userId == 0) base
        else runCatching { createContextAsUser?.invoke(base, handle(userId), 0) as? Context }.getOrNull() ?: base

    private const val PER_USER_RANGE = 100_000
}
