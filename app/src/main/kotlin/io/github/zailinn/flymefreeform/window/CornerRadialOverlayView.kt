package io.github.zailinn.flymefreeform.window

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.zailinn.flymefreeform.apps.IconShapes
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.gesture.CornerSide
import io.github.zailinn.flymefreeform.gesture.RadialArcPlan
import io.github.zailinn.flymefreeform.gesture.RadialGeometry
import io.github.zailinn.flymefreeform.gesture.RadialIconScale
import io.github.zailinn.flymefreeform.gesture.RadialLayout
import io.github.zailinn.flymefreeform.platform.coloros.AppCatalogSnapshot
import io.github.zailinn.flymefreeform.platform.coloros.PanelIconPlaceholder
import io.github.zailinn.flymefreeform.platform.coloros.RadialEntry
import io.github.zailinn.flymefreeform.ui.theme.CornerOverlayTheme
import io.github.zailinn.flymefreeform.ui.theme.LocalRadialItemColors
import io.github.zailinn.flymefreeform.ui.theme.RadialItemColors
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("ViewConstructor")
/**
 * 扇形浮层的 Compose 容器（运行在 system_server）。
 *
 * 职责：绘制扇形/更多面板、把触摸坐标换算成选中格（[RadialGeometry]）、
 * 管理入场/退场动画与提交时机。
 *
 * 提交时机（[dismissAnimated]）：松手当帧即回调 [Listener.onEntryCommitted]，
 * 不等 200ms 退场动画；浮层随后自行淡出。取屏类工具由宿主 [removeOverlay][io.github.zailinn.flymefreeform.platform.coloros.ColorOsFreeformCoordinator]
 * 立即移除并通过两帧等待保证屏幕干净，因此不需要在此区分目标类型。
 *
 * 格数 = 应用数 + 1，最后一格固定是「更多」面板入口，不参与应用列表索引。
 * 多圆弧时应用依次分布在默认圆弧与内侧圆弧上（见 [RadialArcPlan]），下标顺序不变。
 */
