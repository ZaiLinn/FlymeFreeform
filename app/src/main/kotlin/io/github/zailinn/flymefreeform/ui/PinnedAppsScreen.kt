package io.github.zailinn.flymefreeform.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.zailinn.flymefreeform.R
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.apps.SidebarCatalogState
import io.github.zailinn.flymefreeform.apps.SidebarCatalogStatus
import io.github.zailinn.flymefreeform.gesture.RadialArcPlan
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.config.PinnedTarget
import io.github.zailinn.flymefreeform.config.PinnedTargetCodec
import io.github.zailinn.flymefreeform.framework.FrameworkConnectionState
import io.github.zailinn.flymefreeform.ui.component.TopBarBackdrop
import io.github.zailinn.flymefreeform.ui.component.captureForTopBar
import io.github.zailinn.flymefreeform.ui.component.rememberTopBarBackdrop
import io.github.zailinn.flymefreeform.ui.component.topBarContainerColor
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun PinnedAppsScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    sidebarCatalog: SidebarCatalogState,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onBack: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
    onInnerArcTargetsChange: (Int, List<PinnedTarget>) -> Unit,
    onMoveTargetToArc: (PinnedTarget, Int) -> Unit,
) {
    val refreshCatalog by rememberUpdatedState(onRefreshCatalog)
    val cancelCatalog by rememberUpdatedState(onCancelCatalog)
    LifecycleResumeEffect(state.canChangeSettings) {
        if (state.canChangeSettings) refreshCatalog()
        onPauseOrDispose { cancelCatalog() }
    }
    // 多圆弧时每圈单独编辑：第 1 圈即默认圆弧，内侧各圈保存在独立的键里。
    // 减少圆弧条数只是不再显示多出的页签，已保存的固定项不动，再次增加时原样恢复。
    val arcCount = state.settings.effectiveRadialArcCount
    var chosenArc by rememberSaveable { mutableIntStateOf(0) }
    // 圆弧条数减少后，原先选中的页签可能已不存在，此时回到第 1 圈。
    val pagerState = rememberPagerState(initialPage = chosenArc.coerceIn(0, arcCount - 1)) { arcCount }
    val selectedArc = pagerState.currentPage.coerceIn(0, arcCount - 1)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { chosenArc = it }
    }
    val scope = rememberCoroutineScope()
    val arcTargets: List<List<PinnedTarget>> =
        listOf(state.settings.pinnedTargets) + state.settings.innerArcTargets.take(arcCount - 1)
    fun pinCapacityOf(arc: Int) = RadialArcPlan.pinCapacity(arc)
    // 按当前「数量上限」该圈实际能显示几项；固定项超出部分照常保存。
    fun visibleCapacityOf(arc: Int) =
        if (arc == 0) {
            state.settings.radialMaxItems
        } else {
            RadialArcPlan.innerCapacities(arcCount, state.settings.radialMaxItems).getOrElse(arc - 1) { 0 }
        }
    val pinCapacity = pinCapacityOf(selectedArc)
    val fillSource = ModulePreferences.coerceRadialFillSource(state.settings.radialFillSource)
    fun changeTargets(arc: Int, targets: List<PinnedTarget>) {
        if (arc == 0) onPinnedTargetsChange(targets) else onInnerArcTargetsChange(arc - 1, targets)
    }
    /**
     * 已固定在其他圈的条目 → 所在圈下标（0 为默认圆弧）。含当前未启用的圈：
     * 圆弧条数减少后那几圈的固定项仍保存着，移过来时一并移除，避免条数恢复后同一条目出现在两圈。
     */
    val allArcTargets: List<List<PinnedTarget>> = listOf(state.settings.pinnedTargets) + state.settings.innerArcTargets
    val otherArcOf: Map<PinnedTarget, Int> =
        buildMap {
            allArcTargets.forEachIndexed { arc, targets ->
                if (arc != selectedArc) targets.forEach { putIfAbsent(it, arc) }
            }
        }
    val allEntries = remember(apps, sidebarCatalog.entries) { apps + sidebarCatalog.entries }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    // 拖动或增删后的本地顺序（按圈）；远端配置回读同步前以它为准，避免列表闪回旧顺序
    val localOrders = remember { mutableStateMapOf<Int, List<PinnedTarget>>() }
    LaunchedEffect(state.canChangeSettings, state.issue) {
        if (!state.canChangeSettings || state.issue != null) localOrders.clear()
    }
    LaunchedEffect(arcTargets) {
        localOrders.keys.filter { arc -> localOrders[arc] == arcTargets.getOrNull(arc) }.forEach(localOrders::remove)
    }
    fun displayedOf(arc: Int): List<PinnedTarget> = localOrders[arc] ?: arcTargets.getOrElse(arc) { emptyList() }
    fun commitTargets(arc: Int, targets: List<PinnedTarget>) {
        localOrders[arc] = targets
        changeTargets(arc, targets)
    }
    var draggingComponent by remember { mutableStateOf<PinnedTarget?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    // 拖动排序的触感：长按拿起、每换一格、松手各一次，与扇形跨圈震动同属 View 触感常量。
    val view = LocalView.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= WideWindowMinWidth
        val title = stringResource(R.string.radial_apps_screen_title)
        val navigationIcon: @Composable () -> Unit = { BackButton(onBack) }
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (isWide) {
                        SmallTopAppBar(
                            title = title,
                            subtitle = stringResource(R.string.radial_apps_screen_subtitle, pinCapacity),
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = title,
                            subtitle = stringResource(R.string.radial_apps_screen_subtitle, pinCapacity),
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { innerPadding ->
            val direction = LocalLayoutDirection.current
            val safeStart = innerPadding.calculateStartPadding(direction)
            val safeEnd = innerPadding.calculateEndPadding(direction)
            val safeWidth = (maxWidth - safeStart - safeEnd).coerceAtLeast(0.dp)
            val side = maxOf(ScreenHorizontalMargin, (safeWidth - ScreenContentMaxWidth) / 2)
            val appByComponent = remember(allEntries) { allEntries.associateBy(SelectableEntry::target) }
            val horizontalStart = safeStart + side
            val horizontalEnd = safeEnd + side
            val arcPage: @Composable (Int) -> Unit = { arc ->
                val pinned = arcTargets.getOrElse(arc) { emptyList() }
                val displayed = displayedOf(arc)
                val arcPinCapacity = pinCapacityOf(arc)
                val visibleCapacity = visibleCapacityOf(arc)
                // 正在播放移除动画的条目：动画结束后才真正写入配置，其余行随之自然上移。
                val removing = remember { mutableStateMapOf<PinnedTarget, Boolean>() }
                // 已出现过的条目不再播放进场动画；之后新增（含移除后重新添加）的才展开进场。
                val shown = remember { HashSet(displayed) }
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding =
                        PaddingValues(
                            start = horizontalStart,
                            top = if (arcCount > 1) 12.dp else innerPadding.calculateTopPadding() + ScreenTopSpacing,
                            end = horizontalEnd,
                            bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                        ),
                    overscrollEffect = null,
                ) {
                    item(key = "radial_options") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            BasicComponent(
                                title = stringResource(R.string.select_apps_action),
                                startAction = { PreferenceIcon(Icons.Rounded.Add, state.settingsEditable) },
                                onClick = {
                                    pickerQuery = ""
                                    pickerOpen = true
                                },
                                onClickLabel = stringResource(R.string.select_apps_action),
                                role = Role.Button,
                                enabled = state.settingsEditable,
                            )
                        }
                    }
                    if (arc > 0 || displayed.size > visibleCapacity) {
                        item(key = "arc_intro") {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                                if (arc > 0) {
                                    Text(
                                        text =
                                            stringResource(
                                                when {
                                                    displayed.isNotEmpty() -> R.string.inner_arc_custom_intro
                                                    fillSource == ModulePreferences.RADIAL_FILL_FREQUENT ->
                                                        R.string.inner_arc_auto_intro_frequent
                                                    fillSource == ModulePreferences.RADIAL_FILL_NONE ->
                                                        R.string.inner_arc_auto_intro_none
                                                    else -> R.string.inner_arc_auto_intro
                                                },
                                            ),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.body2,
                                    )
                                }
                                if (displayed.size > visibleCapacity) {
                                    Text(
                                        text = stringResource(R.string.arc_capacity_limited, visibleCapacity),
                                        modifier = Modifier.padding(top = 4.dp),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.body2,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "added_intro") {
                        Text(
                            text =
                                stringResource(
                                    R.string.added_apps_title,
                                    displayed.size,
                                    arcPinCapacity,
                                ),
                            modifier =
                                Modifier
                                    .padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                                    .semantics { heading() },
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                        )
                    }
                    item(key = "added") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            displayed.forEachIndexed { index, component ->
                                key(PinnedTargetCodec.encode(component)) {
                                    val visibility =
                                        remember { MutableTransitionState(component in shown) }
                                    visibility.targetState = removing[component] != true
                                    SideEffect { shown += component }
                                    // 收起动画播完再写入配置：列表高度随行一起收拢，下方各行平滑上移。
                                    if (removing[component] == true && visibility.isIdle && !visibility.currentState) {
                                        LaunchedEffect(component) {
                                            removing.remove(component)
                                            shown -= component
                                            commitTargets(arc, displayedOf(arc).filterNot { it == component })
                                        }
                                    }
                                    AnimatedVisibility(
                                        visibleState = visibility,
                                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                                    ) {
                                        AddedAppRow(
                                            component = component,
                                            app = appByComponent[component],
                                            index = index,
                                            enabled = state.settingsEditable && removing[component] != true,
                                            draggable =
                                                state.settingsEditable && displayed.size > 1 && removing.isEmpty(),
                                            dragging = draggingComponent == component,
                                            reordering = draggingComponent != null,
                                            dragOffsetY = dragOffsetY,
                                            rowHeightPx = rowHeightPx,
                                            onRowHeight = { height ->
                                                if (rowHeightPx != height) rowHeightPx = height
                                            },
                                            onRemove = { removing[component] = true },
                                            onDragStart = {
                                                localOrders[arc] = displayed
                                                draggingComponent = component
                                                dragOffsetY = 0f
                                                view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
                                            },
                                            onDrag = { deltaY ->
                                                if (rowHeightPx > 0f) {
                                                    dragOffsetY += deltaY
                                                    val order = displayedOf(arc).toMutableList()
                                                    val start = order.indexOf(component)
                                                    var current = start
                                                    while (current >= 0 &&
                                                        dragOffsetY > rowHeightPx / 2 &&
                                                        current < order.size - 1
                                                    ) {
                                                        order.add(current + 1, order.removeAt(current))
                                                        dragOffsetY -= rowHeightPx
                                                        current++
                                                    }
                                                    while (current >= 0 &&
                                                        dragOffsetY < -rowHeightPx / 2 &&
                                                        current > 0
                                                    ) {
                                                        order.add(current - 1, order.removeAt(current))
                                                        dragOffsetY += rowHeightPx
                                                        current--
                                                    }
                                                    if (current != start) {
                                                        localOrders[arc] = order
                                                        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                val order = localOrders[arc]
                                                if (draggingComponent != null) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                                                }
                                                draggingComponent = null
                                                dragOffsetY = 0f
                                                if (order != null) {
                                                    if (order == pinned) {
                                                        localOrders.remove(arc)
                                                    } else {
                                                        changeTargets(arc, order)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = displayed.isEmpty(),
                                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                            ) {
                                BasicComponent(
                                    title =
                                        stringResource(
                                            when {
                                                arc == 0 -> R.string.added_apps_empty
                                                fillSource == ModulePreferences.RADIAL_FILL_FREQUENT ->
                                                    R.string.inner_arc_empty_frequent
                                                fillSource == ModulePreferences.RADIAL_FILL_NONE -> R.string.inner_arc_empty_none
                                                else -> R.string.inner_arc_empty
                                            },
                                        ),
                                    summary =
                                        stringResource(
                                            if (arc > 0) {
                                                R.string.inner_arc_empty_summary
                                            } else {
                                                R.string.added_apps_empty_summary
                                            },
                                        ),
                                    enabled = false,
                                )
                            }
                        }
                    }
                }
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .captureForTopBar(backdrop)
                        .consumeWindowInsets(innerPadding),
            ) {
                if (arcCount > 1) {
                    TabRowWithContour(
                        tabs = List(arcCount) { stringResource(R.string.radial_arc_tab, it + 1) },
                        selectedTabIndex = selectedArc,
                        onTabSelected = { arc -> scope.launch { pagerState.animateScrollToPage(arc) } },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = horizontalStart,
                                    top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                                    end = horizontalEnd,
                                ),
                    )
                }
                // 左右滑动切换圈；拖动排序期间锁定，避免竖向拖动被横向翻页抢走。
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = draggingComponent == null,
                    beyondViewportPageCount = 1,
                    key = { it },
                ) { arc ->
                    arcPage(arc)
                }
            }
            val displayed = displayedOf(selectedArc)
            // 勾选状态跟随 displayed，sheet 内增删即时可见
            val addedSet = remember(displayed) { displayed.toSet() }
            val candidates =
                remember(allEntries, pickerQuery) {
                    if (pickerQuery.isBlank()) {
                        allEntries
                    } else {
                        allEntries.filter {
                            it.label.contains(pickerQuery, ignoreCase = true) ||
                                it.packageName.contains(pickerQuery, ignoreCase = true)
                        }
                    }
                }
            val groups = remember(candidates) {
                listOf(
                    R.string.entry_group_tools to candidates.filter { (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.Tool },
                    R.string.entry_group_shortcuts to candidates.filter {
                        val kind = (it.target as? PinnedTarget.Sidebar)?.kind
                        kind == PinnedTarget.Kind.Shortcut || kind == PinnedTarget.Kind.AppEntry
                    },
                    R.string.entry_group_cloned_apps to candidates.filter {
                        (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp
                    },
                    R.string.entry_group_apps to candidates.filter { it.target is PinnedTarget.App },
                ).filter { it.second.isNotEmpty() }
            }
            OverlayBottomSheet(
                show = pickerOpen,
                title = stringResource(R.string.select_apps_action),
                onDismissRequest = { pickerOpen = false },
            ) {
                Column {
                    TextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.search_apps_placeholder),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = MiuixIcons.Basic.Search,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                            )
                        },
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        if (sidebarCatalog.status != SidebarCatalogStatus.Ready) {
                            item(key = "sidebar_status") {
                                BasicComponent(
                                    title = stringResource(if (sidebarCatalog.status == SidebarCatalogStatus.Loading) R.string.sidebar_catalog_loading else R.string.sidebar_catalog_unavailable),
                                    summary = stringResource(if (sidebarCatalog.status == SidebarCatalogStatus.Loading) R.string.sidebar_catalog_loading_summary else R.string.sidebar_catalog_retry_summary),
                                    onClick = onRefreshCatalog,
                                    onClickLabel = stringResource(R.string.retry_action),
                                    role = Role.Button,
                                    enabled = state.settingsEditable && sidebarCatalog.status != SidebarCatalogStatus.Loading,
                                )
                            }
                        }
                        when {
                            allEntries.isEmpty() ->
                                item(key = "apps_loading") {
                                    BasicComponent(
                                        title = stringResource(R.string.available_apps_loading),
                                        summary = stringResource(R.string.available_apps_loading_summary),
                                        enabled = false,
                                    )
                                }
                            candidates.isEmpty() ->
                                item(key = "apps_no_match") {
                                    BasicComponent(
                                        title = stringResource(R.string.no_matching_apps),
                                        enabled = false,
                                    )
                                }
                            else -> groups.forEach { (title, entries) ->
                                item(key = "group_$title") {
                                    Text(
                                        text = stringResource(title),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                                        style = MiuixTheme.textStyles.subtitle,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                items(entries, key = { PinnedTargetCodec.encode(it.target) }) { app ->
                                    val added = app.target in addedSet
                                    val sourceArc = if (added) null else otherArcOf[app.target]
                                    val full = displayed.size >= pinCapacity
                                    val summary =
                                        when {
                                            sourceArc == null -> app.packageName
                                            full -> stringResource(R.string.pinned_in_other_arc_full, sourceArc + 1)
                                            sourceArc >= arcCount ->
                                                stringResource(R.string.pinned_in_hidden_arc_move, sourceArc + 1)
                                            allArcTargets[sourceArc].size == 1 ->
                                                stringResource(R.string.pinned_move_empties_arc, sourceArc + 1)
                                            else -> stringResource(R.string.pinned_in_other_arc_move, sourceArc + 1)
                                        }
                                    BasicComponent(
                                        title = app.label,
                                        summary = summary,
                                        startAction = { AppIcon(app) },
                                        endActions =
                                            if (added) {
                                                {
                                                    Icon(
                                                        imageVector = MiuixIcons.Basic.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MiuixTheme.colorScheme.primary,
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        onClick = {
                                            when {
                                                added -> commitTargets(selectedArc, displayed.filterNot { it == app.target })
                                                sourceArc != null -> {
                                                    // 两圈的本地顺序同时更新，配置回读前两页都显示移动后的结果。
                                                    localOrders[selectedArc] = displayed + app.target
                                                    if (sourceArc < arcCount) {
                                                        localOrders[sourceArc] = displayedOf(sourceArc).filterNot { it == app.target }
                                                    }
                                                    onMoveTargetToArc(app.target, selectedArc)
                                                }
                                                else -> commitTargets(selectedArc, (displayed + app.target).take(pinCapacity))
                                            }
                                        },
                                        onClickLabel =
                                            stringResource(
                                                when {
                                                    added -> R.string.remove_named_app_action
                                                    sourceArc != null -> R.string.move_named_app_action
                                                    else -> R.string.add_named_app_action
                                                },
                                                app.label,
                                            ),
                                        role = Role.Button,
                                        enabled =
                                            state.settingsEditable &&
                                                (added || !full) &&
                                                (added || app.target is PinnedTarget.App || sidebarCatalog.status == SidebarCatalogStatus.Ready),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddedAppRow(
    component: PinnedTarget,
    app: SelectableEntry?,
    index: Int,
    enabled: Boolean,
    draggable: Boolean,
    dragging: Boolean,
    reordering: Boolean,
    dragOffsetY: Float,
    rowHeightPx: Float,
    onRowHeight: (Float) -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // 被拖行换位时，其余行先停在旧视觉位置再弹簧归位，形成实时让位动画。
    // 只在拖动排序期间这样做：增删引起的下标变化由行本身的展开 / 收起动画带动，再叠加位移会抖一下。
    // 补偿位移必须与新下标在同一帧生效：SideEffect 在本帧布局与绘制之前执行，
    // 若放进协程再 snapTo，会先按新位置画一帧再跳回旧位置，表现为图标闪动。
    // 松手时被拖行同理：从手指下的位置接着归位，而不是先跳回槽位。
    var shift by remember { mutableFloatStateOf(0f) }
    var restIndex by remember { mutableIntStateOf(index) }
    val lastDrag = remember { floatArrayOf(0f) }
    var wasDragging by remember { mutableStateOf(dragging) }
    SideEffect {
        if (dragging) lastDrag[0] = dragOffsetY
        if (wasDragging && !dragging) shift = lastDrag[0]
        wasDragging = dragging
        if (index != restIndex) {
            if (!dragging && reordering && rowHeightPx > 0f) shift += (restIndex - index) * rowHeightPx
            restIndex = index
        }
    }
    LaunchedEffect(index, dragging) {
        if (dragging) {
            shift = 0f
        } else if (shift != 0f) {
            animate(shift, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> shift = value }
        }
    }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val dragModifier =
        if (draggable) {
            Modifier.pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDragCancel = { currentOnDragEnd() },
                    onDragEnd = { currentOnDragEnd() },
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDrag(amount.y)
                    },
                )
            }
        } else {
            Modifier
        }
    BasicComponent(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> onRowHeight(size.height.toFloat()) }
                .graphicsLayer { translationY = if (dragging) dragOffsetY else shift }
                .zIndex(if (dragging) 1f else 0f)
                .then(dragModifier),
        title = app?.label ?: component.displayIdentity(),
        summary = if (app == null) stringResource(R.string.pinned_app_missing) else null,
        startAction = {
            if (app != null) {
                AppIcon(app)
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "?",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body1,
                    )
                }
            }
        },
        endActions = {
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                backgroundColor = MiuixTheme.colorScheme.error,
                cornerRadius = 13.dp,
                minWidth = 26.dp,
                minHeight = 26.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription =
                        stringResource(
                            R.string.remove_named_app_action,
                            app?.label ?: component.displayIdentity(),
                        ),
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onError,
                )
            }
        },
        enabled = enabled,
    )
}

@Composable
private fun AppIcon(app: SelectableEntry) {
    Box(modifier = Modifier.size(44.dp)) {
        Image(
            bitmap = app.icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.matchParentSize().squircleClip(12.dp),
        )
        app.badge?.let { badge ->
            Image(
                bitmap = badge.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back_action),
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun PinnedTarget.displayIdentity(): String = when (this) {
    is PinnedTarget.App -> packageName
    is PinnedTarget.Sidebar -> alias
}