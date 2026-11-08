package io.github.zailinn.flymefreeform.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zailinn.flymefreeform.R
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.framework.FrameworkConnectionState
import io.github.zailinn.flymefreeform.gesture.RadialArcPlan
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扇形相关的二级页面。
 *
 * 条目按作用分三段：呼出方式（触发位置）、呼出后的行为（保持挂起、应用入口）、
 * 判定数值（触发范围、命中范围、数量上限）。应用条目的增删与排序在「扇形应用」页内维护。
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
    onRadialMultiArcEnabledChange: (Boolean) -> Unit,
    onRadialArcCountChange: (Int) -> Unit,
    onRadialFillSourceChange: (Int) -> Unit,
    onIconShapeChange: (Int) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
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
    SettingsPage(
        title = stringResource(R.string.radial_settings_title),
        onBack = onBack,
    ) {
        item(key = "trigger") {
            Card(modifier = Modifier.fillMaxWidth()) {
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
                SwitchPreference(
                    checked = state.settings.radialKeepOpen,
                    onCheckedChange = onRadialKeepOpenChange,
                    title = stringResource(R.string.radial_keep_open_title),
                    summary = stringResource(R.string.radial_keep_open_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PushPin, enabled) },
                )
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
            }
        }
        item(key = "multi_arc") {
            Card(modifier = Modifier.fillMaxWidth()) {
                MultiArcPreference(
                    checked = state.settings.radialMultiArcEnabled,
                    arcCount = state.settings.radialArcCount,
                    enabled = enabled,
                    onCheckedChange = onRadialMultiArcEnabledChange,
                    onArcCountChange = onRadialArcCountChange,
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
/**
 * 「扇形多圆弧」：开关右侧带展开箭头，展开后选择圆弧条数（1–4 条）。
 *
 * 第 1 条是默认圆弧；新增圆弧向内收拢，容量依次为 5、4、3 项。
 */
@Composable
private fun MultiArcPreference(
    checked: Boolean,
    arcCount: Int,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onArcCountChange: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val showOptions = checked && expanded
    SwitchPreference(
        checked = checked,
        onCheckedChange = { value ->
            if (value) expanded = true
            onCheckedChange(value)
        },
        title = stringResource(R.string.radial_multi_arc_title),
        summary = stringResource(R.string.radial_multi_arc_summary),
        enabled = enabled,
        startAction = { PreferenceIcon(Icons.Rounded.DonutLarge, enabled) },
        endActions = {
            if (checked) {
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector =
                            if (showOptions) {
                                Icons.Rounded.KeyboardArrowUp
                            } else {
                                Icons.Rounded.KeyboardArrowDown
                            },
                        contentDescription =
                            stringResource(
                                if (showOptions) {
                                    R.string.radial_arc_collapse_action
                                } else {
                                    R.string.radial_arc_expand_action
                                },
                            ),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        },
    )
    AnimatedVisibility(
        visible = showOptions,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        ArcCountSelector(
            selected = ModulePreferences.coerceRadialArcCount(arcCount),
            enabled = enabled,
            onSelect = { count ->
                if (count != arcCount) onArcCountChange(count)
            },
        )
    }
}

@Composable
private fun ArcCountSelector(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.radial_arc_count_title),
            color = summaryColor,
            style = MiuixTheme.textStyles.body1,
        )
        Text(
            text = stringResource(R.string.radial_arc_count_summary),
            modifier = Modifier.padding(top = 4.dp),
            color = summaryColor.copy(alpha = 0.7f),
            style = MiuixTheme.textStyles.body2,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (count in ModulePreferences.MIN_RADIAL_ARC_COUNT..ModulePreferences.MAX_RADIAL_ARC_COUNT) {
                val isSelected = count == selected
                val shape = RoundedCornerShape(14.dp)
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(shape)
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .background(primary.copy(alpha = 0.10f), shape)
                                        .border(2.dp, primary, shape)
                                } else {
                                    Modifier
                                },
                            )
                            .selectable(
                                selected = isSelected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelect(count) },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.radial_arc_count_option, count),
                        color =
                            when {
                                !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
                                isSelected -> primary
                                else -> summaryColor
                            },
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
