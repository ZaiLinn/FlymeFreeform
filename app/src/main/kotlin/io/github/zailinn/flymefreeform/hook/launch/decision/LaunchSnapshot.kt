package io.github.zailinn.flymefreeform.hook.launch.decision

/** 反射层提供的一次启动快照；不携带平台对象，不提前查询系统。 */
internal data class LaunchSnapshot(
    val stage: String = "execute",
    val action: String? = null,
    val dataString: String? = null,
    val flags: Int = 0,
    val componentPackage: String? = null,
    val componentClass: String? = null,
    val explicitPackage: String? = null,
    val resolvedTarget: String? = null,
    val callingPackage: String? = null,
    val callingUid: Int = 0,
    val rawCallingUid: Int = 0,
    val realCallingUid: Int = 0,
    val requestRealCallingUid: Int = -1,
    val callingPid: Int = 0,
    val hasResultTo: Boolean = false,
    val requestCode: Int = 0,
    val hasPendingIntent: Boolean = false,
    val hasIntentGrants: Boolean = false,
    val zoomSourceExtra: Int = 0,
    val zoomAppliedExtra: Boolean = false,
    val quickSettingsExtra: Boolean = false,
    val optionsAlreadyZoom: Boolean = false,
    /** 启动参数写明全屏（windowingMode 1）。 */
    val optionsFullscreen: Boolean = false,
    val preempting: Boolean = false,
) {
    val hasData: Boolean get() = dataString != null
    val preemptTarget: String? get() = componentPackage ?: resolvedTarget ?: explicitPackage
}
