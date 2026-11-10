package io.github.zailinn.flymefreeform.platform.coloros

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 目标应用已经是迷你小窗时，用系统原生入口把它恢复成普通小窗。
 *
 * 对迷你小窗再走 `startZoomWindow` 只会把旧任务提到前台并按启动参数直接重设位置：
 * 无动画跳到中间、尺寸仍是迷你态。系统自己退出迷你态走的是
 * `FlexibleTaskCaptionView.onSingleTapConfirmed`（单击迷你小窗）→
 * `FlexibleTaskController.switchState(task, 1, reason)`，带原生放大动画，这里调同一个入口。
 *
 * 柔性状态取自 `ITaskExt.getFlexibleZoomState()`：1 普通小窗，2 迷你小窗
 * （`FlexibleCaptionView.isMiniZoomState` 即与 2 比较）。
 * 反射目标在首次使用时解析；任何一步失败都返回 false，由调用方照常启动。
 */
internal class ColorOsMiniWindowRestorer(
    private val controller: Any,
) {
    private val access: Access? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            Access(controller.javaClass)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    /** 该应用有迷你小窗并已请求恢复时返回 true；没有迷你小窗或入口不可用时返回 false。 */
    fun restore(packageName: String, userId: Int): Boolean {
        val access = access ?: return false
        return try {
            val task = access.findMiniTask(controller, packageName, userId) ?: return false
            access.switchToOrdinary(controller, task)
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private class Access(controllerClass: Class<*>) {
        private val taskClass = controllerClass.classLoader!!.loadClass(TASK_CLASS)
        private val flexibleTasks = controllerClass.field("mFlexibleTasks")
        private val isTaskInFlexibleState =
            controllerClass.method("isTaskInFlexibleState", taskClass, Int::class.javaPrimitiveType!!)
        private val switchState =
            controllerClass.method("switchState", taskClass, Int::class.javaPrimitiveType!!, String::class.java)
        private val getBasePackageName = taskClass.method("getBasePackageName")
        private val userIdField = taskClass.field("mUserId")

        fun findMiniTask(controller: Any, packageName: String, userId: Int): Any? {
            val tasks = flexibleTasks.get(controller) ?: return null
            // 与窗外关闭读取同一列表的做法一致：锁住列表本身取副本，再逐个判断。
            val snapshot = synchronized(tasks) { (tasks as? Iterable<*>)?.filterNotNull()?.toList().orEmpty() }
            return snapshot.firstOrNull { task ->
                taskClass.isInstance(task) &&
                    getBasePackageName.call(task) == packageName &&
                    userIdField.getInt(task) == userId &&
                    isTaskInFlexibleState.call(controller, task, MINI_STATE) == true
            }
        }

        fun switchToOrdinary(controller: Any, task: Any) {
            switchState.call(controller, task, ORDINARY_STATE, SWITCH_REASON)
        }
    }

    private companion object {
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val ORDINARY_STATE = 1
        const val MINI_STATE = 2
        const val SWITCH_REASON = "FlymeFreeformRadial"
    }
}

private fun Class<*>.field(name: String): Field {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException(name)
}

private fun Class<*>.method(name: String, vararg parameterTypes: Class<*>): Method {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            current = current.superclass
        }
    }
    throw NoSuchMethodException(name)
}

private fun Method.call(instance: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(instance, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
