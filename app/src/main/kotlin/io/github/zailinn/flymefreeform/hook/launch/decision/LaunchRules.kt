package io.github.zailinn.flymefreeform.hook.launch.decision

/** 逐项保留旧启动注入器的动作、包名与时间窗常量。 */
internal object LaunchRules {
    /** UserHandle.PER_USER_RANGE：uid = 用户号 × 它 + appId。 */
    const val PER_USER_RANGE = 100_000

    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

    const val TAG = "FlymeFreeform"
    const val ACTIVITY_STARTER_CLASS = "com.android.server.wm.ActivityStarter"

    /** 系统全局搜索：桌面下滑呼出的搜索页，始终全屏。 */
    const val ACTION_GLOBAL_SEARCH = "android.search.action.GLOBAL_SEARCH"

    /**
     * 应用间跳转一律保持全屏的目标。
     *
     * 曾放入 X（com.twitter.android）；用户 2026-11-08 改为让 X 也以小窗打开，中转页重启改由
     * 注入处理（见启动执行层），名单暂空，保留给以后实测小窗不可用的应用。
     */
    val INTER_APP_FULLSCREEN_TARGETS = emptySet<String>()

    /** 中转页自我重启的识别窗口与最多接管次数。 */
    const val SELF_RELAUNCH_WINDOW_MS = 3_000L
    const val MAX_SELF_RELAUNCH_HOPS = 2

    /** 发起方重发同一链接的识别窗口。 */
    const val DUPLICATE_LINK_MS = 1_500L
    /** 关掉旧小窗后等它退场再重开，避免与退出动画抢同一任务。 */
    const val REOPEN_DELAY_MS = 400L

    /** 控制中心启动应用时携带的字段。 */
    const val EXTRA_QUICK_SETTINGS = "start_from_systemui_qs"

    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 桌面进程：其发起的启动属于图标点击，不计入应用间跳转。 */
    const val LAUNCHER_PACKAGE = "com.android.launcher"

    /** 文件选择与文档选取类动作：始终全屏。 */
    val PICKER_ACTIONS =
        setOf(
        "android.intent.action.GET_CONTENT",
        "android.intent.action.OPEN_DOCUMENT",
        "android.intent.action.OPEN_DOCUMENT_TREE",
        "android.intent.action.PICK",
        )

    /** 拍照、录像：取景界面在小窗里不可用，且调用方在等结果，始终全屏。 */
    val CAPTURE_ACTIONS =
        setOf(
        "android.media.action.IMAGE_CAPTURE",
        "android.media.action.IMAGE_CAPTURE_SECURE",
        "android.media.action.VIDEO_CAPTURE",
        )

    /**
     * 分享面板的组件：包名与类名片段。
     *
     * 分享面板由系统包承载，但在 manifest 的排除名单内；作为用户主动发起的跳转，
     * 此处按组件精确放行。文件选择与权限授权界面保持全屏，不在名单内。
     */
    val SHARE_SHEET_COMPONENTS =
        listOf(
        "android" to "com.android.internal.app.ResolverActivity",
        "com.android.intentresolver" to "ChooserActivity",
        )

    /** system_server 代发启动时的发起包名。 */
    const val SYSTEM_SERVER_PACKAGE = "android"

    /**
     * 由 system_server 直接启动、且不属于用户安装应用的目标。
     *
     * 系统通知的落点常常是系统包（例如经 shell 转发的入口），不会命中
     * 「第三方应用」判据，因此按实测落点单独登记。第三方应用由同一分支的
     * 另一条判据覆盖，无需列入此处。
     */
    val SYSTEM_NOTIFICATION_TARGETS = setOf("org.lsposed.manager", "com.android.shell")


    /** 代表其它应用转交启动的系统服务。 */
    val PENDING_DISPATCH_PACKAGES = setOf("android.ext.services")

    /** 桌面快捷菜单可进入的系统设置包：仅「应用详情」这类入口按小窗打开。 */
    val LAUNCHER_SETTINGS_TARGETS = setOf("com.android.settings", "com.coloros.settings")
    const val OPLUS_LIST_MANAGER = "com.android.server.OplusListManagerImpl"
    const val ZOOM_MANAGER_CLASS = "com.oplus.zoomwindow.OplusZoomWindowManager"
    const val LAUNCH_AT_RIGHT = 14

    /** 收起面板的延迟：过早执行会被启动流程重新展开。 */
    const val SHADE_COLLAPSE_DELAY_MS = 60L

    /** 与 SystemUI 侧 BannerCollapseBridge 约定的动作。 */
    const val ACTION_COLLAPSE_BANNER = "io.github.zailinn.flymefreeform.action.COLLAPSE_BANNER"

    /** 磁贴长按场景允许小窗化系统设置页，仅排除界面自身。 */
    val SKIP_TARGETS_FOR_TILES =
        setOf(
        "com.android.systemui",
        "android",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        )

    /** 保持全屏启动的目标包。 */
    val SKIP_TARGETS =
        setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.coloros.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "android",
        )

    /**
     * 桌面发起（卡片或长按菜单）时保持全屏的目标。
     *
     * 在 SKIP_TARGETS 之外再排除三类：
     * 1. 卸载确认与分享面板 —— 流程中的确认界面，放大到小窗不可用；
     * 2. 桌面自身的页面 —— 编辑模式、文件夹、桌面设置都属于桌面的一部分，
     *    脱离桌面单独开成小窗既无意义也无法返回原位置；
     * 3. 应用锁入口（安全中心）—— 它紧接着要拉起全屏的密码确认页，
     *    入口开成小窗会让同一流程前后窗口形态不一致。
     *
     * 该名单只作用于第六段的卡片分支，不影响桌面快捷项、应用详情、
     * 应用互跳、通知与控制中心。
     */
    val LAUNCHER_CARD_SKIP =
        SKIP_TARGETS +
        setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.intentresolver",
            "com.google.android.intentresolver",
            LAUNCHER_PACKAGE,
            "com.oplus.safecenter",
            "com.coloros.safecenter",
        )


}
