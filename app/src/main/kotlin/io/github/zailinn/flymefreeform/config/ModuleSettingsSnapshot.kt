package io.github.zailinn.flymefreeform.config

import android.content.SharedPreferences
import io.github.zailinn.flymefreeform.gesture.RadialArcPlan

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    val radialHitRangeDp: Int = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
    val pinsSaved: Boolean = false,
    val pinnedTargets: List<PinnedTarget> = emptyList(),
    val outsideTapCloseMode: OutsideTapCloseMode =
        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE,
    val handleSwipeUpToMiniEnabled: Boolean =
        ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
    val pauseInLandscape: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
    val pauseInGameMode: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
    val verboseLogEnabled: Boolean = ModulePreferences.DEFAULT_VERBOSE_LOG,
    /** 这些应用在前台时不触发扇形菜单。 */
    val blacklistPackages: List<String> = emptyList(),
    /** 小窗扩展·数量上限：系统允许的最大小窗数至少要达到该值。 */
    val zoomMaxWindowCount: Int = ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
    /** 小窗扩展·解除应用准入：isSupportFlexibleWindow 一律放行。 */
    val zoomAllowAnyApp: Boolean = ModulePreferences.DEFAULT_ZOOM_ALLOW_ANY_APP,
    /**
     * 小窗扩展·小窗跳转：通知、控制中心、应用互跳、桌面快捷项与卡片统一以自由浮窗启动。
     *
     * 关闭后不改写任何启动，全部入口回到系统默认的全屏行为。
     */
    val zoomLaunch: Boolean = ModulePreferences.DEFAULT_ZOOM_LAUNCH,
    /** 扇形呼出后未选择任何条目时保持显示，不自动收起。 */
    val radialKeepOpen: Boolean = ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
    val radialMaxItems: Int = ModulePreferences.DEFAULT_RADIAL_MAX_ITEMS,
    /** 扇形多圆弧：默认圆弧以内再向内收拢若干条圆弧，放更多快捷项。 */
    val radialMultiArcEnabled: Boolean = ModulePreferences.DEFAULT_RADIAL_MULTI_ARC_ENABLED,
    /** 多圆弧打开时的圆弧条数（含默认圆弧），1–4。 */
    val radialArcCount: Int = ModulePreferences.DEFAULT_RADIAL_ARC_COUNT,
    /** 扇形空位的补位来源，取值见 ModulePreferences.RADIAL_FILL_*。 */
    val radialFillSource: Int = ModulePreferences.DEFAULT_RADIAL_FILL_SOURCE,
    /** 图标外形，取值见 ModulePreferences.ICON_SHAPE_*。 */
    val iconShape: Int = ModulePreferences.DEFAULT_ICON_SHAPE,
    /** 「更多」面板保持侧边栏原有的「工具 + 全部应用」，不改写为「收藏 + 最近」。 */
    val panelNativeList: Boolean = ModulePreferences.DEFAULT_PANEL_NATIVE_LIST,
    /** 滑选时在屏幕正中显示所选应用的大图标。 */
    val radialCenterPreview: Boolean = ModulePreferences.DEFAULT_RADIAL_CENTER_PREVIEW,
    /** 屏幕正中的卡片是否带应用名；仅在 [radialCenterPreview] 打开时生效。 */
    val radialCenterLabel: Boolean = ModulePreferences.DEFAULT_RADIAL_CENTER_LABEL,
    /** 屏幕中央卡片的图标边长（dp），48–160。 */
    val radialCenterSizeDp: Int = ModulePreferences.DEFAULT_RADIAL_CENTER_SIZE_DP,
    /**
     * 第 2～4 条圆弧各自的固定项（下标 0 对应第 2 条）。空列表表示该圈用最近使用的应用补满。
     * 与圆弧条数无关，始终保存三份。
     */
    val innerArcTargets: List<List<PinnedTarget>> = EMPTY_INNER_ARC_TARGETS,
) {
    /** 默认圆弧与所有内侧圆弧的固定项，用于启动前核对「目标确实被固定过」。 */
    val allPinnedTargets: List<PinnedTarget>
        get() = pinnedTargets + innerArcTargets.flatten()

    /** 当前生效的圆弧条数：多圆弧关闭时恒为 1。 */
    val effectiveRadialArcCount: Int
        get() = if (radialMultiArcEnabled) ModulePreferences.coerceRadialArcCount(radialArcCount) else 1

    fun isBlacklisted(packageName: String?): Boolean =
        packageName != null && blacklistPackages.any { it == packageName }

    fun isPausedByEnvironment(landscape: Boolean, gameMode: Boolean): Boolean =
        (pauseInLandscape && landscape) || (pauseInGameMode && gameMode)

    fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor {
        editor
            .putBoolean(ModulePreferences.KEY_MODULE_ENABLED, enabled)
            .putBoolean(ModulePreferences.KEY_LEFT_CORNER_ENABLED, leftCornerEnabled)
            .putBoolean(ModulePreferences.KEY_RIGHT_CORNER_ENABLED, rightCornerEnabled)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_LANDSCAPE, pauseInLandscape)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_GAME_MODE, pauseInGameMode)
            .putBoolean(ModulePreferences.KEY_VERBOSE_LOG, verboseLogEnabled)
            .putInt(
                ModulePreferences.KEY_ZOOM_MAX_WINDOW_COUNT,
                ModulePreferences.coerceZoomMaxWindowCount(zoomMaxWindowCount),
            )
            .putBoolean(ModulePreferences.KEY_ZOOM_ALLOW_ANY_APP, zoomAllowAnyApp)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH, zoomLaunch)
            // 旧分项键不再写入，升级后一并清除，避免残留值干扰排查。
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_NOTIFICATION)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_CONTROL_CENTER)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_INTER_APP)
            .putBoolean(ModulePreferences.KEY_RADIAL_KEEP_OPEN, radialKeepOpen)
            .putBoolean(ModulePreferences.KEY_PANEL_NATIVE_LIST, panelNativeList)
            .putBoolean(ModulePreferences.KEY_RADIAL_CENTER_PREVIEW, radialCenterPreview)
            .putBoolean(ModulePreferences.KEY_RADIAL_CENTER_LABEL, radialCenterLabel)
            .putInt(
                ModulePreferences.KEY_RADIAL_CENTER_SIZE_DP,
                ModulePreferences.coerceRadialCenterSizeDp(radialCenterSizeDp),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_MAX_ITEMS,
                ModulePreferences.coerceRadialMaxItems(radialMaxItems),
            )
            .putBoolean(ModulePreferences.KEY_RADIAL_MULTI_ARC_ENABLED, radialMultiArcEnabled)
            .putInt(
                ModulePreferences.KEY_RADIAL_ARC_COUNT,
                ModulePreferences.coerceRadialArcCount(radialArcCount),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_FILL_SOURCE,
                ModulePreferences.coerceRadialFillSource(radialFillSource),
            )
            .putInt(
                ModulePreferences.KEY_ICON_SHAPE,
                ModulePreferences.coerceIconShape(iconShape),
            )
            .putString(
                ModulePreferences.KEY_BLACKLIST_PACKAGES,
                blacklistPackages.joinToString("\n"),
            )
            .putInt(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                ModulePreferences.coerceCornerTriggerRangeDp(cornerTriggerRangeDp),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_HIT_RANGE_DP,
                ModulePreferences.coerceRadialHitRangeDp(radialHitRangeDp),
            )
            .putInt(
                ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                outsideTapCloseMode.storedValue,
            )
            .putBoolean(
                ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                handleSwipeUpToMiniEnabled,
            )
        if (pinsSaved) {
            editor.putString(
                ModulePreferences.KEY_PINNED_TARGETS,
                PinnedTargetCodec.encodeList(pinnedTargets),
            )
        } else {
            editor.remove(ModulePreferences.KEY_PINNED_TARGETS)
        }
        editor.remove(ModulePreferences.KEY_CORNER_PINS)
        ModulePreferences.KEY_INNER_ARC_TARGETS.forEachIndexed { index, key ->
            val targets = innerArcTargets.getOrNull(index).orEmpty()
            if (targets.isEmpty()) {
                editor.remove(key)
            } else {
                editor.putString(
                    key,
                    PinnedTargetCodec.encodeList(targets, RadialArcPlan.pinCapacity(index + 1)),
                )
            }
        }
        return editor
    }

    companion object {
        val EMPTY_INNER_ARC_TARGETS: List<List<PinnedTarget>> =
            List(RadialArcPlan.MAX_INNER_ARCS) { emptyList() }

        /** 广播里的内侧圆弧列表：每圈一行编码文本，缺省或格式不符时返回 null。 */
        fun decodeInnerArcTargets(encoded: List<String>?): List<List<PinnedTarget>>? {
            if (encoded == null || encoded.size != RadialArcPlan.MAX_INNER_ARCS) return null
            return encoded.mapIndexed { index, value ->
                PinnedTargetCodec.decodeList(value, RadialArcPlan.pinCapacity(index + 1))
            }
        }

        fun encodeInnerArcTargets(targets: List<List<PinnedTarget>>): ArrayList<String> =
            ArrayList(
                List(RadialArcPlan.MAX_INNER_ARCS) { index ->
                    PinnedTargetCodec.encodeList(
                        targets.getOrNull(index).orEmpty(),
                        RadialArcPlan.pinCapacity(index + 1),
                    )
                },
            )

        fun readFrom(preferences: SharedPreferences): ModuleSettingsSnapshot {
            val enabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_MODULE_ENABLED,
                    ModulePreferences.DEFAULT_ENABLED,
                )
            val leftEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_LEFT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val rightEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_RIGHT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val cornerTriggerRangeDp =
                ModulePreferences.coerceCornerTriggerRangeDp(
                    preferences.getInt(
                        ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                        ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
                    ),
                )
            val radialHitRangeDp =
                ModulePreferences.coerceRadialHitRangeDp(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_HIT_RANGE_DP,
                        ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
                    ),
                )
            val outsideTapCloseMode =
                OutsideTapCloseMode.fromStoredValue(
                    preferences.getInt(
                        ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE.storedValue,
                    ),
                )
            val handleSwipeUpToMiniEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                    ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                )
            val hasTargets = preferences.contains(ModulePreferences.KEY_PINNED_TARGETS)
            val pinsSaved = hasTargets || preferences.contains(ModulePreferences.KEY_CORNER_PINS)
            val pins = if (hasTargets) {
                PinnedTargetCodec.decodeList(preferences.getString(ModulePreferences.KEY_PINNED_TARGETS, "").orEmpty())
            } else {
                PinnedTargetCodec.decodeLegacy(preferences.getString(ModulePreferences.KEY_CORNER_PINS, "").orEmpty())
            }
            val bothCornersDisabled = !leftEnabled && !rightEnabled
            return ModuleSettingsSnapshot(
                enabled = enabled,
                leftCornerEnabled = if (bothCornersDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothCornersDisabled) true else rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                radialHitRangeDp = radialHitRangeDp,
                pinsSaved = pinsSaved,
                pinnedTargets = pins,
                outsideTapCloseMode = outsideTapCloseMode,
                handleSwipeUpToMiniEnabled = handleSwipeUpToMiniEnabled,
                pauseInLandscape = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_LANDSCAPE,
                    ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
                ),
                pauseInGameMode = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_GAME_MODE,
                    ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
                ),
                verboseLogEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_VERBOSE_LOG,
                    ModulePreferences.DEFAULT_VERBOSE_LOG,
                ),
                blacklistPackages = ModulePreferences.decodePackages(
                    preferences.getString(ModulePreferences.KEY_BLACKLIST_PACKAGES, null),
                ),
                zoomMaxWindowCount =
                    ModulePreferences.coerceZoomMaxWindowCount(
                        preferences.getInt(
                            ModulePreferences.KEY_ZOOM_MAX_WINDOW_COUNT,
                            ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
                        ),
                    ),
                zoomAllowAnyApp = preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_ALLOW_ANY_APP,
                    ModulePreferences.DEFAULT_ZOOM_ALLOW_ANY_APP,
                ),
                zoomLaunch = readZoomLaunch(preferences),
                radialKeepOpen = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_KEEP_OPEN,
                    ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
                ),
                panelNativeList = preferences.getBoolean(
                    ModulePreferences.KEY_PANEL_NATIVE_LIST,
                    ModulePreferences.DEFAULT_PANEL_NATIVE_LIST,
                ),
                radialCenterPreview = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_CENTER_PREVIEW,
                    ModulePreferences.DEFAULT_RADIAL_CENTER_PREVIEW,
                ),
                radialCenterLabel = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_CENTER_LABEL,
                    ModulePreferences.DEFAULT_RADIAL_CENTER_LABEL,
                ),
                radialCenterSizeDp =
                    ModulePreferences.coerceRadialCenterSizeDp(
                        preferences.getInt(
                            ModulePreferences.KEY_RADIAL_CENTER_SIZE_DP,
                            ModulePreferences.DEFAULT_RADIAL_CENTER_SIZE_DP,
                        ),
                    ),
                radialMaxItems =
                    ModulePreferences.coerceRadialMaxItems(
                        preferences.getInt(
                            ModulePreferences.KEY_RADIAL_MAX_ITEMS,
                            ModulePreferences.DEFAULT_RADIAL_MAX_ITEMS,
                        ),
                    ),
                radialMultiArcEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_MULTI_ARC_ENABLED,
                    ModulePreferences.DEFAULT_RADIAL_MULTI_ARC_ENABLED,
                ),
                radialArcCount =
                    ModulePreferences.coerceRadialArcCount(
                        preferences.getInt(
                            ModulePreferences.KEY_RADIAL_ARC_COUNT,
                            ModulePreferences.DEFAULT_RADIAL_ARC_COUNT,
                        ),
                    ),
                radialFillSource =
                    ModulePreferences.coerceRadialFillSource(
                        preferences.getInt(
                            ModulePreferences.KEY_RADIAL_FILL_SOURCE,
                            ModulePreferences.DEFAULT_RADIAL_FILL_SOURCE,
                        ),
                    ),
                iconShape =
                    ModulePreferences.coerceIconShape(
                        preferences.getInt(
                            ModulePreferences.KEY_ICON_SHAPE,
                            ModulePreferences.DEFAULT_ICON_SHAPE,
                        ),
                    ),
                innerArcTargets =
                    ModulePreferences.KEY_INNER_ARC_TARGETS.mapIndexed { index, key ->
                        PinnedTargetCodec.decodeList(
                            preferences.getString(key, "").orEmpty(),
                            RadialArcPlan.pinCapacity(index + 1),
                        )
                    },
            )
        }

        /**
         * 读取小窗跳转总开关。
         *
         * 升级前是三个分项开关，任一项为开即视为开启；由此第一次读取会把旧值合并进总开关，
         * 之后只认新键。
         */
        private fun readZoomLaunch(preferences: SharedPreferences): Boolean {
            val legacy =
                preferences.getBoolean(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_NOTIFICATION, false) ||
                    preferences.getBoolean(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_CONTROL_CENTER, false) ||
                    preferences.getBoolean(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_INTER_APP, false)
            return preferences.getBoolean(ModulePreferences.KEY_ZOOM_LAUNCH, legacy)
        }
    }
}
