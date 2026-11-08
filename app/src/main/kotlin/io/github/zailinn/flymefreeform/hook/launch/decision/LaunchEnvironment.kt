package io.github.zailinn.flymefreeform.hook.launch.decision

/** 系统查询保持惰性；生产实现只读安全缓存或已核实的厂商入口。 */
internal interface LaunchEnvironment {
    val enabled: Boolean
    val zoomLaunch: Boolean
    val systemUiUid: Int
    val launcherUid: Int
    fun deviceUnlocked(refresh: Boolean): Boolean
    fun isExcluded(pkg: String): Boolean
    fun isThirdParty(pkg: String): Boolean
    fun dispatchOwner(callingPackage: String?, realCallingUid: Int, requestRealUid: Int): String?
    fun zoomShownPackage(): String?
    fun fromControlCenter(pkg: String): Boolean
    fun resolveImplicitTarget(): String?
}
