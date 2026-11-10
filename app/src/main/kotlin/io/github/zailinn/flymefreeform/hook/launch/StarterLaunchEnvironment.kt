package io.github.zailinn.flymefreeform.hook.launch

import io.github.zailinn.flymefreeform.hook.ModuleEnvironmentState
import io.github.zailinn.flymefreeform.hook.ProcessConfiguration
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchEnvironment
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules

/** 平台查询适配器；可缓存的未知锁序查询只读 StarterQueryCache，系统状态保持按需查询。 */
internal class StarterLaunchEnvironment(
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState?,
    private val queries: StarterQueryCache,
) : LaunchEnvironment {
    override val enabled get() = configuration.snapshot.enabled
    override val zoomLaunch get() = configuration.snapshot.zoomLaunch
    override val zoomLaunchCard get() = configuration.snapshot.zoomLaunchCard
    override val systemUiUid get() = queries.uid(LaunchRules.SYSTEM_UI_PACKAGE)
    override val launcherUid get() = queries.uid(LaunchRules.LAUNCHER_PACKAGE)
    /** 不在 WM 锁内刷新厂商 Keyguard 实现，只读环境在主线程广播中维护的缓存。 */
    override fun deviceUnlocked(refresh: Boolean) = environment?.isDeviceUnlocked(false) != false
    override fun isExcluded(pkg: String) = configuration.snapshot.isBlacklisted(pkg) || environment?.isExcluded(pkg) == true
    override fun isThirdParty(pkg: String) = queries.isThirdParty(pkg)
    override fun fromControlCenter(pkg: String) = false
    override fun resolveImplicitTarget(): String? = null
    override fun dispatchOwner(callingPackage: String?, realCallingUid: Int, requestRealUid: Int): String? {
        if (callingPackage == null || callingPackage !in LaunchRules.PENDING_DISPATCH_PACKAGES) return null
        val owner = queries.owner(realCallingUid) ?: queries.owner(requestRealUid) ?: return null
        return owner.takeIf { queries.isThirdParty(it) }
    }
    /**
     * 可在 WM 锁内调用：既有 ColorOS 反编译确认仅重入 WM 锁及 OplusFlexibleInfoManager 叶子锁，
     * 不进入 AMS；依据见计划 A2，保持同步查询白名单。
     */
    override fun zoomShownPackage(): String? = runCatching {
        val (getInstance, getState) = zoomState ?: return null
        val instance = getInstance.invoke(null) ?: return null
        val info = getState.invoke(instance) ?: return null
        val pkg = info.javaClass.getField("zoomPkg").get(info) as? String
        if (info.javaClass.getField("windowShown").getBoolean(info)) pkg else null
    }.getOrNull()

    /** 只解析一次；解析失败为 null，按「没有小窗在显示」处理，与逐次反射失败时相同。 */
    private val zoomState by lazy {
        runCatching {
            val type = Class.forName(LaunchRules.ZOOM_MANAGER_CLASS)
            type.getMethod("getInstance") to type.getMethod("getCurrentZoomWindowState")
        }.getOrNull()
    }
}
