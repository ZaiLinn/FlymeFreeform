package io.github.zailinn.flymefreeform.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.zailinn.flymefreeform.R
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.framework.FrameworkConnectionState
import io.github.zailinn.flymefreeform.gesture.RadialArcPlan
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 扇形相关的二级页面。
 *
 * 条目按作用分五组：呼出（位置、范围、挂起与暂停条件）、扇形内容（应用、补位、数量、圆弧）、
 * 外观与判定（图标形状、「更多」颜色、命中范围）、中央预览（关闭时收起其附属项）、「更多」面板。
 * 应用条目的增删与排序在「扇形应用」页内维护。
 *
 * 拖动触发范围与命中范围的滑条时，通过 [CornerRangePreview] 与 [HitRangePreview]
 * 叠加显示当前取值，两者只在拖动期间存在。
 */
@Composable
internal fun RadialSettingsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onRadialMaxItemsChange: (Int) -> Unit,
    onRadialArcsChange: (Int) -> Unit,
    onRadialFillSourceChange: (Int) -> Unit,
    onIconShapeChange: (Int) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onPanelNativeListChange: (Boolean) -> Unit,
    onRadialCenterPreviewChange: (Boolean) -> Unit,
    onRadialCenterLabelChange: (Boolean) -> Unit,
    onRadialCenterSizeChange: (Int) -> Unit,
    onRadialMoreColorChange: (Int) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onNavigateToPinnedApps: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    var cornerRangePreviewDp by remember { mutableStateOf<Int?>(null) }
    var hitRangePreviewDp by remember { mutableStateOf<Int?>(null) }
    val left = state.settings.leftCornerEnabled
    val right = state.settings.rightCornerEnabled
    val sideIndex =
        when {
            left && !right -> 1
            !left && right -> 2
            else -> 0
        }
    val appsSummary =
        when {
            !state.settings.pinsSaved -> stringResource(R.string.radial_apps_recent_summary)
            state.settings.pinnedTargets.isEmpty() -> stringResource(R.string.radial_apps_empty_summary)
            else ->
                stringResource(
                    R.string.radial_apps_count_summary,
                    state.settings.pinnedTargets.size +
                        state.settings.innerArcTargets
                            .take(state.settings.effectiveRadialArcCount - 1)
                            .sumOf { it.size },
                )
        }
    val sideItems =
        listOf(
            DropdownItem(text = stringResource(R.string.corner_trigger_side_both)),
            DropdownItem(text = stringResource(R.string.corner_trigger_side_left)),
            DropdownItem(text = stringResource(R.string.corner_trigger_side_right)),
        )
    val fillItems =
        listOf(
            DropdownItem(text = stringResource(R.string.radial_fill_recent)),
            DropdownItem(text = stringResource(R.string.radial_fill_frequent)),
            DropdownItem(text = stringResource(R.string.radial_fill_none)),
        )
    val fillSource = ModulePreferences.coerceRadialFillSource(state.settings.radialFillSource)
    // 下标即取值：0 = 圆形，1 = 方形。
    val shapeItems =
        listOf(
            DropdownItem(text = stringResource(R.string.icon_shape_circle)),
            DropdownItem(text = stringResource(R.string.icon_shape_square)),
        )
    val iconShape = ModulePreferences.coerceIconShape(state.settings.iconShape)
    // 下标 + 1 即圆弧条数；1 条即只有默认圆弧（关闭多圆弧）。
    val arcItems =
        (ModulePreferences.MIN_RADIAL_ARC_COUNT..ModulePreferences.MAX_RADIAL_ARC_COUNT).map { count ->
            DropdownItem(text = stringResource(R.string.radial_arc_count_option, count))
        }
    SettingsPage(
        title = stringResource(R.string.radial_settings_title),
        onBack = onBack,
    ) {
        item(key = "trigger") {
            SettingsGroup(title = stringResource(R.string.settings_group_trigger)) {
                OverlaySpinnerPreference(
                    items = sideItems,
                    selectedIndex = sideIndex,
                    title = stringResource(R.string.corner_trigger_side_title),
                    summary = stringResource(R.string.corner_trigger_side_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, enabled) },
                    onSelectedIndexChange = { index ->
                        when (index) {
                            0 -> onCornerSidesChange(true, true)
                            1 -> onCornerSidesChange(true, false)
                            2 -> onCornerSidesChange(false, true)
                        }
                    },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Straighten,
                    confirmedValue = state.settings.cornerTriggerRangeDp,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.corner_trigger_range_title),
                    summary = stringResource(R.string.corner_trigger_range_summary),
                    minDp = ModulePreferences.MIN_CORNER_TRIGGER_RANGE_DP,
                    maxDp = ModulePreferences.MAX_CORNER_TRIGGER_RANGE_DP,
                    coerceDp = ModulePreferences::coerceCornerTriggerRangeDp,
                    onPreviewChange = { cornerRangePreviewDp = it },
                    onCommit = onCornerTriggerRangeChange,
                )
                SwitchPreference(
                    checked = state.settings.radialKeepOpen,
                    onCheckedChange = onRadialKeepOpenChange,
                    title = stringResource(R.string.radial_keep_open_title),
                    summary = stringResource(R.string.radial_keep_open_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PushPin, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInLandscape,
                    onCheckedChange = onPauseInLandscapeChange,
                    title = stringResource(R.string.pause_in_landscape_title),
                    summary = stringResource(R.string.pause_in_landscape_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.ScreenRotation, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInGameMode,
                    onCheckedChange = onPauseInGameModeChange,
                    title = stringResource(R.string.pause_in_game_mode_title),
                    summary = stringResource(R.string.pause_in_game_mode_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, enabled) },
                )
            }
        }
        item(key = "content") {
            SettingsGroup(title = stringResource(R.string.settings_group_content)) {
                ArrowPreference(
                    title = stringResource(R.string.radial_apps_title),
                    summary = appsSummary,
                    onClick = onNavigateToPinnedApps,
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Apps, enabled) },
                )
                OverlaySpinnerPreference(
                    items = fillItems,
                    selectedIndex = fillSource,
                    title = stringResource(R.string.radial_fill_title),
                    summary =
                        stringResource(
                            when (fillSource) {
                                ModulePreferences.RADIAL_FILL_FREQUENT -> R.string.radial_fill_frequent_summary
                                ModulePreferences.RADIAL_FILL_NONE -> R.string.radial_fill_none_summary
                                else -> R.string.radial_fill_recent_summary
                            },
                        ),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.History, enabled) },
                    onSelectedIndexChange = { index ->
                        if (index != fillSource) onRadialFillSourceChange(index)
                    },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Apps,
                    confirmedValue = state.settings.radialMaxItems,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_max_items_title),
                    summary = stringResource(R.string.radial_max_items_summary),
                    minDp = ModulePreferences.MIN_RADIAL_MAX_ITEMS,
                    maxDp = ModulePreferences.MAX_RADIAL_MAX_ITEMS,
                    coerceDp = ModulePreferences::coerceRadialMaxItems,
                    onPreviewChange = {},
                    onCommit = onRadialMaxItemsChange,
                    showDpUnit = false,
                )
                OverlaySpinnerPreference(
                    items = arcItems,
                    selectedIndex = state.settings.effectiveRadialArcCount - ModulePreferences.MIN_RADIAL_ARC_COUNT,
                    title = stringResource(R.string.radial_multi_arc_title),
                    summary = stringResource(R.string.radial_multi_arc_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.DonutLarge, enabled) },
                    onSelectedIndexChange = { index ->
                        val count = index + ModulePreferences.MIN_RADIAL_ARC_COUNT
                        if (count != state.settings.effectiveRadialArcCount) onRadialArcsChange(count)
                    },
                )
            }
        }
        item(key = "appearance") {
            SettingsGroup(title = stringResource(R.string.settings_group_appearance)) {
                OverlaySpinnerPreference(
                    items = shapeItems,
                    selectedIndex = iconShape,
                    title = stringResource(R.string.icon_shape_title),
                    summary = stringResource(R.string.icon_shape_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Category, enabled) },
                    onSelectedIndexChange = { index ->
                        if (index != iconShape) onIconShapeChange(index)
                    },
                )
                MoreColorPreference(
                    color = ModulePreferences.coerceRadialMoreColor(state.settings.radialMoreColor),
                    enabled = enabled,
                    onColorChange = onRadialMoreColorChange,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.CenterFocusStrong,
                    confirmedValue = state.settings.radialHitRangeDp,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_hit_range_title),
                    summary = stringResource(R.string.radial_hit_range_summary),
                    minDp = ModulePreferences.MIN_RADIAL_HIT_RANGE_DP,
                    maxDp = ModulePreferences.MAX_RADIAL_HIT_RANGE_DP,
                    coerceDp = ModulePreferences::coerceRadialHitRangeDp,
                    onPreviewChange = { hitRangePreviewDp = it },
                    onCommit = onRadialHitRangeChange,
                )
            }
        }
        item(key = "center_preview") {
            SettingsGroup(title = stringResource(R.string.settings_group_center_preview)) {
                SwitchPreference(
                    checked = state.settings.radialCenterPreview,
                    onCheckedChange = onRadialCenterPreviewChange,
                    title = stringResource(R.string.radial_center_preview_title),
                    summary = stringResource(R.string.radial_center_preview_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.ZoomIn, enabled) },
                )
                AnimatedVisibility(
                    visible = state.settings.radialCenterPreview,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        SwitchPreference(
                            checked = state.settings.radialCenterLabel,
                            onCheckedChange = onRadialCenterLabelChange,
                            title = stringResource(R.string.radial_center_label_title),
                            summary = stringResource(R.string.radial_center_label_summary),
                            enabled = enabled && state.settings.radialCenterPreview,
                            startAction = { PreferenceIcon(Icons.Rounded.Title, enabled && state.settings.radialCenterPreview) },
                        )
                        RemoteDpSliderPreference(
                            icon = Icons.Rounded.PhotoSizeSelectLarge,
                            confirmedValue = state.settings.radialCenterSizeDp,
                            isUpdating = state.isUpdating,
                            enabled = enabled && state.settings.radialCenterPreview,
                            title = stringResource(R.string.radial_center_size_title),
                            summary = stringResource(R.string.radial_center_size_summary),
                            minDp = ModulePreferences.MIN_RADIAL_CENTER_SIZE_DP,
                            maxDp = ModulePreferences.MAX_RADIAL_CENTER_SIZE_DP,
                            coerceDp = ModulePreferences::coerceRadialCenterSizeDp,
                            onPreviewChange = {},
                            onCommit = onRadialCenterSizeChange,
                        )
                    }
                }
            }
        }
        item(key = "more_panel") {
            SettingsGroup(title = stringResource(R.string.settings_group_more_panel)) {
                SwitchPreference(
                    checked = state.settings.panelNativeList,
                    onCheckedChange = onPanelNativeListChange,
                    title = stringResource(R.string.panel_native_list_title),
                    summary = stringResource(R.string.panel_native_list_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.GridView, enabled) },
                )
            }
        }
    }
    cornerRangePreviewDp?.let { rangeDp ->
        CornerRangePreview(
            rangeDp = rangeDp,
            leftEnabled = left,
            rightEnabled = right,
        )
    }
    hitRangePreviewDp?.let { rangeDp ->
        HitRangePreview(
            hitRangeDp = rangeDp,
            arcItemCounts =
                RadialArcPlan.expectedArcCounts(
                    outerLimit = state.settings.radialMaxItems,
                    arcCount = state.settings.effectiveRadialArcCount,
                    innerPinCounts = state.settings.innerArcTargets.map { it.size },
                    fillInner = fillSource != ModulePreferences.RADIAL_FILL_NONE,
                ),
            arcCount = state.settings.effectiveRadialArcCount,
            leftEnabled = left,
            rightEnabled = right,
        )
    }
}
