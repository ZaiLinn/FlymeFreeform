package io.github.zailinn.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.config.OutsideTapCloseMode
import io.github.zailinn.flymefreeform.config.PinnedTarget
import io.github.zailinn.flymefreeform.framework.FrameworkConnectionState
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object PinnedApps : AppRoute

    @Serializable
    data object Blacklist : AppRoute

    @Serializable
    data object BlacklistPicker : AppRoute

    @Serializable
    data object ZoomOptions : AppRoute

    @Serializable
    data object RadialSettings : AppRoute

    @Serializable
    data object EnvironmentScope : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    sidebarCatalog: io.github.zailinn.flymefreeform.apps.SidebarCatalogState,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchChange: (Boolean) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onPanelNativeListChange: (Boolean) -> Unit,
    onRadialCenterPreviewChange: (Boolean) -> Unit,
    onRadialCenterLabelChange: (Boolean) -> Unit,
    onRadialCenterSizeChange: (Int) -> Unit,
    onRadialMaxItemsChange: (Int) -> Unit,
    onRadialMultiArcEnabledChange: (Boolean) -> Unit,
    onRadialArcCountChange: (Int) -> Unit,
    onRadialFillSourceChange: (Int) -> Unit,
    onIconShapeChange: (Int) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
    onInnerArcTargetsChange: (Int, List<PinnedTarget>) -> Unit,
    onMoveTargetToArc: (PinnedTarget, Int) -> Unit,
    onBlacklistChange: (String, Boolean) -> Unit,
) {
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Settings)
    val popBackStack = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            Unit
        }
    }
    val navigateToPinnedApps = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PinnedApps) {
                backStack.add(AppRoute.PinnedApps)
            }
        }
    }
    val navigateToBlacklist = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.Blacklist) {
                backStack.add(AppRoute.Blacklist)
            }
        }
    }
    val navigateToBlacklistPicker = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.BlacklistPicker) {
                backStack.add(AppRoute.BlacklistPicker)
            }
        }
    }
    val navigateToRadialSettings = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.RadialSettings) {
                backStack.add(AppRoute.RadialSettings)
            }
        }
    }
    val navigateToZoomOptions = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.ZoomOptions) {
                backStack.add(AppRoute.ZoomOptions)
            }
        }
    }

    val zoomOptionsContent: @Composable () -> Unit = {
        ZoomOptionsScreen(
            state = state,
            onBack = popBackStack,
            onZoomMaxWindowCountChange = onZoomMaxWindowCountChange,
            onZoomAllowAnyAppChange = onZoomAllowAnyAppChange,
            onZoomLaunchChange = onZoomLaunchChange,
            onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
            onHandleSwipeUpToMiniEnabledChange = onHandleSwipeUpToMiniEnabledChange,
            onPauseInLandscapeChange = onPauseInLandscapeChange,
            onPauseInGameModeChange = onPauseInGameModeChange,
            onNavigateToBlacklist = navigateToBlacklist,
        )
    }
    val squareIconMask = remember(apps) { squareIconMaskOf(apps) }
    CompositionLocalProvider(
        LocalIconShape provides state.settings.iconShape,
        LocalSquareIconMask provides squareIconMask,
    ) {
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
    ) {
        entry<AppRoute.Settings> {
            ControlScreen(
                state = state,
                onModuleEnabledChange = onModuleEnabledChange,
                onCornerSidesChange = onCornerSidesChange,
                onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                onRadialHitRangeChange = onRadialHitRangeChange,
                onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                onHandleSwipeUpToMiniEnabledChange =
                    onHandleSwipeUpToMiniEnabledChange,
                onPauseInLandscapeChange = onPauseInLandscapeChange,
                onPauseInGameModeChange = onPauseInGameModeChange,
                onVerboseLogChange = onVerboseLogChange,
                onRequestScopes = onRequestScopes,
                onNavigateToPinnedApps = navigateToPinnedApps,
                onNavigateToRadialSettings = navigateToRadialSettings,
                onNavigateToBlacklist = navigateToBlacklist,
                onNavigateToZoomOptions = navigateToZoomOptions,
            )
        }
        entry<AppRoute.RadialSettings> {
            RadialSettingsScreen(
                state = state,
                onBack = popBackStack,
                onCornerSidesChange = onCornerSidesChange,
                onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                onRadialHitRangeChange = onRadialHitRangeChange,
                onRadialMaxItemsChange = onRadialMaxItemsChange,
                onRadialMultiArcEnabledChange = onRadialMultiArcEnabledChange,
                onRadialArcCountChange = onRadialArcCountChange,
                onRadialFillSourceChange = onRadialFillSourceChange,
                onIconShapeChange = onIconShapeChange,
                onRadialKeepOpenChange = onRadialKeepOpenChange,
                onPanelNativeListChange = onPanelNativeListChange,
                onRadialCenterPreviewChange = onRadialCenterPreviewChange,
                onRadialCenterLabelChange = onRadialCenterLabelChange,
                onRadialCenterSizeChange = onRadialCenterSizeChange,
                onNavigateToPinnedApps = navigateToPinnedApps,
            )
        }
        entry<AppRoute.ZoomOptions> { zoomOptionsContent() }
        // 兼容升级前保存的导航状态，旧生效范围路由也显示同一个合并页面。
        entry<AppRoute.EnvironmentScope> { zoomOptionsContent() }
        entry<AppRoute.Blacklist> {
            BlacklistScreen(
                state = state,
                apps = apps,
                onBack = popBackStack,
                onAddApps = navigateToBlacklistPicker,
                onBlacklistChange = onBlacklistChange,
            )
        }
        entry<AppRoute.BlacklistPicker> {
            BlacklistPickerScreen(
                state = state,
                apps = apps,
                onBack = popBackStack,
                onBlacklistChange = onBlacklistChange,
            )
        }
        entry<AppRoute.PinnedApps> {
            PinnedAppsScreen(
                state = state,
                apps = apps,
                sidebarCatalog = sidebarCatalog,
                onRefreshCatalog = onRefreshCatalog,
                onCancelCatalog = onCancelCatalog,
                onBack = popBackStack,
                onPinnedTargetsChange = onPinnedTargetsChange,
                onInnerArcTargetsChange = onInnerArcTargetsChange,
                onMoveTargetToArc = onMoveTargetToArc,
            )
        }
    }
    }
}
