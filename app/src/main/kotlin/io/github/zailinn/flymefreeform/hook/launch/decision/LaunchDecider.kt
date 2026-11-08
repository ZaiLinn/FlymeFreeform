package io.github.zailinn.flymefreeform.hook.launch.decision

import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.ACTION_MAIN
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.ACTION_VIEW
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.FLAG_ACTIVITY_NEW_TASK
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.ACTION_GLOBAL_SEARCH
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.INTER_APP_FULLSCREEN_TARGETS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.SYSTEM_UI_PACKAGE
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.LAUNCHER_PACKAGE
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.PICKER_ACTIONS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.SHARE_SHEET_COMPONENTS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.SYSTEM_SERVER_PACKAGE
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.SYSTEM_NOTIFICATION_TARGETS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.LAUNCHER_SETTINGS_TARGETS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.SKIP_TARGETS_FOR_TILES
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.SKIP_TARGETS
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchRules.LAUNCHER_CARD_SKIP

/** 按旧函数的分支顺序判定；时间与系统查询均由调用方提供，绝不改写 memory。 */
internal object LaunchDecider {
    fun decidePreempt(s: LaunchSnapshot, env: LaunchEnvironment, memory: LaunchMemory, now: Long): LaunchDecision {
        val events = mutableListOf<LaunchEvent>()
        fun pass() = LaunchDecision.PassThrough(events = events.toList())
        if (s.preempting) return pass()
        if (!env.enabled) return pass()
        val callingPackage = s.callingPackage.orEmpty()
        val systemUiUid = env.systemUiUid
        val fromSystemUi = systemUiUid > 0 &&
            (s.callingUid == systemUiUid || s.realCallingUid == systemUiUid || s.requestRealCallingUid == systemUiUid)
        val target = s.preemptTarget ?: return pass()
        val waitsForResult = s.hasResultTo && s.requestCode >= 0
        if (waitsForResult) return pass()
        val selfRelaunch = s.hasData && isSelfRelaunch(env, memory, callingPackage, target, s, now)
        val linkCandidate = !selfRelaunch && s.hasResultTo && !s.hasPendingIntent &&
            isPlainInterAppLink(env, memory, callingPackage, target, s, now, events)
        val alreadyZoomed = linkCandidate && env.zoomShownPackage() == target
        if (alreadyZoomed) events += LaunchEvent.Log("ZOOM_LINK_TARGET_IN_ZOOM_" + s.stage + "_" + target)
        val plainLink = linkCandidate && !alreadyZoomed
        if (s.hasResultTo && !selfRelaunch && !linkCandidate) return pass()
        val linkKey = if (linkCandidate) callingPackage + "|" + target + "|" + s.dataString else null
        if (linkKey != null && memory.duplicate(linkKey, now) && env.deviceUnlocked(false)) {
            events += LaunchEvent.Log("ZOOM_PREEMPT_DUPLICATE_" + s.stage + "_" + target)
            return LaunchDecision.SwallowDuplicate(target, events.toList())
        }
        if (alreadyZoomed) {
            if (!env.deviceUnlocked(true)) return pass()
            return LaunchDecision.ReopenInZoom(target, linkKey, callingPackage, events.toList())
        }
        val eligible = when {
            !env.deviceUnlocked(true) -> false
            selfRelaunch -> true
            plainLink -> true
            env.dispatchOwner(callingPackage, s.realCallingUid, s.requestRealCallingUid) != null &&
                env.zoomLaunch && target !in SKIP_TARGETS -> true
            s.hasPendingIntent -> false
            fromSystemUi -> env.zoomLaunch && target !in SKIP_TARGETS_FOR_TILES
            else -> false
        }
        events += LaunchEvent.Log("ZOOM_DECISION_" + s.stage +
            "_ok" + eligible + "_rt" + (if (s.hasResultTo) 1 else 0) +
            "_self" + (if (selfRelaunch) 1 else 0) + "_link" + (if (plainLink) 1 else 0) +
            "_pi" + (if (s.hasPendingIntent) 1 else 0) + "_su" + (if (fromSystemUi) 1 else 0) +
            "_cp" + callingPackage + "_tg" + target)
        if (!eligible) return pass()
        return LaunchDecision.Preempt(target, linkKey, selfRelaunch, callingPackage, events.toList())
    }

