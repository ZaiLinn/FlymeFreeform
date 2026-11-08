package io.github.zailinn.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.zailinn.flymefreeform.R
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.config.OutsideTapCloseMode
import io.github.zailinn.flymefreeform.framework.FrameworkConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 小窗交互的二级页面。
 *
 * 各项独立挂钩、独立判定，互不依赖：
 * - 窗外关闭：扩展标题层触摸区，接管窗外的点击；
 * - 小窗数量：改写 `getMaxWinNum`；
 * - 解除限制：`isSupportFlexibleWindow` 一律放行；
 * - 启动场景转小窗：在 ActivityStarter 改写通知、控制中心与应用间的启动；
 * - 快速上滑：改写小窗手柄的上滑模式。
 *
 * 除窗外关闭有默认取值外，其余能力默认关闭，由用户按需打开。
 */
@Composable
internal fun ZoomOptionsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchChange: (Boolean) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    val outsideTapModes = OutsideTapCloseMode.entries
    val outsideTapItems =
        listOf(
            DropdownItem(text = stringResource(R.string.outside_tap_mode_disabled)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_single)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_double)),
        )
    val followSystemLabel = stringResource(R.string.zoom_max_windows_follow_system)
    val windowCountItems =
        (ModulePreferences.MIN_ZOOM_MAX_WINDOW_COUNT..ModulePreferences.MAX_ZOOM_MAX_WINDOW_COUNT).map { count ->
            DropdownItem(
                text = if (count == ModulePreferences.ZOOM_MAX_WINDOW_FOLLOW_SYSTEM) followSystemLabel else count.toString(),
            )
        }
    SettingsPage(
        title = stringResource(R.string.zoom_limit_removed_title),
        onBack = onBack,
    ) {
        item(key = "options") {
            Card(modifier = Modifier.fillMaxWidth()) {
                OverlaySpinnerPreference(
                    items = outsideTapItems,
                    selectedIndex = outsideTapModes.indexOf(state.settings.outsideTapCloseMode),
                    title = stringResource(R.string.outside_tap_close_title),
                    summary = stringResource(R.string.outside_tap_close_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.TouchApp, enabled) },
                    onSelectedIndexChange = { index ->
                        outsideTapModes.getOrNull(index)?.let(onOutsideTapCloseModeChange)
                    },
                )
                // 下标即取值：0 = 跟随系统，1–5 = 指定个数。
                OverlaySpinnerPreference(
                    items = windowCountItems,
                    selectedIndex = ModulePreferences.coerceZoomMaxWindowCount(state.settings.zoomMaxWindowCount),
                    title = stringResource(R.string.zoom_max_windows_title),
                    summary = stringResource(R.string.zoom_max_windows_summary),
                    enabled = enabled && !state.isUpdating,
                    startAction = { PreferenceIcon(Icons.Rounded.Widgets, enabled) },
                    onSelectedIndexChange = { index ->
                        onZoomMaxWindowCountChange(ModulePreferences.coerceZoomMaxWindowCount(index))
                    },
                )
                SwitchPreference(
                    checked = state.settings.zoomAllowAnyApp,
                    onCheckedChange = onZoomAllowAnyAppChange,
                    title = stringResource(R.string.zoom_allow_any_app_title),
                    summary = stringResource(R.string.zoom_allow_any_app_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Apps, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunch,
                    onCheckedChange = onZoomLaunchChange,
                    title = stringResource(R.string.zoom_launch_title),
                    summary = stringResource(R.string.zoom_launch_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwapHoriz, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.handleSwipeUpToMiniEnabled,
                    onCheckedChange = onHandleSwipeUpToMiniEnabledChange,
                    title = stringResource(R.string.handle_swipe_up_to_mini_title),
                    summary = stringResource(R.string.handle_swipe_up_to_mini_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, enabled) },
                )
            }
        }
    }
}