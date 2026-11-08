package io.github.zailinn.flymefreeform.config

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val ACTION_PINNED_TARGETS_CHANGED =
        "io.github.zailinn.flymefreeform.action.PINNED_TARGETS_CHANGED"
    const val EXTRA_PINNED_TARGETS = "pinned_targets"
    const val EXTRA_PINS_SAVED = "pins_saved"
    /** 内侧圆弧（第 2～4 条）的固定项，按圈序排列的已编码列表。 */
    const val EXTRA_INNER_ARC_TARGETS = "inner_arc_targets"
    const val EXTRA_MODULE_ENABLED = "module_enabled"
    const val KEY_MODULE_ENABLED = "enabled"
    const val KEY_LEFT_CORNER_ENABLED = "corner_left_enabled"
    const val KEY_RIGHT_CORNER_ENABLED = "corner_right_enabled"
    const val KEY_CORNER_TRIGGER_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_RADIAL_HIT_RANGE_DP = "radial_hit_range_dp"
    const val KEY_PINNED_TARGETS = "corner_targets_v2"
    const val KEY_CORNER_PINS = "corner_pins_v1"
    /**
     * 第 2～4 条圆弧各自的固定项，与默认圆弧的 [KEY_PINNED_TARGETS] 分开保存。
     * 减少圆弧条数只是不显示多出的圆弧，这些键保持原样，再次增加时恢复原来的固定项。
     * 键不存在或列表为空表示该圈用最近使用的应用补满。
     */
    val KEY_INNER_ARC_TARGETS: List<String> =
        listOf("corner_targets_arc2_v1", "corner_targets_arc3_v1", "corner_targets_arc4_v1")
    const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode_v1"
    const val KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED = "handle_swipe_up_to_mini_enabled"
    const val KEY_PAUSE_IN_LANDSCAPE = "pause_in_landscape"
    const val KEY_PAUSE_IN_GAME_MODE = "pause_in_game_mode"
    const val KEY_VERBOSE_LOG = "verbose_log_enabled"
    const val KEY_BLACKLIST_PACKAGES = "foreground_blacklist_v1"
    const val KEY_ZOOM_MAX_WINDOW_COUNT = "zoom_max_window_count"
    const val KEY_ZOOM_ALLOW_ANY_APP = "zoom_allow_any_app"
    /** 小窗跳转总开关：通知、控制中心、应用互跳、桌面快捷项与卡片统一受它控制。 */
    const val KEY_ZOOM_LAUNCH = "zoom_launch"

    /** 旧版三个分项开关的键，仅用于升级后迁移取值。 */
    const val LEGACY_KEY_ZOOM_LAUNCH_NOTIFICATION = "zoom_launch_notification"
    const val LEGACY_KEY_ZOOM_LAUNCH_CONTROL_CENTER = "zoom_launch_control_center"
    const val LEGACY_KEY_ZOOM_LAUNCH_INTER_APP = "zoom_launch_inter_app"
    const val KEY_RADIAL_KEEP_OPEN = "radial_keep_open"
    const val KEY_RADIAL_MAX_ITEMS = "radial_max_items"
    const val KEY_RADIAL_MULTI_ARC_ENABLED = "radial_multi_arc_enabled"
    const val KEY_RADIAL_ARC_COUNT = "radial_arc_count"
    const val KEY_RADIAL_FILL_SOURCE = "radial_fill_source"
    const val KEY_ICON_SHAPE = "icon_shape"
    const val KEY_PANEL_NATIVE_LIST = "panel_native_list"
    const val KEY_RADIAL_CENTER_PREVIEW = "radial_center_preview"
    const val KEY_RADIAL_CENTER_LABEL = "radial_center_label"
    const val KEY_RADIAL_CENTER_SIZE_DP = "radial_center_size_dp"
    const val KEY_RADIAL_MORE_COLOR = "radial_more_color"
    const val DEFAULT_ENABLED = false
    const val DEFAULT_CORNER_ENABLED = true
    const val DEFAULT_CORNER_TRIGGER_RANGE_DP = 84
    const val DEFAULT_RADIAL_HIT_RANGE_DP = 100
    val DEFAULT_OUTSIDE_TAP_CLOSE_MODE = OutsideTapCloseMode.SingleTap
    const val DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED = true
    const val DEFAULT_PAUSE_IN_LANDSCAPE = true
    const val DEFAULT_PAUSE_IN_GAME_MODE = true
    const val DEFAULT_VERBOSE_LOG = false
    const val DEFAULT_ZOOM_MAX_WINDOW_COUNT = 1

    /** 小窗数量取此值时不改写 `getMaxWinNum`，上限交给系统或其他模块（如 LuckyTool）。 */
    const val ZOOM_MAX_WINDOW_FOLLOW_SYSTEM = 0
    const val MIN_ZOOM_MAX_WINDOW_COUNT = ZOOM_MAX_WINDOW_FOLLOW_SYSTEM
    const val MAX_ZOOM_MAX_WINDOW_COUNT = 5
    const val DEFAULT_ZOOM_ALLOW_ANY_APP = false
    const val DEFAULT_ZOOM_LAUNCH = false
    const val DEFAULT_RADIAL_KEEP_OPEN = false
    const val DEFAULT_PANEL_NATIVE_LIST = false
    const val DEFAULT_RADIAL_CENTER_PREVIEW = false
    const val DEFAULT_RADIAL_CENTER_LABEL = true
    /** 屏幕中央所选应用卡片里的图标边长。 */
    const val DEFAULT_RADIAL_CENTER_SIZE_DP = 96
    /** 「更多」按钮底板颜色（ARGB）；0 表示跟随系统深浅色。 */
    const val DEFAULT_RADIAL_MORE_COLOR = 0
    const val MIN_RADIAL_CENTER_SIZE_DP = 48
    const val MAX_RADIAL_CENTER_SIZE_DP = 160
    /** 默认圆弧固定项的上限。内侧圆弧各自的上限见 RadialArcPlan.pinCapacity。 */
    const val MAX_PINNED_APPS = 6
    /** 默认圆弧同时显示的条目数：1–6，默认 6。 */
    const val DEFAULT_RADIAL_MAX_ITEMS = 6
    const val MIN_RADIAL_MAX_ITEMS = 1
    const val MAX_RADIAL_MAX_ITEMS = 6
    const val DEFAULT_RADIAL_MULTI_ARC_ENABLED = false
    /** 多圆弧打开后的圆弧条数：1–4，默认 2。 */
    const val DEFAULT_RADIAL_ARC_COUNT = 2
    const val MIN_RADIAL_ARC_COUNT = 1
    const val MAX_RADIAL_ARC_COUNT = 4
    /**
     * 扇形空位的补位来源：最近使用（最近任务列表）、最常使用（近 7 天前台时长），
     * 或不补位（未自定义的内圈留空，默认圆弧仍用最近使用补位）。
     */
    const val RADIAL_FILL_RECENT = 0
    const val RADIAL_FILL_FREQUENT = 1
    const val RADIAL_FILL_NONE = 2
    const val DEFAULT_RADIAL_FILL_SOURCE = RADIAL_FILL_RECENT

    /** 扇形与模块应用列表的图标外形：统一为圆形或圆角方形。 */
    const val ICON_SHAPE_CIRCLE = 0
    const val ICON_SHAPE_SQUARE = 1
    const val DEFAULT_ICON_SHAPE = ICON_SHAPE_CIRCLE
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24
    const val MAX_CORNER_TRIGGER_RANGE_DP = 160
    const val MIN_RADIAL_HIT_RANGE_DP = 40
    const val MAX_RADIAL_HIT_RANGE_DP = 140

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)

    fun coerceRadialHitRangeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_HIT_RANGE_DP, MAX_RADIAL_HIT_RANGE_DP)

    /**
     * 0 保留为「跟随系统」；自定义颜色保留透明度，但不低于 [MIN_RADIAL_MORE_ALPHA]，
     * 避免按钮几乎看不见，也避免全透明的颜色与 0 混淆。
     */
    fun coerceRadialMoreColor(value: Int): Int {
        if (value == DEFAULT_RADIAL_MORE_COLOR) return value
        return customRadialMoreColor(value)
    }

    /**
     * 取色器选出的颜色一律当作自定义：透明度抬到 [MIN_RADIAL_MORE_ALPHA] 以上，结果永不为 0。
     * 否则选中全透明黑色（0x00000000）会被当成「跟随系统」。
     */
    fun customRadialMoreColor(value: Int): Int {
        val alpha = (value ushr 24).coerceAtLeast(MIN_RADIAL_MORE_ALPHA)
        return (alpha shl 24) or (value and 0xFFFFFF)
    }

    const val MIN_RADIAL_MORE_ALPHA = 0x33

    fun coerceRadialCenterSizeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_CENTER_SIZE_DP, MAX_RADIAL_CENTER_SIZE_DP)

    fun coerceZoomMaxWindowCount(value: Int): Int =
        value.coerceIn(MIN_ZOOM_MAX_WINDOW_COUNT, MAX_ZOOM_MAX_WINDOW_COUNT)

    fun coerceRadialMaxItems(value: Int): Int =
        value.coerceIn(MIN_RADIAL_MAX_ITEMS, MAX_RADIAL_MAX_ITEMS)

    fun coerceRadialArcCount(value: Int): Int =
        value.coerceIn(MIN_RADIAL_ARC_COUNT, MAX_RADIAL_ARC_COUNT)

    fun coerceRadialFillSource(value: Int): Int =
        if (value in RADIAL_FILL_RECENT..RADIAL_FILL_NONE) value else DEFAULT_RADIAL_FILL_SOURCE

    fun coerceIconShape(value: Int): Int =
        if (value in ICON_SHAPE_CIRCLE..ICON_SHAPE_SQUARE) value else DEFAULT_ICON_SHAPE

    /** 黑名单包名：换行分隔，逐条校验，最多 200 条。 */
    fun decodePackages(raw: String?): List<String> =
        raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { PACKAGE_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BLACKLIST_PACKAGES)
            .toList()

    fun encodePackages(packages: List<String>): String =
        packages
            .filter { PACKAGE_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BLACKLIST_PACKAGES)
            .joinToString("\n")

    private val PACKAGE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private const val MAX_BLACKLIST_PACKAGES = 200
}