    fun decideInject(s: LaunchSnapshot, env: LaunchEnvironment, memory: LaunchMemory, now: Long): LaunchDecision {
        if (!env.enabled || s.zoomAppliedExtra) return LaunchDecision.PassThrough()
        val events = mutableListOf<LaunchEvent>()
        val systemUiUid = env.systemUiUid
        val selfTarget = s.componentPackage ?: s.explicitPackage ?: s.preemptTarget
        val selfInject = !s.hasData && selfTarget != null &&
            isSelfRelaunch(env, memory, s.callingPackage, selfTarget, s, now)
        val reason = if (selfInject) "self_relaunch" else
            reasonFor(s, env, memory, now, systemUiUid, events)
                ?: return LaunchDecision.PassThrough(events = events.toList())
        val destination = s.componentPackage ?: s.explicitPackage
        val record = if (reason == "inter_app" && s.callingPackage != null && destination != null)
            s.callingPackage to destination else null
        return LaunchDecision.Inject(reason, selfInject, selfTarget, record, events.toList())
    }

    private fun isSelfRelaunch(env: LaunchEnvironment, memory: LaunchMemory, callingPackage: String?,
        target: String, s: LaunchSnapshot, now: Long): Boolean =
        env.zoomLaunch && callingPackage == target && memory.selfRelaunch(target, now) &&
            (s.flags and FLAG_ACTIVITY_NEW_TASK) != 0 && !env.isExcluded(target)

    private fun isPlainInterAppLink(env: LaunchEnvironment, memory: LaunchMemory, callingPackage: String,
        target: String, s: LaunchSnapshot, now: Long, events: MutableList<LaunchEvent>): Boolean =
        env.zoomLaunch && s.action == ACTION_VIEW && callingPackage.isNotEmpty() && callingPackage != target &&
            callingPackage != LAUNCHER_PACKAGE && callingPackage != SYSTEM_UI_PACKAGE && target !in SKIP_TARGETS &&
            target !in INTER_APP_FULLSCREEN_TARGETS && !env.isExcluded(target) && env.isThirdParty(callingPackage) &&
            !returning(memory, callingPackage, target, now, events)

    private fun returning(memory: LaunchMemory, callingPackage: String?, target: String, now: Long,
        events: MutableList<LaunchEvent>): Boolean {
        val check = memory.checkReturning(callingPackage, target, now)
        if (check.clearPending) events += LaunchEvent.ReturnUpdate(check)
        check.log?.let { events += LaunchEvent.Log(it, "FFINTER") }
        return check.returning
    }

    private fun isShareSheet(s: LaunchSnapshot): Boolean {
        val pkg = s.componentPackage ?: return false
        val name = s.componentClass ?: return false
        return SHARE_SHEET_COMPONENTS.any { (candidate, simpleName) -> pkg == candidate && name.contains(simpleName) }
    }

