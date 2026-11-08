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
) {
    fun start(options: Bundle?): Boolean = runCatching {
        val copy = Intent(intent).putExtra(ZoomLaunchSpec.EXTRA_APPLIED, 1)
        val starter = obtain.invoke(controller, copy, "FlymeFreeform-reopen") ?: return false
        val request = field(starter, "mRequest").get(starter) ?: return false
        fields.forEach { (field, value) -> field.set(request, if (value is Intent) Intent(value) else value) }
        field(request, "intent").set(request, copy)
        val setOptions = starter.javaClass.declaredMethods.first { it.name == "setActivityOptions" &&
            it.parameterTypes.contentEquals(arrayOf(Bundle::class.java)) }.apply { isAccessible = true }
        setOptions.invoke(starter, options)
        val execute = starter.javaClass.getDeclaredMethod("execute").apply { isAccessible = true }
        (execute.invoke(starter) as? Int)?.let { it >= 0 } == true
    }.getOrDefault(false)

    companion object {
        fun capture(starter: Any, request: Any?, intent: Intent): StarterRequestReplay? = runCatching {
            request ?: return null
            val controller = field(starter, "mController").get(starter) ?: return null
            val obtain = controller.javaClass.getDeclaredMethod("obtainStarter", Intent::class.java, String::class.java)
                .apply { isAccessible = true }
            // 在关窗之前确认启动和参数 API 可用。
            starter.javaClass.getDeclaredMethod("execute")
            require(starter.javaClass.declaredMethods.any { it.name == "setActivityOptions" &&
                it.parameterTypes.contentEquals(arrayOf(Bundle::class.java)) })
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
            StarterRequestReplay(controller, obtain, fields, Intent(intent))
        }.getOrNull()

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
