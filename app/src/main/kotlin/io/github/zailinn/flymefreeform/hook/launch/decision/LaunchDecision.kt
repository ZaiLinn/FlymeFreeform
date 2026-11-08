package io.github.zailinn.flymefreeform.hook.launch.decision

/** 日志与返回指纹更新按旧调用顺序交给执行层，不在判定时产生副作用。 */
internal sealed interface LaunchEvent {
    data class Log(val code: String, val tag: String = "FlymeFreeform") : LaunchEvent
    data class ReturnUpdate(val check: LaunchMemory.ReturnCheck) : LaunchEvent
}

/** 五类结果分别对应原启动、重复吞掉、同步接管、异步重开和参数注入。 */
internal sealed interface LaunchDecision {
    val events: List<LaunchEvent>
    data class PassThrough(val log: String? = null, override val events: List<LaunchEvent> = emptyList()) : LaunchDecision
    data class SwallowDuplicate(val target: String, override val events: List<LaunchEvent> = emptyList()) : LaunchDecision
    data class Preempt(val target: String, val linkKey: String?, val selfRelaunch: Boolean, val callingPackage: String,
        override val events: List<LaunchEvent> = emptyList()) : LaunchDecision
    data class ReopenInZoom(val target: String, val linkKey: String?, val callingPackage: String,
        override val events: List<LaunchEvent> = emptyList()) : LaunchDecision
    data class Inject(val reason: String, val markSelfRelaunch: Boolean = false, val selfTarget: String? = null,
        val recordInterApp: Pair<String, String>? = null, override val events: List<LaunchEvent> = emptyList()) : LaunchDecision
}
