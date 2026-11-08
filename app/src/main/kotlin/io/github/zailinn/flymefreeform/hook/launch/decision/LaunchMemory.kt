package io.github.zailinn.flymefreeform.hook.launch.decision

/** 启动时间窗与跨应用关系。所有时间由执行层显式传入；判定只调用只读方法。 */
internal class LaunchMemory {
    @Volatile private var lastZoomTarget: String? = null
    @Volatile private var lastZoomAt = 0L
    @Volatile private var lastZoomHops = 0
    @Volatile private var lastLinkKey: String? = null
    @Volatile private var lastLinkAt = 0L
    private var caller: String? = null
    private var target: String? = null
    private var recordedAt = 0L
    private var returnCaller: String? = null
    private var returnTarget: String? = null
    private var returnUntil = 0L

    @Synchronized fun markZoomed(target: String, selfRelaunch: Boolean, now: Long) {
        lastZoomHops = if (selfRelaunch) lastZoomHops + 1 else 0
        lastZoomTarget = target
        lastZoomAt = now
    }
    @Synchronized fun markLink(key: String?, now: Long) { lastLinkKey = key; lastLinkAt = now }
    @Synchronized fun duplicate(key: String, now: Long): Boolean = key == lastLinkKey && now - lastLinkAt < LaunchRules.DUPLICATE_LINK_MS
    @Synchronized fun selfRelaunch(target: String, now: Long): Boolean = target == lastZoomTarget &&
        now - lastZoomAt < LaunchRules.SELF_RELAUNCH_WINDOW_MS && lastZoomHops < LaunchRules.MAX_SELF_RELAUNCH_HOPS

    @Synchronized fun record(callerPackage: String, targetPackage: String, now: Long) {
        caller = callerPackage; target = targetPackage; recordedAt = now
    }

    data class ReturnCheck(val returning: Boolean, val clearPending: Boolean = false,
        val caller: String? = null, val target: String? = null, val until: Long = 0L, val log: String? = null)

    /** 与旧 consumeIfReturning 的每个返回一一对应，但不在这里清空记录。 */
    @Synchronized fun checkReturning(callingPackage: String?, targetPackage: String, now: Long): ReturnCheck {
        if (now < returnUntil && callingPackage == returnTarget && targetPackage == returnCaller) return ReturnCheck(true)
        val pendingCaller = caller ?: return ReturnCheck(false)
        val pendingTarget = target ?: return ReturnCheck(false)
        if (now - recordedAt > 120_000L) return ReturnCheck(false, clearPending = true, log = "INTER_APP_CLEAR_EXPIRED")
        if (callingPackage != pendingTarget || targetPackage != pendingCaller) return ReturnCheck(false)
        return ReturnCheck(true, true, pendingCaller, pendingTarget, now + 2_000L,
            "INTER_APP_RETURN_" + pendingTarget + "->" + pendingCaller)
    }

    @Synchronized fun apply(check: ReturnCheck) {
        if (check.caller != null) {
            returnCaller = check.caller; returnTarget = check.target; returnUntil = check.until
        }
        if (check.clearPending) { caller = null; target = null; recordedAt = 0L }
    }
}