    private fun reasonFor(s: LaunchSnapshot, env: LaunchEnvironment, memory: LaunchMemory, now: Long,
        systemUiUid: Int, events: MutableList<LaunchEvent>): String? {
        val callingUid = s.callingUid
        val realCallingUid = s.realCallingUid
        val requestRealUid = s.requestRealCallingUid
        val callingPackage = s.callingPackage
        val hasResultTo = s.hasResultTo
        // 「小窗跳转」是唯一的启动改写开关：关闭时任何入口都不改写，
        // 全部启动回到系统默认的全屏行为。
        if (!env.zoomLaunch) return null
        if (s.zoomSourceExtra == 1) {
            return "notification"
        }
        if (s.quickSettingsExtra) return "control_center"
        // 系统全局搜索（桌面下滑呼出的搜索页）始终全屏。
        // 必须在桌面「卡片」判定之前：桌面以 startActivityForResult 拉起搜索页
        // （cp=桌面、rt=1、action 非 MAIN），正好命中卡片分支，会被开成小窗。
        if (s.action == ACTION_GLOBAL_SEARCH) {
            events += LaunchEvent.Log("ZOOM_SKIP_GLOBAL_SEARCH")
            return null
        }
        // 通知点击经 PendingIntent 下发，发起方为 SystemUI。
        //
        // 常规通知由 SystemUI 的通知启动入口改写参数，此处若再次写入会覆盖系统已设定的
        // 值；但系统通知（如电池、下载完成）走的是另一条路径，SystemUI 并不改写。
        // 因此不在这里放弃，改为交由下方判定处理：已被改写的启动会在 alreadyZoom
        // 处原样放行，未改写的才补写参数。
        val systemUiPending = s.hasPendingIntent && callingPackage == SYSTEM_UI_PACKAGE
        // 大量跳转以 action 隐式启动，没有显式组件；解析出目标包后才能参与判定。
        val target =
            s.componentPackage
                ?: s.explicitPackage
                ?: env.resolveImplicitTarget()
                ?: return null
        // 排除列表内的应用不参与任何启动改写。
        if (env.isExcluded(target)) {
            events += LaunchEvent.Log("ZOOM_SKIPPED_EXCLUDED_" + target)
            return null
        }

        // 目标应用完成操作后重新唤起发起方，特征与主动跳转相同（跨包、携带 resultTo），
        // 但这次启动用于回到原应用，不参与浮窗化。关闭浮窗交由系统的返回入口处理。
        if (returning(memory, callingPackage, target, now, events)) {
            events += LaunchEvent.Log("ZOOM_INTER_APP_RETURN")
            return null
        }

        // 通知点击：目标非系统组件时按小窗启动。
        if (systemUiPending) {
            return if (target !in SKIP_TARGETS) "notification" else null
        }

        // 文件选择类界面保持全屏：这类页面依赖完整的列表与预览布局，
        // 在小窗内可用性差，且调用频繁。该判定先于其余分支，避免被结果回传条件命中。
        if (s.action in PICKER_ACTIONS) {
            events += LaunchEvent.Log("ZOOM_SKIPPED_PICKER")
            return null
        }

        // 携带 resultTo 的跨应用启动：调用方在等待结果。
        // 本方法只补 ActivityOptions，不另起一次启动，结果回传链路保持完整。
        //
        // 桌面同样以 startActivityForResult 启动应用，若不做区分会把每一次图标点击
        // 都当成应用间跳转；系统界面自身的启动由后续分支处理。两者一律保持全屏。
        // 应用内部页面之间的跳转同样携带 resultTo，因此还要求目标与发起方不同包。
        //
        // 该判定先于 SKIP_TARGETS：分享面板由系统包承载，但属于用户主动发起的跳转，
        // 按组件精确放行，其余系统组件维持全屏。
        // 链接一律全屏打开的应用（见 INTER_APP_FULLSCREEN_TARGETS），不注入小窗参数。
        // 只管应用间跳转：桌面卡片、快捷项与 SystemUI 入口照常。
        if (target in INTER_APP_FULLSCREEN_TARGETS &&
            callingPackage != null &&
            callingPackage != target &&
            callingPackage != LAUNCHER_PACKAGE &&
            callingPackage != SYSTEM_UI_PACKAGE
        ) {
            events += LaunchEvent.Log("ZOOM_SKIP_INTER_APP_FULLSCREEN_" + target)
            return null
        }
        if (hasResultTo &&
            callingPackage != null &&
            callingPackage != LAUNCHER_PACKAGE &&
            callingPackage != SYSTEM_UI_PACKAGE &&
            callingPackage != target &&
            (target !in SKIP_TARGETS || isShareSheet(s))
        ) {
            return "inter_app"
        }
        // 跨应用启动：发起方与目标均为第三方应用且不同包。
        //
        // 登录、分享、深链等场景的递交方式各不相同：有的携带 resultTo，有的经
        // PendingIntent，有的两者皆无，仅凭这些标志无法覆盖全部入口。
        // 此分支以「双方都是用户安装的应用」为准，覆盖上述分支未命中的情形；
        // 系统组件与桌面图标点击已在前置条件中排除。
        if (callingPackage != null &&
            callingPackage != target &&
            callingPackage != LAUNCHER_PACKAGE &&
            s.action != ACTION_MAIN &&
            env.isThirdParty(callingPackage) &&
            env.isThirdParty(target)
        ) {
            return "inter_app"
        }
        launcherOriginReason(s, env, target, callingPackage, realCallingUid, requestRealUid, hasResultTo)
            ?.let { return it }
        if (target in SKIP_TARGETS) return null
        // system_server 以 MAIN 启动应用的两类来源：
        // 1. 通知点击，落点通常是系统包，见 SYSTEM_NOTIFICATION_TARGETS；
        // 2. 无桌面入口的模块管理工具（如 LSPosed 管理器）经 root 权限代发，
        //    落点是用户安装的应用，没有来源标记也没有 PendingIntent。
        // 两者都按应用跳转处理，以自由浮窗打开。
        if (callingPackage == SYSTEM_SERVER_PACKAGE && s.action == ACTION_MAIN) {
            if (target in SYSTEM_NOTIFICATION_TARGETS || env.isThirdParty(target)) {
                return "system_launch"
            }
        }
        // 通知栏与控制中心的启动均由 SystemUI 发起，需进一步区分：
        // 磁贴类启动携带 quicksettings 相关 action，交给注入器处理；
        // 其余来自 SystemUI 的启动属于通知栏或横幅点击，已由浮窗通道接管，
        // 再次注入会覆盖系统参数，表现为窗口延迟出现且无过渡动画。
        val fromSystemUi =
            systemUiUid > 0 &&
                (callingUid == systemUiUid || realCallingUid == systemUiUid || requestRealUid == systemUiUid)
        if (fromSystemUi) {
            val action = s.action.orEmpty()
            val isTileLaunch =
                action.contains("quicksettings", ignoreCase = true) ||
                    action.contains("QS_TILE", ignoreCase = true)
            if (isTileLaunch) {
                return "control_center"
            }
            events += LaunchEvent.Log("ZOOM_LEFT_SHADE_TO_NATIVE")
            return null
        }
        // 控制中心内点击应用图标时，ColorOS 转由桌面代理启动，发起 uid 为 launcher。
        // 系统在 OplusListManagerImpl 中记录该包来自控制中心，据此判定。
        if (env.fromControlCenter(target)) {
            return "control_center_launcher"
        }
        return null
    }