internal class CornerRadialOverlayView(
    context: Context,
    private val listener: Listener,
    private val debug: (String) -> Unit = {},
) : AbstractComposeView(context), LifecycleOwner, SavedStateRegistryOwner {
    interface Listener {
        fun onEntryCommitted(entry: RadialEntry)

        fun onMorePanelRequested()

        fun onDismissRequested()

        fun onCleanupFailed(throwable: Throwable)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val metricsState = mutableStateOf<AdaptiveOverlayMetrics?>(null)
    private val layoutState = mutableStateOf(EMPTY_LAYOUT)
    private val selectedIndexState = mutableIntStateOf(NO_SELECTION)
    private val panelModeState = mutableStateOf(false)
    private val backdropOnlyState = mutableStateOf(false)
    private val backdropAlpha = mutableFloatStateOf(1f)
    private var backdropAnimator: ValueAnimator? = null
    private val exitRequestState = mutableStateOf<ExitRequest?>(null)
    private val entryProgress = Animatable(0f)
    private val handoffEntryProgress = mutableFloatStateOf(0f)
    private val handoffLayoutState = mutableStateOf(EMPTY_LAYOUT)
    private val handoffMetricsState = mutableStateOf<RadialVisualMetrics?>(null)
    private var appliedWindowInsets: WindowInsets? = null
    /** 当前目录放在 State 里：同一批条目换上新目录（本地副本换成真实目录、面板列表补齐）时，读它的界面随之重组。 */
    private val catalogState = mutableStateOf(AppCatalogSnapshot(), referentialEqualityPolicy())
    private var catalog: AppCatalogSnapshot
        get() = catalogState.value
        set(value) {
            catalogState.value = value
        }
    private val radialImages = mutableStateOf<List<RadialImage>>(emptyList())
    /** 方形图标时「更多」底板的外形模板（取自扇形里的应用图标）；圆形或取不到时为 null。 */
    private val morePlateMask = mutableStateOf<ImageBitmap?>(null)
    /**
     * 面板图标：自带图标的条目（侧边栏工具等）开场时放入；应用条目由格子出现在视野里时按需请求
     * （见 AppCatalogSnapshot.panelIconLoader），格子离开组合即移除，只持有可见范围及缓冲行，其余交给目录的有上限缓存。
     */
    private var side = CornerSide.Right
    private var radialHitRangeDp = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP
    /** 「永久保持」：松手未选中时保留浮层，并允许直接点选条目。 */
    private var keepOpen = false
    /** 保持挂起：松手时没选中，扇形停在屏幕上等待点选。 */
    private var restingOpen = false

    /** 扇形正停在屏幕上等待点选（保持挂起、手指已抬起、未提交、未打开面板）。宿主据此在回桌面或切应用时收起。 */
    val isRestingOpen: Boolean
        get() = restingOpen && keepOpen && !dismissing && !disposed && !panelModeState.value
    /** 屏幕中央所选应用卡片与其应用名的开关，随每次呼出从最新设置传入。 */
    private var centerPreview = false
    private var centerLabel = true
    private var centerSizeDp = ModulePreferences.DEFAULT_RADIAL_CENTER_SIZE_DP
    /**
     * 「更多」按钮颜色取自呼出时的最新设置，与中央预览各项一样由协调器直接传入；
     * 不读 catalog.settings，那份只在目录重新挑选条目时才更新，改色后会停在旧值。
     */
    private var moreColor = ModulePreferences.DEFAULT_RADIAL_MORE_COLOR
    private var latestX = 0f
    private var latestY = 0f
    private var selectedIndex: Int? = null
    private var dismissing = false
    private var dismissNotified = false
    /** 松手当帧已把目标交给宿主（应用/快捷方式），退场动画结束后只做收尾。 */
    private var commitNotified = false
    private var disposed = false
    private var lastTickUptime = 0L
    /** 每格所在的圈，随布局更新；换圈时用更明显的震动。 */
    private var arcOfIndex = IntArray(0)
    /** 扇形窗口的安全区，屏幕中央卡片以其中心为准。 */
    private var centerInsets = OverlaySafeInsets()
    private val previewLabelPaint by lazy {
        android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            color = android.graphics.Color.WHITE
        }
    }
    /** 中央卡片应用名的排版结果，按选中项、图标大小与画布尺寸缓存，避免每帧截断与测量。 */
    private class CenterLabelLayout(
        val key: List<Any?>,
        val text: String?,
        val width: Float?,
        val ascent: Float,
        val descent: Float,
    )
    private var centerLabelLayout: CenterLabelLayout? = null

    /** 中央卡片的高清图标（按所选条目请求，见 [PreviewIconLoader]）与其本体放大倍数。 */
    private val previewImages = mutableStateMapOf<io.github.zailinn.flymefreeform.config.PinnedTarget, Pair<ImageBitmap, Float>>()
    private var previewGeneration = 0L
    private val previewRequests = HashMap<io.github.zailinn.flymefreeform.config.PinnedTarget, () -> Unit>()

    /** 为第 [index] 项请求中央卡片的高清图标；已有或已在请求中则跳过。 */
    private fun requestPreviewImage(index: Int) {
        val target = catalog.radialApps.getOrNull(index)?.target ?: return
        if (target in previewImages || target in previewRequests) return
        val loader = catalog.previewIconLoader ?: return
        val generation = previewGeneration
        val sizePx = PreviewIconSizing.renderSizeFor(centerSizeDp, resources.displayMetrics.density)
        var appliedInline = false
        val cancel =
            loader.request(target, sizePx) { bitmap ->
                // 留白比例在图标线程算好（逐像素扫描），主线程只接收结果；命中缓存的位图查表即得。
                val fill = RadialIconScale.bodyFill(iconBodyFraction(bitmap))
                val image = bitmap.asImageBitmap()
                val apply = {
                    if (!disposed && generation == previewGeneration) {
                        previewRequests.remove(target)
                        previewImages.remove(target)
                        // 位图与目录缓存共用同一份，不另占内存；条目数以本次扇形为上限。
                        previewImages[target] = image to fill
                    }
                }
                // 命中缓存时回调在主线程同步返回：当场写入，首帧就用高清图，不先闪一帧扇形小图。
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    appliedInline = true
                    apply()
                } else {
                    post { apply() }
                }
            }
        if (!appliedInline) previewRequests[target] = cancel
    }
    private var lastSelectedArc = -1
    private val timeout = Runnable(::requestDismiss)
    private val dismissFallback = Runnable(::completeRadialExit)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun updateRadialAppearance(next: AppCatalogSnapshot) {
        if (disposed || dismissing) return
        if (panelModeState.value) {
            updatePanelCatalog(next)
            return
        }
        val accepted = RadialCatalogUpdate.accept(catalog, next) { snapshot -> snapshot.radialApps.map { it.target } } ?: return
        if (catalog.radialApps.isEmpty() && accepted.radialApps.isNotEmpty()) {
            // 呼出时目录还没就绪（只有「更多」一格）：目录一发布就整体补进这次的扇形，不必松手再呼出。
            replaceEmptyCatalog(accepted)
            return
        }
        // 条目不同的目录留到下次呼出：不在滑选途中交换目标或重置入场动画。
        adoptSameTargets(accepted)
    }

    /**
     * 同一批条目：整份换上新目录（图像、高清与面板加载器、面板列表、分圈），保留选中项与手势状态。
     * 本地副本没有加载器、面板为空，真实目录到达后中央高清图与内置「更多」随之可用，不必重新呼出。
     */
    private fun adoptSameTargets(next: AppCatalogSnapshot) {
        val previous = catalog
        catalog = next
        radialImages.value = next.radialApps.map(::RadialImage)
        morePlateMask.value = MorePlateMasks.of(next)
        if (next.previewIconLoader !== previous.previewIconLoader) {
            // 旧加载器在途的请求作废，改向新加载器请求（目录按条目合并在途任务，不会重画）。
            // 已显示的高清图只在条目图标换了（应用更新）时丢弃，其余保留，不闪回小图。
            previewGeneration++
            previewRequests.values.forEach { it() }
            previewRequests.clear()
            previous.radialApps.forEachIndexed { index, old ->
                if (next.radialApps[index].icon !== old.icon) previewImages.remove(old.target)
            }
            if (centerPreview) next.radialApps.indices.forEach(::requestPreviewImage)
        }
        if (previous.radialArcCounts != next.radialArcCounts || previous.panelApps.size != next.panelApps.size ||
            previous.settings.effectiveRadialArcCount != next.settings.effectiveRadialArcCount ||
            previous.settings.radialMaxItems != next.settings.radialMaxItems
        ) {
            // 分圈或面板条数变了才重算布局；updateLayout 会按手指当前位置重新判定选中项。
            updateLayout()
        }
    }

    /**
     * 内置面板已显示：只在面板还空着（本地副本）或条目顺序相同（只换了加载器）时换上新列表，
     * 不在用户浏览途中重排格子。
     */
    private fun updatePanelCatalog(next: AppCatalogSnapshot) {
        val current = catalog
        val sameOrder = current.panelApps.map { it.target } == next.panelApps.map { it.target }
        if (current.panelApps.isNotEmpty() && !sameOrder) return
        if (sameOrder && current.panelIconLoader === next.panelIconLoader) return
        catalog = current.copy(panelApps = next.panelApps, panelIconLoader = next.panelIconLoader)
        if (!sameOrder) updateLayout()
    }

    /** 只在当前扇形为空时调用：换上新目录、重算布局，并按手指当前位置重新判定选中项。 */
    private fun replaceEmptyCatalog(next: AppCatalogSnapshot) {
        catalog = next
        previewGeneration++
        previewRequests.values.forEach { it() }
        previewRequests.clear()
        previewImages.clear()
        if (centerPreview) next.radialApps.indices.forEach(::requestPreviewImage)
        radialImages.value = next.radialApps.map(::RadialImage)
        morePlateMask.value = MorePlateMasks.of(next)
        selectedIndex = null
        selectedIndexState.intValue = NO_SELECTION
        lastSelectedArc = -1
        updateLayout()
        updateGestureFromLatestPoint()
    }

    fun begin(
        side: CornerSide,
        catalog: AppCatalogSnapshot,
        x: Float,
        y: Float,
        hitRangeDp: Int = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
        keepOpen: Boolean = false,
        centerPreview: Boolean = false,
        centerLabel: Boolean = true,
        centerSizeDp: Int = ModulePreferences.DEFAULT_RADIAL_CENTER_SIZE_DP,
        moreColor: Int = ModulePreferences.DEFAULT_RADIAL_MORE_COLOR,
    ) {
        check(!isAttachedToWindow) { "Overlay must be initialized before it is attached" }
        this.moreColor = ModulePreferences.coerceRadialMoreColor(moreColor)
        this.keepOpen = keepOpen
        restingOpen = false
        this.centerPreview = centerPreview
        this.centerLabel = centerLabel
        this.centerSizeDp = ModulePreferences.coerceRadialCenterSizeDp(centerSizeDp)
        this.side = side
        // 保持挂起不改变命中判定：滑动选格与点选统一使用「命中范围」的设定值。
        this.radialHitRangeDp = ModulePreferences.coerceRadialHitRangeDp(hitRangeDp)
        this.catalog = catalog
        previewGeneration++
        previewRequests.values.forEach { it() }
        previewRequests.clear()
        previewImages.clear()
        // 目录已在后台预取全部扇形项的高清图，这里多半同步命中，首帧即用高清图。
        if (centerPreview) catalog.radialApps.indices.forEach(::requestPreviewImage)
        latestX = x
        latestY = y
        radialImages.value = catalog.radialApps.map(::RadialImage)
        morePlateMask.value = MorePlateMasks.of(catalog)
        selectedIndex = null
        selectedIndexState.intValue = NO_SELECTION
        lastSelectedArc = -1
        handoffEntryProgress.floatValue = 0f
        handoffLayoutState.value = EMPTY_LAYOUT
        handoffMetricsState.value = null
        panelModeState.value = false
        exitRequestState.value = null
        dismissing = false
        dismissNotified = false
        commitNotified = false
        val displayMetrics = resources.displayMetrics
        updateLayout(
            requestedWidth = displayMetrics.widthPixels,
            requestedHeight = displayMetrics.heightPixels,
            safeInsets = OverlaySafeInsets(),
        )
        removeCallbacks(timeout)
        if (!keepOpen) postDelayed(timeout, GESTURE_TIMEOUT_MS)
    }

    fun updateGesture(x: Float, y: Float): Int? {
        latestX = x
        latestY = y
        if (panelModeState.value || dismissing) return selectedIndex
        removeCallbacks(timeout)
        if (!keepOpen) postDelayed(timeout, GESTURE_TIMEOUT_MS)
        updateGestureFromLatestPoint()
        return selectedIndex
    }

    fun finishGesture() {
        val selection = selectedIndex
        val selectionLabel =
            when {
                selection == null -> "NONE"
                selection < catalog.radialApps.size ->
                    catalog.radialApps.getOrNull(selection)?.label ?: "APP_" + selection
                else -> "MORE_PANEL"
            }
        debug(
            "SYSTEM_HIT_RELEASE_" + (selection ?: -1) + "_" + selectionLabel +
                "_HITRADIUS_" + (radialHitRangeDp * resources.displayMetrics.density).toInt() + "PX",
        )
        when {
            // 永久保持：未选中任何条目时不收起，等待下一次点选。
            selection == null && keepOpen -> {
                removeCallbacks(timeout)
                restingOpen = true
            }
            selection == null -> dismissAnimated()
            selection < catalog.radialApps.size -> dismissAnimated(catalog.radialApps[selection])
            else -> showMorePanel()
        }
    }

    fun cancelGesture() {
        dismissAnimated()
    }

    fun dismissAnimated() = dismissAnimated(pendingCommit = null)

    fun disposeOverlay(): Throwable? {
        if (disposed) return null
        disposed = true
        previewRequests.values.forEach { it() }
        previewRequests.clear()
        // 收起即放开高清图引用：补位项的图由目录缓存按上限保留，不随浮层对象等回收才释放。
        previewImages.clear()
        backdropAnimator?.cancel()
        backdropAnimator = null
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        var failure: Throwable? = null
        try {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            disposeComposition()
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        return failure
    }

    @Composable
    override fun Content() {
        CornerOverlayTheme {
            val metrics = metricsState.value
            val layout = layoutState.value
            if (metrics != null) {
                OverlayContent(metrics = metrics, layout = layout)
            }
        }
    }

    @Composable
    private fun OverlayContent(
        metrics: AdaptiveOverlayMetrics,
        layout: RadialLayout,
    ) {
        val panelProgress = remember { Animatable(0f) }
        val panelContentProgress = remember { Animatable(0f) }
        val radialHandoffProgress = remember { Animatable(0f) }
        val exitProgress = remember { Animatable(0f) }
        val panelInputEnabled = remember { mutableStateOf(false) }
        val itemRings =
            remember(layout.itemCenters.size) {
                List(layout.itemCenters.size) { Animatable(0f) }
            }
        val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
        // 屏幕中央的所选应用卡片：淡入淡出；换选中项时直接换图并轻弹一下。
        val previewProgress = remember { Animatable(0f) }
        val previewPop = remember { Animatable(1f) }
        val previewIndex = remember { mutableIntStateOf(NO_SELECTION) }
        LaunchedEffect(selectedIndexState.intValue) {
            val selected = selectedIndexState.intValue
            val show =
                centerPreview && selected != NO_SELECTION && selected < catalog.radialApps.size
            if (!show) {
                // 保留上一项，淡出期间仍画它。
                if (!animationsEnabled) previewProgress.snapTo(0f)
                else previewProgress.animateTo(0f, tween(CENTER_PREVIEW_FADE_MILLIS))
                return@LaunchedEffect
            }
            val switching = previewIndex.intValue != selected && previewProgress.value > 0.5f
            previewIndex.intValue = selected
            requestPreviewImage(selected)
            if (!animationsEnabled) {
                previewPop.snapTo(1f)
                previewProgress.snapTo(1f)
                return@LaunchedEffect
            }
            coroutineScope {
                launch { previewProgress.animateTo(1f, tween(CENTER_PREVIEW_FADE_MILLIS)) }
                if (switching) {
                    launch {
                        previewPop.snapTo(CENTER_PREVIEW_POP_FROM)
                        previewPop.animateTo(1f, tween(CENTER_PREVIEW_FADE_MILLIS, easing = LinearOutSlowInEasing))
                    }
                } else {
                    previewPop.snapTo(1f)
                }
            }
        }

        val exitRequest = exitRequestState.value
        LaunchedEffect(panelModeState.value, exitRequest, backdropOnlyState.value) {
            if (panelModeState.value || exitRequest != null || backdropOnlyState.value) return@LaunchedEffect
            if (!animationsEnabled) {
                entryProgress.snapTo(1f)
            } else {
                entryProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(RadialEntryMotion.DURATION_MILLIS.toInt(), easing = LinearEasing),
                )
            }
        }
        LaunchedEffect(selectedIndexState.intValue, itemRings) {
            val selected = selectedIndexState.intValue
            coroutineScope {
                itemRings.forEachIndexed { index, ring ->
                    launch {
                        val target = if (index == selected) 1f else 0f
                        if (!animationsEnabled) {
                            ring.snapTo(target)
                        } else {
                            ring.animateTo(
                                targetValue = target,
                                animationSpec = tween(
                                    RadialEntryMotion.SELECTION_DURATION_MILLIS.toInt(),
                                    easing = { RadialEntryMotion.selectionProgress(it) },
                                ),
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(panelModeState.value) {
            if (panelModeState.value) {
                panelInputEnabled.value = false
                if (!animationsEnabled) {
                    radialHandoffProgress.snapTo(1f)
                    panelProgress.snapTo(1f)
                    panelContentProgress.snapTo(1f)
                    panelInputEnabled.value = true
                } else {
                    coroutineScope {
                        launch {
                            radialHandoffProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = RadialHandoffMotion.DURATION_MILLIS.toInt(),
                                        easing = LinearEasing,
                                    ),
                            )
                        }
                        launch {
                            panelProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    folmeSpring(
                                        damping = PANEL_EXPAND_DAMPING,
                                        response = PANEL_EXPAND_RESPONSE_SECONDS,
                                        visibilityThreshold = PANEL_VISIBILITY_THRESHOLD,
                                    ),
                            )
                        }
                        launch {
                            panelContentProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = PANEL_CONTENT_FADE_MILLIS,
                                        delayMillis = PANEL_CONTENT_DELAY_MILLIS,
                                        easing = LinearOutSlowInEasing,
                                    ),
                            )
                            panelInputEnabled.value = true
                        }
                    }
                }
            }
        }
        LaunchedEffect(exitRequest) {
            if (exitRequest != null) {
                exitProgress.snapTo(0f)
                exitProgress.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = RadialDismissMotion.DURATION_MILLIS.toInt(),
                            easing = LinearEasing,
                        ),
                )
                completeRadialExit()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val radialLayout = if (panelModeState.value) handoffLayoutState.value else layout
            val radialMetrics =
                if (panelModeState.value) {
                    handoffMetricsState.value ?: metrics.radial
                } else {
                    metrics.radial
                }
            RadialCanvas(
                metrics = radialMetrics,
                layout = radialLayout,
                panelProgress = panelProgress,
                radialHandoffProgress = radialHandoffProgress,
                exitProgress = exitProgress,
                itemRings = itemRings,
                animationsEnabled = animationsEnabled,
                previewProgress = previewProgress,
                previewPop = previewPop,
                previewIndex = previewIndex.intValue,
            )
            if (keepOpen && !panelModeState.value && exitRequest == null) {
                // 保持显示期间窗口可触摸：点中条目直接启动，点空白处收起。
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(radialLayout, radialHitRangeDp) {
                                // catalog 在点按时经属性现读，换目录不必重启手势检测。
                                detectTapGestures { offset ->
                                    val hitRadius =
                                        radialHitRangeDp * resources.displayMetrics.density
                                    val index =
                                        RadialGeometry.selection(
                                            layout = radialLayout,
                                            x = offset.x,
                                            y = offset.y,
                                            previous = null,
                                            enterRadius = hitRadius,
                                            keepRadius = 0f,
                                        )
                                    if (index == null) {
                                        dismissAnimated()
                                    } else if (index < catalog.radialApps.size) {
                                        dismissAnimated(catalog.radialApps[index])
                                    } else {
                                        showMorePanel()
                                    }
                                }
                            },
                )
            }
            if (panelModeState.value) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    requestDismiss()
                                }
                            },
                )
                MoreAppsPanel(
                    metrics = metrics.panel,
                    panelProgress = panelProgress,
                    contentProgress = panelContentProgress,
                    inputEnabled = panelInputEnabled.value,
                    animationsEnabled = animationsEnabled,
                )
            }
        }
    }

    @Composable
    private fun RadialCanvas(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        panelProgress: Animatable<Float, *>,
        radialHandoffProgress: Animatable<Float, *>,
        exitProgress: Animatable<Float, *>,
        itemRings: List<Animatable<Float, *>>,
        animationsEnabled: Boolean,
        previewProgress: Animatable<Float, *>,
        previewPop: Animatable<Float, *>,
        previewIndex: Int,
    ) {
        val itemColors = LocalRadialItemColors.current
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            if (backdropOnlyState.value) {
                drawRect(Color.Black, alpha = OverlayBackdrop.MAX_ALPHA * backdropAlpha.floatValue)
                return@Canvas
            }
            val panel =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    panelProgress.value.coerceIn(0f, 1f)
                }
            val handoff =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    radialHandoffProgress.value
                }
            val exitRequest = exitRequestState.value
            val entrySample = RadialEntryMotion.sample(
                when {
                    !animationsEnabled -> 1f
                    exitRequest != null -> exitRequest.entryProgress
                    panelModeState.value -> handoffEntryProgress.floatValue
                    else -> entryProgress.value
                },
            )
            val exitSample = RadialDismissMotion.sample(exitProgress.value)
            val handoffVisuals = RadialHandoffMotion.sample(
                frozenRevealProgress = entrySample.contentAlpha,
                handoffProgress = handoff,
                panelProgress = panel,
            )
            // 提早松手时从当前入场帧衔接退出，不跳到完整展开位置。
            val dismissalBlend = exitProgress.value.coerceIn(0f, 1f)
            val dismissal = if (exitRequest == null) null else RadialEntryVisuals(
                contentAlpha = entrySample.contentAlpha * exitSample.contentAlpha,
                radialProgress = exitSample.radialProgress + (entrySample.radialProgress - 1f) * (1f - dismissalBlend),
                horizontalOvershoot = exitSample.horizontalOvershoot + entrySample.horizontalOvershoot * (1f - dismissalBlend),
                rotationDegrees = exitSample.rotationDegrees + entrySample.rotationDegrees * (1f - dismissalBlend),
                iconScale = exitSample.iconScale + (entrySample.iconScale - 1f) * (1f - dismissalBlend),
            )
            val visual = dismissal ?: entrySample
            val scrimAlpha = if (panelModeState.value) handoffVisuals.scrimProgress else visual.contentAlpha
            drawRect(Color.Black, alpha = OverlayBackdrop.MAX_ALPHA * scrimAlpha)
            val alpha = visual.contentAlpha * if (panelModeState.value) handoffVisuals.contentAlpha else 1f
            if (alpha <= 0f) return@Canvas
            drawRadialItems(
                metrics = metrics,
                layout = layout,
                motion = visual,
                contentAlpha = alpha,
                contentScale = if (panelModeState.value) handoffVisuals.contentScale else 1f,
                itemRings = itemRings,
                colors = itemColors,
            )
            drawCenterPreview(
                index = previewIndex,
                progress = previewProgress.value,
                pop = previewPop.value,
                contentAlpha = alpha,
                colors = itemColors,
            )
        }
    }

    /** 屏幕正中的所选应用卡片；只在设置打开、选中应用（不含「更多」）时出现。 */
    private fun DrawScope.drawCenterPreview(
        index: Int,
        progress: Float,
        pop: Float,
        contentAlpha: Float,
        colors: RadialItemColors,
    ) {
        if (progress <= 0f || !centerPreview) return
        val image = radialImages.value.getOrNull(index) ?: return
        val alpha = (contentAlpha * progress).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val density = resources.displayMetrics.density
        val insets = centerInsets
        val safeWidth = (size.width - insets.left - insets.right).coerceAtLeast(0f)
        val paint = previewLabelPaint
        val rawLabel = catalog.radialApps.getOrNull(index)?.label?.takeIf { centerLabel && it.isNotBlank() }
        val key = listOf(rawLabel, centerSizeDp, safeWidth, resources.configuration.fontScale)
        val labelLayout =
            centerLabelLayout?.takeIf { it.key == key } ?: run {
                paint.textSize =
                    RadialCenterPreview.labelSp(centerSizeDp.toFloat()) * density * resources.configuration.fontScale
                val text =
                    rawLabel?.let {
                        android.text.TextUtils.ellipsize(
                            it, paint, safeWidth * RadialCenterPreview.MAX_LABEL_WIDTH_FRACTION, android.text.TextUtils.TruncateAt.END,
                        ).toString()
                    }
                val metrics = paint.fontMetrics
                CenterLabelLayout(key, text, text?.let(paint::measureText), metrics.ascent, metrics.descent)
                    .also { centerLabelLayout = it }
            }
        val label = labelLayout.text
        val card =
            RadialCenterPreview.layout(
                width = size.width,
                height = size.height,
                insets = insets,
                density = density,
                labelWidth = labelLayout.width,
                iconDp = centerSizeDp.toFloat(),
                labelAscent = labelLayout.ascent,
                labelDescent = labelLayout.descent,
            )
        if (card.iconSize <= 0f) return
        val scale = (CENTER_PREVIEW_SCALE_FROM + (1f - CENTER_PREVIEW_SCALE_FROM) * progress) * pop
        val pivot = Offset((card.left + card.right) / 2f, (card.top + card.bottom) / 2f)
        // 只画图标与应用名，不加底板；高清图标未到时先用扇形那份。
        val sharp = catalog.radialApps.getOrNull(index)?.target?.let { previewImages[it] }
        withTransform({ scale(scale, scale, pivot) }) {
            val iconSize = card.iconSize * (sharp?.second ?: image.bodyFill)
            drawSystemImage(sharp?.first ?: image.icon, card.iconCenterX, card.iconCenterY, iconSize, alpha)
            image.badge?.let { badge ->
                drawBadge(badge, card.iconCenterX, card.iconCenterY, iconSize, alpha, horizontalOffsetFraction = 0f)
            }
            val baseline = card.labelBaseline
            if (label != null && baseline != null) {
                // 白字加阴影：背后是压暗的屏幕，深浅色下都看得清。
                paint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                paint.setShadowLayer(4f * density, 0f, 1f * density, android.graphics.Color.argb((alpha * 160f).roundToInt().coerceIn(0, 255), 0, 0, 0))
                drawIntoCanvas { it.nativeCanvas.drawText(label, card.iconCenterX, baseline, paint) }
            }
        }
    }

    private fun DrawScope.drawRadialItems(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        motion: RadialEntryVisuals,
        contentAlpha: Float,
        contentScale: Float,
        itemRings: List<Animatable<Float, *>>,
        colors: RadialItemColors,
    ) {
        val direction = if (layout.side == CornerSide.Left) 1f else -1f
        val overshoot = direction * RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP * metrics.pixelsPerBaseDp * motion.horizontalOvershoot
        layout.itemCenters.forEachIndexed { index, destination ->
            val centerX = layout.origin.x + (destination.x - layout.origin.x) * motion.radialProgress + overshoot
            val centerY = layout.origin.y + (destination.y - layout.origin.y) * motion.radialProgress
            val ringProgress = itemRings.getOrNull(index)?.value ?: 0f
            // 选中项放大：复用每格原有 0→1 的进度动画，1f→1.2f 平滑放大；
            // 不再绘制白色描边外圈（原实现用同一进度画 SELECTION_RING_ALPHA 的圆环）。
            val selectionScale = 1f + SELECTED_ICON_SCALE_GAIN * ringProgress
            // 外大内小：内侧圆弧逐圈缩小；应用图标再按留白补偿，本体与「更多」圆盘同大。
            val arcScale = RadialIconScale.arcScale(arcOfIndex.getOrElse(index) { 0 }, catalog.settings.effectiveRadialArcCount)
            val scale = motion.iconScale * contentScale * selectionScale * arcScale
            val diameter = metrics.iconDiameter * scale
            val image = if (index < catalog.radialApps.size) radialImages.value.getOrNull(index) else null
            val iconSize = diameter * (image?.bodyFill ?: 1f)
            rotate(motion.rotationDegrees, pivot = Offset(centerX, centerY)) {
                if (index < catalog.radialApps.size) {
                    image?.let { drawSystemImage(it.icon, centerX, centerY, iconSize, contentAlpha) }
                } else {
                    drawMoreItem(centerX, centerY, diameter, contentAlpha, colors, IconShapes.isSquare(catalog.settings.iconShape))
                }
                // 左右扇形的角标分别位于左下角、右下角，避开相邻图标，不参与圆形裁剪。
                image?.let { image ->
                    image.badge?.let { badge ->
                        drawBadge(
                            badge, centerX, centerY, iconSize, contentAlpha,
                            horizontalOffsetFraction = if (layout.side == CornerSide.Left) image.badgeLeftOffsetFraction else 0f,
                        )
                    }
                }
            }
        }
    }

    private fun DrawScope.drawBadge(
        badge: ImageBitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float,
        horizontalOffsetFraction: Float,
    ) {
        val pixels = size.roundToInt()
        if (pixels <= 0) return
        drawImage(
            image = badge,
            dstOffset = IntOffset(
                (centerX - pixels / 2f + pixels * horizontalOffsetFraction).roundToInt(),
                (centerY - pixels / 2f).roundToInt(),
            ),
            dstSize = IntSize(pixels, pixels),
            alpha = alpha,
            filterQuality = FilterQuality.High,
        )
    }

    /**
     * 按 ColorOS 17 工具栏溢出按钮的观感绘制：底板加横排三点；配色随深浅色切换。
     * 比例取自同一按钮 40dp 圆盘内 24dp 图标的实机测量，随图标直径缩放。
     * 底板随「图标形状」：圆形为圆盘；方形取应用图标的外形模板（[morePlateMask]），
     * 与侧边栏条目同法，取不到模板时退回 [IconShapes.SQUARE_CORNER_FRACTION] 的圆角方形。
     */
    private fun DrawScope.drawMoreItem(
        centerX: Float,
        centerY: Float,
        diameter: Float,
        alpha: Float,
        colors: RadialItemColors,
        square: Boolean,
    ) {
        val radius = diameter / 2f
        if (radius <= 0f) return
        // 用户自定义颜色时底板取纯色，三个点按底板亮度取黑或白；0 跟随系统深浅色。
        val custom = moreColor.takeIf { it != ModulePreferences.DEFAULT_RADIAL_MORE_COLOR }
        val plateTop = custom?.let(::Color) ?: colors.morePlateTop
        val plateBottom = custom?.let(::Color) ?: colors.morePlateBottom
        val dotColor = custom?.let { Color(MoreButtonColors.dotFor(it)) } ?: colors.moreDot
        // 去掉原实现的径向光晕与描边圈：只保留底板与三点，避免滑到「更多」时多出一层光圈。
        val plate =
            Brush.verticalGradient(
                0f to plateTop,
                1f to plateBottom,
                startY = centerY - radius,
                endY = centerY + radius,
            )
        val topLeft = Offset(centerX - radius, centerY - radius)
        val plateSize = Size(diameter, diameter)
        val mask = if (square) morePlateMask.value else null
        when {
            !square -> drawCircle(brush = plate, radius = radius, center = Offset(centerX, centerY), alpha = alpha)
            mask == null ->
                drawRoundRect(
                    brush = plate,
                    topLeft = topLeft,
                    size = plateSize,
                    cornerRadius = CornerRadius(diameter * IconShapes.SQUARE_CORNER_FRACTION),
                    alpha = alpha,
                )
            else ->
                drawIntoCanvas { canvas ->
                    // 先画模板的 alpha，再用 SrcIn 把渐变填进模板，外形与应用图标一致。
                    canvas.saveLayer(Rect(topLeft, plateSize), androidx.compose.ui.graphics.Paint())
                    drawImage(
                        image = mask,
                        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                        dstSize = IntSize(diameter.roundToInt().coerceAtLeast(1), diameter.roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.High,
                    )
                    drawRect(brush = plate, topLeft = topLeft, size = plateSize, alpha = alpha, blendMode = BlendMode.SrcIn)
                    canvas.restore()
                }
        }
        for (offset in -1..1) {
            drawCircle(
                color = dotColor,
                radius = diameter * MORE_DOT_RADIUS_FRACTION,
                center = Offset(centerX + offset * diameter * MORE_DOT_SPACING_FRACTION, centerY),
                alpha = alpha,
            )
        }
    }

    private fun DrawScope.drawSystemImage(
        image: ImageBitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float,
    ) {
        if (image.width <= 0 || image.height <= 0 || size <= 0f) return
        val aspectRatio = image.width.toFloat() / image.height
        val drawWidth = if (aspectRatio >= 1f) size else size * aspectRatio
        val drawHeight = if (aspectRatio >= 1f) size / aspectRatio else size
        val destinationSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1))
        // 位图已经按 IconShapes 塑形，按原 alpha 绘制，保留方形的四角。
        drawImage(
            image = image,
            dstOffset =
                IntOffset(
                    (centerX - destinationSize.width / 2f).roundToInt(),
                    (centerY - destinationSize.height / 2f).roundToInt(),
                ),
            dstSize = destinationSize,
            alpha = alpha,
            filterQuality = FilterQuality.High,
        )
    }

    @Composable
    private fun MoreAppsPanel(
        metrics: PanelVisualMetrics,
        panelProgress: Animatable<Float, *>,
        contentProgress: Animatable<Float, *>,
        inputEnabled: Boolean,
        animationsEnabled: Boolean,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val squircleEnabled = isSquircleEnabled()
        val panelColor = MiuixTheme.colorScheme.surface.copy(alpha = PANEL_SURFACE_ALPHA)
        val labelColor = MiuixTheme.colorScheme.onSurface
        val bounds = metrics.bounds
        val width = with(density) { bounds.width.toDp() }
        val height = with(density) { bounds.height.toDp() }
        val cornerRadius = with(density) { metrics.cornerRadius.toDp() }
        val horizontalPadding = with(density) { metrics.contentHorizontalPadding.toDp() }
        val verticalPadding = with(density) { metrics.topPadding.toDp() }
        val cellHeight = with(density) { metrics.cellHeight.toDp() }
        val iconSize = with(density) { metrics.iconDiameter.toDp() }
        val labelGap = with(density) { (metrics.labelTopOffset - metrics.iconDiameter / 2f).toDp() }
        val labelSize = with(density) { metrics.labelTextSize.toSp() }

        Box(
            modifier =
                Modifier
                    .absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .requiredSize(width = width, height = height)
                    .panelReveal(
                        progress = {
                            if (animationsEnabled) panelProgress.value else 1f
                        },
                        seedSize = metrics.iconDiameter,
                        cornerRadius = metrics.cornerRadius,
                        anchorOnLeft = side == CornerSide.Left,
                        squircleEnabled = squircleEnabled,
                    )
                    .squircleSurface(color = panelColor, cornerRadius = cornerRadius)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            resetPanelTimeout()
                            waitForUpOrCancellation()
                        }
                    },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(metrics.columns),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress =
                                if (animationsEnabled) {
                                    contentProgress.value.coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                            alpha = progress
                            translationY =
                                (1f - progress) *
                                    metrics.iconDiameter *
                                    PANEL_CONTENT_TRANSLATION_FRACTION
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                contentPadding =
                    PaddingValues(
                        horizontal = horizontalPadding,
                        vertical = verticalPadding,
                    ),
                verticalArrangement = Arrangement.Top,
                userScrollEnabled = inputEnabled,
            ) {
                items(
                    items = catalog.panelApps,
                    key = { entry -> io.github.zailinn.flymefreeform.config.PinnedTargetCodec.encode(entry.target) },
                ) { entry ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(cellHeight)
                                .clickable(enabled = inputEnabled) {
                                    listener.onEntryCommitted(entry)
                                },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val loader = catalog.panelIconLoader
                        val placeholder = PanelIconPlaceholder.isPlaceholder(entry.icon)
                        // 图像属于当前格子与当前加载器，旧 effect 清理不能影响新会话。
                        var loaded by remember(entry.target, loader) {
                            mutableStateOf(loader?.cached(entry.target)?.asImageBitmap())
                        }
                        if (placeholder) {
                            DisposableEffect(entry.target, loader) {
                                val session = VisibleIconRequest<ImageBitmap> { loaded = it }
                                session.start(loader?.cached(entry.target)?.asImageBitmap()) { receive ->
                                    loader?.request(entry.target) { bitmap ->
                                        post { if (!disposed) receive(bitmap.asImageBitmap()) }
                                    } ?: {}
                                }
                                onDispose { session.close() }
                            }
                        }
                        val image = if (placeholder) loaded else remember(entry.icon) { entry.icon.asImageBitmap() }
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize),
                                filterQuality = FilterQuality.High,
                            )
                        } else {
                            // 图标未画好时留出同样大小的空位，名称不跳动；格子在视野里才请求。
                            Spacer(modifier = Modifier.size(iconSize))
                        }
                        Spacer(modifier = Modifier.height(labelGap))
                        Text(
                            text = entry.label,
                            modifier = Modifier.fillMaxWidth(),
                            color = labelColor,
                            fontSize = labelSize,
                            letterSpacing = 0.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(snapshotExclusion)
        if (!disposed && !lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        post {
            if (!disposed && isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(snapshotExclusion)
        snapshotSurface = null
        val cleanupFailure = disposeOverlay()
        super.onDetachedFromWindow()
        cleanupFailure?.let(listener::onCleanupFailed)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && (oldw != w || oldh != h)) {
            requestDismiss()
            return
        }
        updateLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        appliedWindowInsets = insets
        val result = super.onApplyWindowInsets(insets)
        updateLayout()
        return result
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEventPreIme(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        // 保持挂起时浮层持有焦点，返回键到不了下层应用：按返回收起扇形。
        val resting = isRestingOpen
        if (!panelModeState.value && !resting) return false
        if (event.action == KeyEvent.ACTION_UP) {
            if (resting) dismissAnimated() else requestDismiss()
        }
        return true
    }

    private fun updateLayout() {
        if (width <= 0 || height <= 0 || disposed) return
        updateLayout(
            requestedWidth = width,
            requestedHeight = height,
            safeInsets = currentSafeInsets(),
        )
    }

    private fun updateLayout(
        requestedWidth: Int,
        requestedHeight: Int,
        safeInsets: OverlaySafeInsets,
    ) {
        if (requestedWidth <= 0 || requestedHeight <= 0 || disposed) return
        val radialInsets = currentRadialInsets()
        centerInsets = radialInsets
        val safeWidth = (requestedWidth - radialInsets.left - radialInsets.right).coerceAtLeast(1f)
        val safeHeight = (requestedHeight - radialInsets.top - radialInsets.bottom).coerceAtLeast(1f)
        val systemIconDiameter = systemIconSize()
        val arcCount = catalog.settings.effectiveRadialArcCount
        // 各圈条数以目录给出的为准：自定义的内侧圆弧可能放不满，不能按总数重新均分。
        // 目录未带分圈信息时（旧快照）才按容量顺序分配。
        val arcItemCounts =
            catalog.radialArcCounts.takeIf { counts ->
                counts.isNotEmpty() && counts.sum() == catalog.radialApps.size
            } ?: RadialArcPlan.distribute(
                itemCount = catalog.radialApps.size,
                outerLimit = catalog.settings.radialMaxItems,
                arcCount = arcCount,
            )
        val outerSlotCount = arcItemCounts.first() + 1
        arcOfIndex = RadialArcPlan.arcOfIndex(arcItemCounts)
        val metrics =
            AdaptiveOverlayGeometry.calculate(
                width = requestedWidth.toFloat(),
                height = requestedHeight.toFloat(),
                safeInsets = safeInsets,
                systemIconSize = systemIconDiameter,
                density = resources.displayMetrics.density,
                radialItemCount = outerSlotCount,
                radialHitRangeDp = radialHitRangeDp.toFloat(),
                radialArcCount = arcCount,
                radialInsets = radialInsets,
                fontScale = resources.configuration.fontScale,
                panelItemCount = catalog.panelApps.size,
                anchorOnLeft = side == CornerSide.Left,
            )
        metricsState.value = metrics
        layoutState.value =
            RadialGeometry.layout(
                side = side,
                width = safeWidth,
                // 圆心上移：最下面一排图标离底边更远。
                height = safeHeight - metrics.radial.bottomLift,
                offsetX = radialInsets.left,
                offsetY = radialInsets.top,
                radius = metrics.radial.radius,
                itemCount = outerSlotCount,
                innerArcCounts = arcItemCounts.drop(1),
                arcSpacing = metrics.radial.arcSpacing,
                innerArcRadii = metrics.radial.innerArcRadii,
                // 角落空白区按最内一圈缩小后的图标计。
                iconDiameter =
                    metrics.radial.iconDiameter *
                        RadialIconScale.arcScale(arcItemCounts.indexOfLast { it > 0 }, arcCount),
            )
        if (!panelModeState.value && !dismissing) updateGestureFromLatestPoint()
    }

    private fun updateGestureFromLatestPoint() {
        val metrics = metricsState.value ?: return
        val layout = layoutState.value
        if (layout.itemCenters.isEmpty()) return
        val next =
            RadialGeometry.selection(
                layout = layout,
                x = latestX,
                y = latestY,
                previous = selectedIndex,
                enterRadius = metrics.radial.selectionEnterRadius,
                keepRadius = metrics.radial.selectionKeepRadius,
            )
        if (next == selectedIndex) return
        // 命中区判定细节：进了哪一格、离格心多远、当前生效的进入半径。
        val center = next?.let { layout.itemCenters.getOrNull(it) }
        debug(
            if (next == null) {
                "SYSTEM_HIT_LEAVE"
            } else {
                "SYSTEM_HIT_ENTER_" + next + "_" + (catalog.radialApps.getOrNull(next)?.label ?: "?") +
                    "_DIST_" + kotlin.math.hypot(latestX - center!!.x, latestY - center.y).toInt() +
                    "_ENTER_PX_" + metrics.radial.selectionEnterRadius.toInt()
            },
        )
        selectedIndex = next
        selectedIndexState.intValue = next ?: NO_SELECTION
        val now = SystemClock.uptimeMillis()
        if (next != null) {
            // 跨圈换格用「越过阈值」的震动，不看屏幕也能分辨进了哪一圈；同圈换格仍是节流的轻 tick。
            val arc = arcOfIndex.getOrElse(next) { 0 }
            val crossedArc = lastSelectedArc >= 0 && arc != lastSelectedArc
            lastSelectedArc = arc
            if (crossedArc) {
                performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                lastTickUptime = now
            } else if (now - lastTickUptime >= TICK_INTERVAL_MS) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastTickUptime = now
            }
        }
    }

    private fun showMorePanel() {
        if (dismissing || panelModeState.value) return
        selectedIndex = null
        handoffEntryProgress.floatValue = entryProgress.value
        handoffLayoutState.value = layoutState.value
        handoffMetricsState.value = metricsState.value?.radial
        removeCallbacks(timeout)
        prefetchPanelFirstScreen()
        listener.onMorePanelRequested()
    }

    /** 内置面板第一屏（含相邻缓冲行）里还是占位图的条目，交给目录预画；量不出布局时取前 [PANEL_PREFETCH_FALLBACK] 项。 */
    private fun prefetchPanelFirstScreen() {
        val loader = catalog.panelIconLoader ?: return
        val apps = catalog.panelApps
        val range = metricsState.value?.panel?.visibleItemRange(apps.size, 0f)
            ?: (0 until minOf(apps.size, PANEL_PREFETCH_FALLBACK))
        val targets = range.mapNotNull { apps.getOrNull(it) }
            .filter { PanelIconPlaceholder.isPlaceholder(it.icon) && loader.cached(it.target) == null }
            .map { it.target }
        if (targets.isNotEmpty()) loader.prefetch(targets)
    }

    fun showBuiltInMorePanel() {
        if (disposed || !isAttachedToWindow) return
        backdropOnlyState.value = false
        panelModeState.value = true
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
        requestFocus()
    }

    fun retainBackdropForPanel() {
        backdropAlpha.floatValue = 1f
        backdropOnlyState.value = true
    }

    fun beginBackdropExit() {
        if (disposed || !backdropOnlyState.value) return
        backdropAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            backdropAlpha.floatValue = 0f
            return
        }
        backdropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            addUpdateListener {
                val alpha = AllAppsPanelMotion.exit(it.currentPlayTime / 1000f).alpha
                backdropAlpha.floatValue = alpha
                if (alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD) {
                    backdropAlpha.floatValue = 0f
                    cancel()
                }
            }
            start()
        }
    }

    fun hideBackdropAfterFrame(onHidden: () -> Unit) {
        backdropAnimator?.cancel()
        backdropAnimator = null
        if (disposed || !isAttachedToWindow) {
            onHidden()
            return
        }
        // 必须等透明帧提交后才让截图等工具执行，不能只确认状态变量已经更新。
        if (isHardwareAccelerated) {
            viewTreeObserver.registerFrameCommitCallback { post { onHidden() } }
        } else {
            postOnAnimation { postOnAnimation { onHidden() } }
        }
        backdropAlpha.floatValue = 0f
        invalidate()
    }

    // 浮层存活期间窗口不会隐藏，Surface 不重建：标记成功一次即可，之后每帧只剩一次判空。
    private var snapshotSurface: android.view.SurfaceControl? = null
    private var snapshotExclusionFailed = false
    private val snapshotExclusion = android.view.ViewTreeObserver.OnPreDrawListener {
        if (!disposed && !snapshotExclusionFailed && snapshotSurface == null) {
            runCatching {
                val root = android.view.View::class.java.getDeclaredMethod("getViewRootImpl").apply {
                    isAccessible = true
                }.invoke(this)
                val surface = root?.javaClass?.getMethod("getSurfaceControl")?.invoke(root)
                    as? android.view.SurfaceControl
                if (surface != null && surface.isValid) {
                    android.view.SurfaceControl.Transaction().use { transaction ->
                        transaction.javaClass.getMethod("setSkipScreenshot", android.view.SurfaceControl::class.java,
                            Boolean::class.javaPrimitiveType).invoke(transaction, surface, true)
                        transaction.apply()
                    }
                    snapshotSurface = surface
                }
            }.onFailure {
                snapshotExclusionFailed = true
                debug("SYSTEM_OVERLAY_SNAPSHOT_EXCLUSION_FAILED_" + it.javaClass.simpleName)
            }
        }
        true
    }

    private fun dismissAnimated(pendingCommit: RadialEntry?) {
        if (dismissing || disposed) return
        dismissing = true
        removeCallbacks(timeout)
        // 松手当帧就把目标交出去，不等 200ms 退场动画：
        // 应用/快捷方式让浮层自己淡出；取屏类工具由宿主立刻 removeViewImmediate，
        // 再等两帧 → 屏幕已经是干净的，不必再为动画白等 200ms。
        if (pendingCommit != null) {
            commitNotified = true
            listener.onEntryCommitted(pendingCommit)
            if (disposed) return
        }
        exitRequestState.value = ExitRequest(pendingCommit, entryProgress.value)
        removeCallbacks(dismissFallback)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            completeRadialExit()
        } else {
            val durationScale = ValueAnimator.getDurationScale().coerceAtLeast(1f)
            val fallbackDelay =
                (RadialDismissMotion.DURATION_MILLIS * durationScale + DISMISS_FALLBACK_GRACE_MS)
                    .roundToInt()
                    .toLong()
            postDelayed(dismissFallback, fallbackDelay)
        }
    }

    private fun completeRadialExit() {
        if (dismissNotified || disposed) return
        removeCallbacks(dismissFallback)
        val pendingCommit = exitRequestState.value?.pendingCommit
        if (pendingCommit == null || commitNotified) {
            commitNotified = false
            requestDismiss()
        } else {
            dismissNotified = true
            removeCallbacks(timeout)
            listener.onEntryCommitted(pendingCommit)
        }
    }

    private fun resetPanelTimeout() {
        if (!panelModeState.value || disposed) return
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
    }

    /** 内置「更多」面板打开时收起（回桌面、手势上滑时由协调器调用）；其他状态不处理。 */
    fun dismissPanel() {
        if (panelModeState.value) requestDismiss()
    }

    private fun requestDismiss() {
        if (dismissNotified || disposed) return
        dismissNotified = true
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        listener.onDismissRequested()
    }

    private fun mergeCleanupFailure(
        current: Throwable?,
        next: Throwable,
    ): Throwable =
        current?.also { previous -> previous.addSuppressed(next) } ?: next

    private fun currentRadialInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        // 扇形属于角落手势本身；系统手势保留区及屏幕圆角不是整页绘制边距。
        // 只避让实际系统栏、缺口和键盘，正常竖屏圆心落在物理侧边与导航栏顶边。
        val insets = windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
        )
        return OverlaySafeInsets(insets.left.toFloat(), insets.top.toFloat(), insets.right.toFloat(), insets.bottom.toFloat())
    }

    private fun currentSafeInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        val drawingInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime(),
            )
        val gestureInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemGestures() or
                    WindowInsets.Type.mandatorySystemGestures(),
            )
        val topLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        val topRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
        val bottomLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
        val bottomRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
        return OverlaySafeInsets(
            left = maxOf(drawingInsets.left, gestureInsets.left).toFloat(),
            top =
                maxOf(
                    drawingInsets.top,
                    gestureInsets.top,
                    topLeft?.radius ?: 0,
                    topRight?.radius ?: 0,
                ).toFloat(),
            right = maxOf(drawingInsets.right, gestureInsets.right).toFloat(),
            bottom =
                maxOf(
                    drawingInsets.bottom,
                    gestureInsets.bottom,
                    bottomLeft?.radius ?: 0,
                    bottomRight?.radius ?: 0,
                ).toFloat(),
        )
    }

    private fun systemIconSize(): Float =
        try {
            context
                .getSystemService(ActivityManager::class.java)
                ?.launcherLargeIconSize
                ?.toFloat()
                ?: 0f
        } catch (_: RuntimeException) {
            0f
        }

    private data class ExitRequest(val pendingCommit: RadialEntry?, val entryProgress: Float)

    private companion object {
        val EMPTY_LAYOUT =
            RadialLayout(
                side = CornerSide.Right,
                origin = io.github.zailinn.flymefreeform.gesture.GesturePoint(0f, 0f),
                radius = 0f,
                itemCenters = emptyList(),
            )
        const val NO_SELECTION = -1
        const val CENTER_PREVIEW_FADE_MILLIS = 120
        /** 卡片入场从该比例放大到 1；换选中项时从 [CENTER_PREVIEW_POP_FROM] 弹回 1。 */
        const val CENTER_PREVIEW_SCALE_FROM = 0.9f
        const val CENTER_PREVIEW_POP_FROM = 0.94f
        /** 选中项放大幅度：1f → 1.2f，与每格 0→1 的进度动画相乘。 */
        const val SELECTED_ICON_SCALE_GAIN = 0.2f
        const val PANEL_SURFACE_ALPHA = 253f / 255f
        // 24dp 图标在 40dp 圆盘内：点径 3.5、点距 7.125（图标视口单位）。
        const val MORE_DOT_RADIUS_FRACTION = 1.75f / 24f * 0.6f
        const val MORE_DOT_SPACING_FRACTION = 7.125f / 24f * 0.6f
        const val PANEL_EXPAND_DAMPING = 0.9f
        const val PANEL_EXPAND_RESPONSE_SECONDS = 0.3f
        const val PANEL_VISIBILITY_THRESHOLD = 0.0001f
        const val PANEL_CONTENT_DELAY_MILLIS = 45
        const val PANEL_CONTENT_FADE_MILLIS = 180
        const val PANEL_CONTENT_TRANSLATION_FRACTION = 0.12f
        const val TICK_INTERVAL_MS = 80L
        const val GESTURE_TIMEOUT_MS = 5_000L

        const val PANEL_TIMEOUT_MS = 15_000L
        const val PANEL_PREFETCH_FALLBACK = 24
        const val DISMISS_FALLBACK_GRACE_MS = 260L
    }
}

