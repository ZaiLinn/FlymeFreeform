package io.github.zailinn.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Dashboard
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 小窗设置：排除列表置顶并供小窗跳转共用；其下分「打开方式」与「小窗操作」两组。
 * 横屏与游戏模式开关只暂停扇形呼出，已移到扇形设置的「呼出」组。
 *
 * 各项独立挂钩、独立判定，互不依赖：
 * - 窗外关闭：扩展标题层触摸区，接管窗外的点击；
 * - 小窗数量：改写 `getMaxWinNum`；
 * - 解除限制：`isSupportFlexibleWindow` 一律放行；
 * - 启动场景转小窗：在 ActivityStarter 改写通知、控制中心与应用间的启动；
 * - 快速上滑：改写小窗手柄的上滑模式。
 *
 * 小窗交互各项中，除窗外关闭有默认取值外，其余能力默认关闭，由用户按需打开。
 */
@Composable
internal fun ZoomOptionsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchChange: (Boolean) -> Unit,
    onZoomLaunchCardChange: (Boolean) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onNavigateToBlacklist: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    val outsideTapModes = OutsideTapCloseMode.entries
    val outsideTapItems =
        listOf(
            DropdownItem(text = stringResource(R.string.outside_tap_mode_disabled)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_single)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_double)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_caption_double)),
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
        item(key = "blacklist") {
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = stringResource(R.string.blacklist_title),
                    summary = stringResource(R.string.blacklist_summary),
                    onClick = onNavigateToBlacklist,
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Block, enabled) },
                )
            }
        }
        item(key = "open_mode") {
            SettingsGroup(title = stringResource(R.string.settings_group_zoom_open)) {
                SwitchPreference(
                    checked = state.settings.zoomLaunch,
                    onCheckedChange = onZoomLaunchChange,
                    title = stringResource(R.string.zoom_launch_title),
                    summary = stringResource(R.string.zoom_launch_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwapHoriz, enabled) },
                )
                // 小窗跳转的分项：总开关关闭时置灰，取值保留。
                val cardEnabled = enabled && state.settings.zoomLaunch
                SwitchPreference(
                    checked = state.settings.zoomLaunchCard,
                    onCheckedChange = onZoomLaunchCardChange,
                    title = stringResource(R.string.zoom_launch_card_title),
                    summary = stringResource(R.string.zoom_launch_card_summary),
                    enabled = cardEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Dashboard, cardEnabled) },
                )
                SwitchPreference(
                    checked = state.settings.zoomAllowAnyApp,
                    onCheckedChange = onZoomAllowAnyAppChange,
                    title = stringResource(R.string.zoom_allow_any_app_title),
                    summary = stringResource(R.string.zoom_allow_any_app_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Apps, enabled) },
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
            }
        }
        item(key = "window_actions") {
            SettingsGroup(title = stringResource(R.string.settings_group_zoom_actions)) {
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
