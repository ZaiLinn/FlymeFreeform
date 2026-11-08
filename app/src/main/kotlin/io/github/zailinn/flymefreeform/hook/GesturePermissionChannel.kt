package io.github.zailinn.flymefreeform.hook

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import io.github.zailinn.flymefreeform.config.TrustedSender

/** 触摸入口只读本地许可；没有收到 system_server 的有效状态前一律不抢触摸。 */
internal class GesturePermissionClient(private val changed: () -> Unit) {
    @Volatile var allowed = false
        private set
    private var started = false

    fun start(context: Context, handler: Handler) {
        if (started) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(owner: Context?, intent: Intent?) {
                if (intent?.action != STATE || !TrustedSender.matches(sentFromUid, Process.SYSTEM_UID)) return
                allowed = intent.getBooleanExtra(ALLOWED, false)
                changed()
            }
        }
        try {
            context.registerReceiver(receiver, IntentFilter(STATE), null, handler, Context.RECEIVER_EXPORTED)
            started = true
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(owner: Context?, intent: Intent?) {
                    allowed = false
                    changed()
                    request(context)
                }
            }, IntentFilter("android.intent.action.USER_SWITCHED"), null, handler, Context.RECEIVER_NOT_EXPORTED)
            request(context)
        } catch (_: RuntimeException) { allowed = false }
    }

    private fun request(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(REQUEST).setPackage("android").putExtra(CLIENT, context.packageName),
                null, options())
        }
    }
}

/** 只在主线程发布；前台回调在 WM 锁内仅投递，广播不得在该回调中直接执行。 */
internal class GesturePermissionPublisher(
    private val context: Context,
    private val handler: Handler,
    private val policy: () -> Boolean,
    private val failure: (Throwable) -> Unit,
) {
    private var switchingUser = false

    fun start() {
      try {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(owner: Context?, intent: Intent?) {
                if (intent?.action == "android.intent.action.USER_SWITCHED") {
                    switchingUser = true
                    publish()
                    return
                }
                val client = intent?.getStringExtra(CLIENT) ?: return
                if (client !in CLIENTS || sentFromUid < 0) return
                val packages = runCatching { context.packageManager.getPackagesForUid(sentFromUid) }.getOrNull().orEmpty()
                if (client in packages) publish()
            }
        }, IntentFilter(REQUEST).apply { addAction("android.intent.action.USER_SWITCHED") },
            null, handler, Context.RECEIVER_EXPORTED)
        publish()
      } catch (error: Exception) { failure(error) }
    }

    fun foregroundChanged() { switchingUser = false; publish() }

    fun publish() {
        try {
            val allowed = !switchingUser && policy()
            val all = UserHandle::class.java.getField("ALL").get(null) as UserHandle
            CLIENTS.forEach { target ->
                Context::class.java.getMethod("sendBroadcastAsUser", Intent::class.java,
                    UserHandle::class.java, String::class.java, android.os.Bundle::class.java)
                    .invoke(context, Intent(STATE).setPackage(target).putExtra(ALLOWED, allowed)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), all, null, options())
            }
        } catch (error: Exception) { failure(error) }
    }
}

private const val STATE = "io.github.zailinn.flymefreeform.action.GESTURE_PERMISSION"
private const val REQUEST = "io.github.zailinn.flymefreeform.action.REQUEST_GESTURE_PERMISSION"
private const val ALLOWED = "allowed"
private const val CLIENT = "client"
private val CLIENTS = setOf("com.android.systemui", "com.android.launcher")
private fun options() = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
