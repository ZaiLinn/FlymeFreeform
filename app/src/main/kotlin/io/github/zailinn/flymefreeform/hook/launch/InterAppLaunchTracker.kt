package io.github.zailinn.flymefreeform.hook.launch

import android.os.SystemClock
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchMemory
import io.github.zailinn.flymefreeform.log.ModuleLog

/** 跨应用关系的兼容外壳；状态与时间窗规则集中于纯 Kotlin LaunchMemory。 */
internal class InterAppLaunchTracker {
    val memory = LaunchMemory()
    fun record(callerPackage: String, targetPackage: String) {
        memory.record(callerPackage, targetPackage, SystemClock.uptimeMillis())
        ModuleLog.debug("FFINTER") { "INTER_APP_RECORD " + callerPackage + "->" + targetPackage }
    }
    fun consumeIfReturning(callingPackage: String?, targetPackage: String): Boolean {
        val check = memory.checkReturning(callingPackage, targetPackage, SystemClock.uptimeMillis())
        memory.apply(check)
        check.log?.let { code -> ModuleLog.debug("FFINTER") { code } }
        return check.returning
    }
}