    private fun launcherOriginReason(s: LaunchSnapshot, env: LaunchEnvironment, target: String,
        callingPackage: String?, realCallingUid: Int, requestRealUid: Int, hasResultTo: Boolean): String? {
        val fromLauncherProcess = callingPackage == LAUNCHER_PACKAGE
        // 卡片页：桌面以 startActivityForResult 打开，携带卡片自己的 action。
        // 确认类界面（安装器、分享面板）列在排除名单内，仍按全屏处理。
        if (fromLauncherProcess &&
            hasResultTo &&
            s.action != ACTION_MAIN &&
            target !in LAUNCHER_CARD_SKIP
        ) {
            return "launcher_card"
        }
        // 快捷菜单里的系统设置入口（应用详情 / 应用信息）：桌面直接发起，不带 resultTo。
        // 设置页整包在保持全屏名单内，仅这一条发起路径放行；应用内跳设置、权限授权页不变。
        if (fromLauncherProcess &&
            !hasResultTo &&
            s.action != ACTION_MAIN &&
            target in LAUNCHER_SETTINGS_TARGETS
        ) {
            return "launcher_settings"
        }
        // 长按快捷项：系统经 LauncherApps.startShortcut 解析出快捷方式的 Intent 后，
        // 以「快捷方式所属包」作为 callingPackage 代发，realCallingUid 仍是桌面。
        // action 不参与判定：快捷项的内核就是应用自己的启动入口，QQ 这类直接以
        // ACTION_MAIN 加参数下发（实测 flags 0x14000000）。
        val launcherUid = env.launcherUid
        val fromShortcut =
            callingPackage != null &&
                callingPackage == target &&
                launcherUid > 0 &&
                (realCallingUid == launcherUid || requestRealUid == launcherUid)
        if (fromShortcut && !hasResultTo && target !in SKIP_TARGETS) {
            return "launcher_shortcut"
        }
        return null
    }

}