private fun Modifier.panelReveal(
    progress: () -> Float,
    seedSize: Float,
    cornerRadius: Float,
    anchorOnLeft: Boolean,
    squircleEnabled: Boolean,
): Modifier =
    drawWithCache {
        val revealPath = Path()
        onDrawWithContent {
            val revealProgress = progress()
            val revealWidth = PanelRevealMotion.extent(size.width, seedSize, revealProgress)
            val revealHeight = PanelRevealMotion.extent(size.height, seedSize, revealProgress)
            val revealLeft =
                PanelRevealMotion.horizontalOffset(size.width, revealWidth, anchorOnLeft)
            val revealTop = PanelRevealMotion.verticalOffset(size.height, revealHeight)
            revealPath.rewind()
            revealPath.addSquircleRect(
                width = revealWidth,
                height = revealHeight,
                cornerRadius = cornerRadius,
                squircleEnabled = squircleEnabled,
            )
            translate(left = revealLeft, top = revealTop) {
                clipPath(revealPath) {
                    translate(left = -revealLeft, top = -revealTop) {
                        this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
    }

/**
 * 「更多」底板模板的缓存：浮层每次呼出都新建，模板跨次复用，只在扇形应用换了图标时重取。
 * 只看前 [CANDIDATES] 个条目，避免全是圆形工具时逐个扫完。
 */
private object MorePlateMasks {
    private const val CANDIDATES = 12
    private var source: java.lang.ref.WeakReference<android.graphics.Bitmap>? = null
    private var mask: ImageBitmap? = null

    @Synchronized
    fun of(catalog: AppCatalogSnapshot): ImageBitmap? {
        if (!IconShapes.isSquare(catalog.settings.iconShape)) return null
        val cached = source?.get()
        if (cached != null && mask != null && catalog.radialApps.any { it.icon === cached }) return mask
        for (entry in catalog.radialApps.take(CANDIDATES)) {
            val found = runCatching { IconShapes.bodyMaskFrom(entry.icon) }.getOrNull() ?: continue
            source = java.lang.ref.WeakReference(entry.icon)
            mask = found.asImageBitmap()
            return mask
        }
        return null
    }
}

private class RadialImage(entry: RadialEntry) {
    val icon: ImageBitmap = entry.icon.asImageBitmap()
    val badge: ImageBitmap? = entry.badge?.asImageBitmap()
    val badgeLeftOffsetFraction: Float = entry.badgeLeftOffsetFraction
    /** 绘制时的放大倍数，使不透明本体铺满图标直径（见 [RadialIconScale.bodyFill]）。 */
    val bodyFill: Float = RadialIconScale.bodyFill(iconBodyFraction(entry.icon))
}

/** 不透明本体包围盒的长边占位图长边的比例；同一位图只算一次。 */
private val iconBodyFractions = java.util.WeakHashMap<android.graphics.Bitmap, Float>()

private fun iconBodyFraction(bitmap: android.graphics.Bitmap): Float =
            synchronized(iconBodyFractions) {
                iconBodyFractions.getOrPut(bitmap) {
                    val width = bitmap.width
                    val height = bitmap.height
                    if (width <= 0 || height <= 0) return@getOrPut 1f
                    val pixels = IntArray(width * height)
                    runCatching { bitmap.getPixels(pixels, 0, width, 0, 0, width, height) }
                        .onFailure { return@getOrPut 1f }
                    val bounds =
                        io.github.zailinn.flymefreeform.apps.IconShapes.opaqueBounds(width, height) { x, y ->
                            pixels[y * width + x] ushr 24
                        } ?: return@getOrPut 1f
                    val body = maxOf(bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1)
                    body.toFloat() / maxOf(width, height)
                }
            }
