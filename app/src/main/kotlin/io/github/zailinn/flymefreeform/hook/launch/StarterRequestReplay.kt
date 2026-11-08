package io.github.zailinn.flymefreeform.hook.launch

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import java.lang.reflect.Modifier

/**
 * 延迟启动复制 Request，保留 caller、UID/PID、用户、resultTo 与 URI grants；禁止保存池化 starter。
 * AOSP android16-release ActivityStarter.Request 的普通字段由 reset/set 管理，final 日志缓冲不复制。
 * https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/wm/ActivityStarter.java
 * 厂商布局不兼容时捕获失败，原请求继续执行，不关窗也不吞启动。
 */
internal class StarterRequestReplay private constructor(
    private val controller: Any,
    private val obtain: java.lang.reflect.Method,
    private val fields: List<Pair<java.lang.reflect.Field, Any?>>,
    private val intent: Intent,
    private val writeOptions: (starter: Any, request: Any, options: Bundle) -> Unit,
) {
    /**
     * [zoomOptions] 为 null 时沿用复制来的原 SafeActivityOptions，不重新包装。
     * 非 null 时必须带原调用方身份写入：重放运行在 system_server 主线程，
     * 单参数版本会以 Binder.getCallingUid()（即 SYSTEM_UID）构造 SafeActivityOptions，
     * 使调用方自带的 launchTaskId、launchDisplayId 等受限字段按系统权限通过检查。
     */
    fun start(zoomOptions: Bundle?): Boolean = runCatching {
        val copy = Intent(intent).putExtra(ZoomLaunchSpec.EXTRA_APPLIED, 1)
        val starter = obtain.invoke(controller, copy, "FlymeFreeform-reopen") ?: return false
        val request = field(starter, "mRequest").get(starter) ?: return false
        fields.forEach { (field, value) -> field.set(request, if (value is Intent) Intent(value) else value) }
        field(request, "intent").set(request, copy)
        if (zoomOptions != null) writeOptions(starter, request, zoomOptions)
        val execute = starter.javaClass.getDeclaredMethod("execute").apply { isAccessible = true }
        (execute.invoke(starter) as? Int)?.let { it >= 0 } == true
    }.getOrDefault(false)

    companion object {
        fun capture(starter: Any, request: Any?, intent: Intent): StarterRequestReplay? = runCatching {
            request ?: return null
            val controller = field(starter, "mController").get(starter) ?: return null
            val obtain = controller.javaClass.getDeclaredMethod("obtainStarter", Intent::class.java, String::class.java)
                .apply { isAccessible = true }
            // 在关窗之前确认启动和带身份的参数 API 可用。
            starter.javaClass.getDeclaredMethod("execute")
            val fields = request.javaClass.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers)
            }.map { f ->
                f.isAccessible = true
                val value = f.get(request)
                f to when {
                    f.name == "realCallingUid" && value == -1 -> Binder.getCallingUid()
                    f.name == "realCallingPid" && value == 0 -> Binder.getCallingPid()
                    value is Intent -> Intent(value)
                    value is Bundle -> Bundle(value)
                    else -> value
                }
            }
            require(fields.any { it.first.name == "userId" && (it.second as? Int ?: -1) >= 0 })
            val values = fields.associate { it.first.name to it.second }
            // 与 startActivity 入口一致：SafeActivityOptions 记录的是 Binder 调用方，即 realCalling 身份。
            val original = values["activityOptions"]
            val pid = original?.let { intOf(it, "mOriginalCallingPid") }?.takeIf { it > 0 }
                ?: (values["realCallingPid"] as? Int)?.takeIf { it > 0 } ?: Binder.getCallingPid()
            val uid = original?.let { intOf(it, "mOriginalCallingUid") }?.takeIf { it >= 0 }
                ?: (values["realCallingUid"] as? Int)?.takeIf { it >= 0 } ?: Binder.getCallingUid()
            StarterRequestReplay(controller, obtain, fields, Intent(intent), requireNotNull(optionsWriter(starter, pid, uid)))
        }.getOrNull()

        /**
         * 优先 ActivityStarter.setActivityOptions(Bundle, int, int)；没有时用
         * SafeActivityOptions.fromBundle(Bundle, int, int) 直接写 Request.activityOptions。
         * 两者都不可用则放弃重开，原启动照常执行，不退回单参数版本。
         */
        private fun optionsWriter(starter: Any, pid: Int, uid: Int): ((Any, Any, Bundle) -> Unit)? {
            val identity = arrayOf(Bundle::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            starter.javaClass.declaredMethods.firstOrNull {
                it.name == "setActivityOptions" && it.parameterTypes.contentEquals(identity)
            }?.apply { isAccessible = true }?.let { method ->
                return { target, _, options -> method.invoke(target, options, pid, uid) }
            }
            val safe = runCatching {
                Class.forName("com.android.server.wm.SafeActivityOptions", false, starter.javaClass.classLoader)
                    .getDeclaredMethod("fromBundle", *identity).apply { isAccessible = true }
            }.getOrNull() ?: return null
            return { _, request, options -> field(request, "activityOptions").set(request, safe.invoke(null, options, pid, uid)) }
        }

        private fun intOf(instance: Any, name: String): Int? = runCatching { field(instance, name).getInt(instance) }.getOrNull()

        private fun field(instance: Any, name: String): java.lang.reflect.Field {
            var type: Class<*>? = instance.javaClass
            while (type != null) {
                try { return type.getDeclaredField(name).apply { isAccessible = true } }
                catch (_: NoSuchFieldException) { type = type.superclass }
            }
            throw NoSuchFieldException(name)
        }
    }
}
