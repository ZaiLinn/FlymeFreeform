package io.github.zailinn.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.zailinn.flymefreeform.apps.AppSelectionPolicy
import io.github.zailinn.flymefreeform.apps.IconShapes
import io.github.zailinn.flymefreeform.window.PreviewIconSizing
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.config.ModuleSettingsSnapshot
import io.github.zailinn.flymefreeform.config.PinnedTarget
import io.github.zailinn.flymefreeform.gesture.RadialArcPlan
import io.github.zailinn.flymefreeform.log.ModuleLog
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class RadialEntry(
    val target: PinnedTarget,
    val label: String,
    val icon: Bitmap,
    val badge: Bitmap? = null,
    val badgeLeftOffsetFraction: Float = 0f,
)

internal data class AppCatalogSnapshot(
    val radialApps: List<RadialEntry> = emptyList(),
    val panelApps: List<RadialEntry> = emptyList(),
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
    /**
     * 每条圆弧实际放了几项（第 1 条不含「更多」），与 [radialApps] 的顺序对应。
     * 自定义的内侧圆弧可能放不满，必须按这里分圈，不能按总数重新均分，否则会挤进下一圈。
     */
    val radialArcCounts: List<Int> = emptyList(),
    /**
     * 「更多」面板按需取图标。面板条目的图标可能还是 [PanelIconPlaceholder]：
     * 面板只为滚到眼前的格子请求图标，不必等数百个图标全部画完。
     */
    val panelIconLoader: PanelIconLoader? = null,
    /** 屏幕中央所选应用卡片用的高清图标；只对有桌面入口的应用（含单入口分身）生效。 */
    val previewIconLoader: PreviewIconLoader? = null,
) {
    /**
     * 目录是否需要重算。
     *
     * 必须包含 radialMaxItems：扇形条目数是截断结果的一部分，漏掉它的话
     * 改完上限要等下一次 pinnedTargets 变动才会生效（表现为「拖了滑条没反应」）。
     * 圆弧条数同理：它决定内侧圆弧能多放几项。
     */
    fun matches(settings: ModuleSettingsSnapshot): Boolean =
        this.settings.pinsSaved == settings.pinsSaved &&
            this.settings.pinnedTargets == settings.pinnedTargets &&
            this.settings.radialMaxItems == settings.radialMaxItems &&
            this.settings.effectiveRadialArcCount == settings.effectiveRadialArcCount &&
            this.settings.innerArcTargets == settings.innerArcTargets &&
            this.settings.radialFillSource == settings.radialFillSource &&
            this.settings.iconShape == settings.iconShape
}

/** 「更多」面板按需取图标：回调在图标线程执行；返回的函数用于取消尚未开始的请求。 */
internal fun interface PanelIconLoader {
    fun request(target: PinnedTarget, onLoaded: (Bitmap) -> Unit): () -> Unit
}

/** 按指定边长重新绘制的高清图标：回调在图标线程执行；返回的函数用于取消尚未开始的请求。 */
internal fun interface PreviewIconLoader {
    fun request(target: PinnedTarget, sizePx: Int, onLoaded: (Bitmap) -> Unit): () -> Unit
}

/** 面板图标尚未画好时的占位图；面板见到它就按需请求真图标。 */
internal object PanelIconPlaceholder {
    val bitmap: Bitmap by lazy { createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    fun isPlaceholder(bitmap: Bitmap): Boolean = bitmap === this.bitmap
}

/**
 * 目录查询只在后台执行；发布后的 Bitmap 与列表供手势热路径只读。
 *
 * 一次刷新分两步发布：
 * 1. 只为扇形上的条目（最多 18 项）取名称、图标并塑形，立即发布，扇形马上可用；
 * 2. 读出全部应用名称、排好「更多」面板的顺序后发布（图标未画好的用占位图，面板按需请求），
 *    随后在后台补齐全部图标，完成后再发布一次。
 * 桌面应用往往有数百个，若每次都先把全部图标画成位图，增减一个固定项也要等好几秒，
 * 开机后头几次呼出的扇形还会是空的。
 *
 * 应用列表只在 [refresh] 带 reloadApps 时重读；名称与图标按条目缓存，
 * 只有收到该包的安装 / 更新 / 卸载广播（[invalidatePackage]）才丢弃。
 */
internal class ColorOsAppCatalog(
    private val context: Context,
    private val executor: Executor,
    logger: (Int, String, Throwable?) -> Unit,
    private val publish: (AppCatalogSnapshot) -> Unit,
) {
    private val iconRenderer = ColorOsRadialIconRenderer(context.resources, logger)

    /** reloadApps 时递增；排队的任务被丢弃也不会漏掉重读。 */
    private val appsRevision = AtomicLong()

    /** 每次 [refresh] 递增；面板补图途中发现有新任务就让位，已画好的图标留在缓存里。 */
    private val requestGeneration = AtomicLong()
    private val invalidatedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 以下字段仅目录工作线程访问。
    private var appIndex: AppIndex? = null
    // 名称与原始图标也会被面板图标线程读写。
    private val labelCache = ConcurrentHashMap<PinnedTarget, String>()
    private val iconCache = ConcurrentHashMap<PinnedTarget, Bitmap>()
    private val iconRevision = io.github.zailinn.flymefreeform.apps.CacheRevision()
    private val previewRenderer = HighResolutionIconRenderer(ColorOsRadialIconRenderer(context.resources, logger))
    /**
     * 中央卡片的高清图标：缓存当前扇形的全部条目（每次加载目录后裁掉不在扇形里的），由 [prefetchPreviews] 在后台预取。
     * 按显示尺寸 1.5 倍绘制，默认大小每张约 0.9 MB，18 项约 16 MB；[PREVIEW_CACHE_SIZE] 只作安全上限。
     * 只缓存扇形项，「更多」面板的应用不缓存。
     */
    private val previewCache =
        object : LinkedHashMap<PinnedTarget, Bitmap>(PREVIEW_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PinnedTarget, Bitmap>?): Boolean = size > PREVIEW_CACHE_SIZE
        }
    private val shapedCache = HashMap<PinnedTarget, Bitmap>()
    private val nativeShapedCache = HashMap<PinnedTarget, Pair<Bitmap, Bitmap>>()
    private var lastPanel: List<RadialEntry> = emptyList()
    private var frequentCache: Pair<Long, List<String>>? = null

    /** 各图标缓存对应的外形；设置改变时清空。目录线程写，面板图标线程读。 */
    @Volatile private var iconShape = ModulePreferences.DEFAULT_ICON_SHAPE
    private var squareMask: Bitmap? = null

    @Volatile private var sidebarEntries: List<SelectableEntry> = emptyList()
    @Volatile private var foregroundPackage: String? = null

    /** 当前前台应用：补位时跳过它，固定项不受影响。 */
    fun updateForeground(packageName: String?) {
        foregroundPackage = packageName
    }

    /** 面板按需取图标专用：与目录线程分开，补图期间滚到的格子不用排在数百个图标之后。 */
    private val panelIconExecutor =
        Executors.newSingleThreadExecutor { task -> Thread(task, PANEL_ICON_THREAD_NAME).apply { isDaemon = true } }

    fun updateSidebarEntries(entries: List<SelectableEntry>, settings: ModuleSettingsSnapshot) {
        sidebarEntries = entries.toList()
        refresh(settings, reloadApps = false)
    }

    /** 包被安装、更新或卸载：丢弃它的名称与图标缓存，下次刷新重新读取。 */
    fun invalidatePackage(packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        invalidatedPackages += packageName
        appsRevision.incrementAndGet()
        iconRevision.invalidate {
            iconCache.keys.removeAll { it is PinnedTarget.App && it.packageName == packageName }
            // 只丢该包的高清图，其余保留，下次加载目录时补画缺的那张。
            synchronized(previewCache) { previewCache.keys.removeAll { it is PinnedTarget.App && it.packageName == packageName } }
        }
    }

    fun refresh(settings: ModuleSettingsSnapshot, reloadApps: Boolean = false) {
        if (reloadApps) appsRevision.incrementAndGet()
        val generation = requestGeneration.incrementAndGet()
        executor.execute { load(settings, generation) }
    }

    private fun load(settings: ModuleSettingsSnapshot, generation: Long) {
        val startedAt = SystemClock.uptimeMillis()
        dropInvalidatedPackages()
        val shape = ModulePreferences.coerceIconShape(settings.iconShape)
        if (shape != iconShape) {
            iconRevision.invalidate {
                iconShape = shape
                shapedCache.clear()
                nativeShapedCache.clear()
                iconCache.clear()
                synchronized(previewCache) { previewCache.clear() }
            }
        }
        val index = currentIndex() ?: return
        if (IconShapes.isSquare(shape)) ensureSquareMask(index)
        val natives = nativeEntries()
        fun exists(target: PinnedTarget) = target in index.activities || target in natives
        val recents = recentComponents().map { it.toTarget() }.filter(::exists).distinct()
        // 空位补位来源：「不补位」只让未自定义的内圈留空，默认圆弧仍按最近使用补位。
        val fillSource = ModulePreferences.coerceRadialFillSource(settings.radialFillSource)
        val ranked =
            if (fillSource == ModulePreferences.RADIAL_FILL_FREQUENT) {
                frequentPackages().mapNotNull(index.byPackage::get).filter(::exists)
            } else {
                recents
            }
        // 补位跳过正在前台的应用：用小窗再开自己只会切回全屏，白占一格。
        val foreground = foregroundPackage
        val fill = ranked.filterNot { it is PinnedTarget.App && it.packageName == foreground }
        val innerFill = if (fillSource == ModulePreferences.RADIAL_FILL_NONE) emptyList() else fill
        val alphabetical by lazy(LazyThreadSafetyMode.NONE) {
            val collator = Collator.getInstance(Locale.getDefault())
            index.order.sortedWith { first, second -> collator.compare(labelOf(first, index), labelOf(second, index)) }
        }

        val innerCapacities =
            RadialArcPlan.innerCapacities(settings.effectiveRadialArcCount, settings.radialMaxItems)
        // 内侧圆弧明确固定的条目只留给那一圈：外圈用最近使用补位时跳过它们。
        val reservedInner = settings.innerArcTargets.take(innerCapacities.size).flatten().toHashSet()
        val outerRecents = fill.filterNot { it in reservedInner }
        // 名称排序要读全部应用的名称；只有未保存固定项且最近使用不够时才用得到。
        val needsAlphabetical = !settings.pinsSaved && outerRecents.size < settings.radialMaxItems
        val outer =
            AppSelectionPolicy.radialItems(
                pinsSaved = settings.pinsSaved,
                // 固定项严格按设置页的顺序（应用、工具、快捷方式可混排）；超出名额时截掉的是排在最后的。
                availablePins = settings.pinnedTargets.filter(::exists),
                recent = outerRecents,
                all = if (needsAlphabetical) alphabetical.filterNot { it in reservedInner } else emptyList(),
                identity = { it },
                limit = settings.radialMaxItems,
            )
        val inner =
            AppSelectionPolicy.innerArcItems(
                capacities = innerCapacities,
                customPins = settings.innerArcTargets.map { it.filter(::exists) },
                customized = settings.innerArcTargets.map { it.isNotEmpty() },
                recent = innerFill,
                used = outer,
                identity = { it },
            )
        val radialTargets = outer + inner.flatten()
        val radialArcCounts = listOf(outer.size) + inner.map { it.size }
        val radial = radialTargets.mapNotNull { target -> radialEntry(target, index, natives) }
        val iconLoader = PanelIconLoader { target, onLoaded -> requestPanelIcon(target, index, onLoaded) }
        val previewLoader = PreviewIconLoader { target, size, onLoaded -> requestPreviewIcon(target, index, size, onLoaded) }
        if (radial.size != radialTargets.size) {
            // 个别图标读取失败时不能再按分圈结果摆放，交给扇形按总数重新均分。
            ModuleLog.debug(LOG_TAG) { "SYSTEM_CATALOG_ICON_MISSING_" + (radialTargets.size - radial.size) }
        }
        radial.forEach { entry ->
            entry.icon.prepareToDraw()
            entry.badge?.prepareToDraw()
        }
        val excluded = radialTargets.toHashSet()
        val counts = if (radial.size == radialTargets.size) radialArcCounts else emptyList()
        publish(AppCatalogSnapshot(radial, lastPanel.filterNot { it.target in excluded }, settings, counts, iconLoader, previewLoader))
        prefetchPreviews(radial.map { it.target }, index, settings)
        ModuleLog.debug(LOG_TAG) {
            "SYSTEM_CATALOG_RADIAL_" + (SystemClock.uptimeMillis() - startedAt) + "MS_" + radial.size
        }

        // 「更多」顶部与补位依据一致：选了「最常使用」时按常用排，其余按最近使用。
        val panelLead = if (fillSource == ModulePreferences.RADIAL_FILL_FREQUENT) ranked else recents
        if (lastPanel.isEmpty()) {
            // 开机后第一次：读全部名称前先发布一批常用 / 最近应用，「更多」不必空着等排序完成。
            val early =
                panelLead.filterNot { it in excluded }.mapNotNull { target ->
                    if (target !in index.activities) return@mapNotNull null
                    RadialEntry(target, labelOf(target, index), iconCache[target] ?: PanelIconPlaceholder.bitmap)
                }
            if (early.isNotEmpty()) publish(AppCatalogSnapshot(radial, early, settings, counts, iconLoader, previewLoader))
        }
        val panelTargets =
            AppSelectionPolicy.panelItems(
                recent = panelLead,
                all = alphabetical,
                excluded = excluded,
                identity = { it },
            )
        fun panelEntries(): List<RadialEntry> =
            panelTargets.mapNotNull { target ->
                if (target !in index.activities) return@mapNotNull natives[target]
                RadialEntry(target, labelOf(target, index), iconCache[target] ?: PanelIconPlaceholder.bitmap)
            }
        val ordered = panelEntries()
        lastPanel = ordered
        publish(AppCatalogSnapshot(radial, ordered, settings, counts, iconLoader, previewLoader))
        ModuleLog.debug(LOG_TAG) {
            "SYSTEM_CATALOG_PANEL_ORDER_" + (SystemClock.uptimeMillis() - startedAt) + "MS_" + ordered.size
        }

        // 后台补齐图标，下次打开面板不必再按需等待。
        var drawn = 0
        for (target in panelTargets) {
            if (requestGeneration.get() != generation) return
            if (iconCache.containsKey(target)) continue
            val info = index.activities[target] ?: continue
            rawIcon(target, info)?.prepareToDraw()
            drawn++
        }
        if (drawn == 0) return
        val panel = panelEntries()
        lastPanel = panel
        publish(AppCatalogSnapshot(radial, panel, settings, counts, iconLoader, previewLoader))
        ModuleLog.debug(LOG_TAG) {
            "SYSTEM_CATALOG_PANEL_" + (SystemClock.uptimeMillis() - startedAt) + "MS_" + panel.size
        }
    }

    private fun requestPanelIcon(
        target: PinnedTarget,
        index: AppIndex,
        onLoaded: (Bitmap) -> Unit,
    ): () -> Unit {
        val revision = iconRevision.current()
        if (index.revision != appsRevision.get()) return {}
        iconCache[target]?.let { cached ->
            onLoaded(cached)
            return {}
        }
        val info = index.activities[target] ?: return {}
        val cancelled = AtomicBoolean()
        panelIconExecutor.execute {
            // 格子已滚出视野的请求直接跳过，快速滑动时不必把途经的图标全部画完。
            if (cancelled.get() || !iconRevision.isCurrent(revision)) return@execute
            val icon = rawIcon(target, info, revision) ?: return@execute
            icon.prepareToDraw()
            if (!cancelled.get() && iconRevision.isCurrent(revision)) onLoaded(icon)
        }
        return { cancelled.set(true) }
    }

    /**
     * 中央卡片的高清图标：直接按目标边长绘制系统自适应图标（高密度资源），再按「图标形状」统一，
     * 不经扇形那份按系统图标尺寸画好的位图放大，避免发糊。没有桌面入口的侧边栏条目不处理。
     */
    private fun requestPreviewIcon(
        target: PinnedTarget,
        index: AppIndex,
        sizePx: Int,
        onLoaded: (Bitmap) -> Unit,
    ): () -> Unit {
        val revision = iconRevision.current()
        if (sizePx <= 0 || index.revision != appsRevision.get()) return {}
        val requestedShape = iconShape
        synchronized(previewCache) { previewCache[target] }?.takeIf { it.width >= sizePx }?.let { cached ->
            onLoaded(cached)
            return {}
        }
        val info = launcherInfoOf(target, index) ?: return {}
        val cancelled = AtomicBoolean()
        panelIconExecutor.execute {
            if (cancelled.get() || !iconRevision.isCurrent(revision)) return@execute
            val icon = previewRenderer.render(info, sizePx, requestedShape, squareMask) ?: return@execute
            icon.prepareToDraw()
            if (!iconRevision.commit(revision) { synchronized(previewCache) { previewCache[target] = icon } }) return@execute
            if (!cancelled.get() && iconRevision.isCurrent(revision)) onLoaded(icon)
        }
        return { cancelled.set(true) }
    }

    /**
     * 只画缺的：已缓存且尺寸够的跳过，所以前台切换引起的频繁加载只会补画新进扇形的那几项。
     * 裁掉不在本次扇形里的条目，内存随扇形条目数而定。中央预览关闭时不预取也不保留。
     */
    private fun prefetchPreviews(targets: List<PinnedTarget>, index: AppIndex, settings: ModuleSettingsSnapshot) {
        val keep = if (settings.radialCenterPreview) targets.toHashSet() else emptySet()
        synchronized(previewCache) { previewCache.keys.retainAll(keep) }
        if (!settings.radialCenterPreview) return
        val sizePx = PreviewIconSizing.renderSizeFor(settings.radialCenterSizeDp, context.resources.displayMetrics.density)
        val missing = synchronized(previewCache) { targets.filter { (previewCache[it]?.width ?: 0) < sizePx } }
        if (missing.isEmpty()) return
        ModuleLog.debug(LOG_TAG) { "SYSTEM_PREVIEW_PREFETCH_" + missing.size + "_OF_" + targets.size }
        missing.forEach { target -> requestPreviewIcon(target, index, sizePx) {} }
    }

    /** 应用的桌面入口；分身仅在包内只有一个入口时复用主用户入口（与扇形塑形同一规则）。 */
    private fun launcherInfoOf(target: PinnedTarget, index: AppIndex): LauncherActivityInfo? {
        index.activities[target]?.let { return it }
        if ((target as? PinnedTarget.Sidebar)?.kind != PinnedTarget.Kind.CloneApp) return null
        val packageName = sidebarEntries.firstOrNull { it.target == target }?.packageName ?: return null
        return index.activities.values.filter { it.componentName.packageName == packageName }.singleOrNull()
    }

    private fun dropInvalidatedPackages() {
        if (invalidatedPackages.isEmpty()) return
        val packages = invalidatedPackages.toHashSet()
        invalidatedPackages.removeAll(packages)
        fun stale(target: PinnedTarget) = target is PinnedTarget.App && target.packageName in packages
        labelCache.keys.removeAll(::stale)
        iconCache.keys.removeAll(::stale)
        shapedCache.keys.removeAll(::stale)
        synchronized(previewCache) { previewCache.keys.removeAll(::stale) }
        lastPanel = lastPanel.filterNot { stale(it.target) }
    }

    private fun currentIndex(): AppIndex? {
        val revision = appsRevision.get()
        appIndex?.takeIf { it.revision == revision }?.let { return it }
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val activities = LinkedHashMap<PinnedTarget, LauncherActivityInfo>()
        launcherApps.getActivityList(null, Process.myUserHandle()).forEach { info ->
            if (info.componentName.packageName != MODULE_PACKAGE) {
                activities.putIfAbsent(info.componentName.toTarget(), info)
            }
        }
        // 卸载的应用不在新列表里，顺手清掉它们的缓存。
        labelCache.keys.retainAll(activities.keys)
        iconCache.keys.retainAll(activities.keys)
        shapedCache.keys.retainAll(activities.keys)
        val byPackage = LinkedHashMap<String, PinnedTarget>()
        activities.keys.forEach { target ->
            if (target is PinnedTarget.App) byPackage.putIfAbsent(target.packageName, target)
        }
        return AppIndex(revision, activities, activities.keys.toList(), byPackage).also { appIndex = it }
    }

    private fun nativeEntries(): Map<PinnedTarget, RadialEntry> {
        val sidebar = sidebarEntries
        val result = LinkedHashMap<PinnedTarget, RadialEntry>()
        sidebar.forEach {
            result.putIfAbsent(
                it.target,
                RadialEntry(it.target, it.label, it.icon, it.badge, it.badge?.leftOffsetFraction() ?: 0f),
            )
        }
        nativeShapedCache.keys.retainAll(result.keys)
        return result
    }

    private fun labelOf(target: PinnedTarget, index: AppIndex): String =
        labelCache.getOrPut(target) {
            val info = index.activities.getValue(target)
            try {
                info.label?.toString()?.trim().orEmpty()
            } catch (_: RuntimeException) {
                ""
            }.ifEmpty { info.componentName.packageName }
        }

    private fun rawIcon(target: PinnedTarget, info: LauncherActivityInfo,
        revision: Long = iconRevision.current()): Bitmap? {
        if (!iconRevision.isCurrent(revision) || appIndex?.revision != appsRevision.get()) return null
        iconCache[target]?.let { return it }
        val shape = iconShape
        return try {
            val icon = IconShapes.apply(info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
                shape, isApp = true, squareMask = null)
            if (iconRevision.commit(revision) { iconCache[target] = icon }) icon else null
        } catch (_: RuntimeException) { null }
    }

    /** 「更多」面板与扇形塑形前共用的条目：按图标形状统一过的系统图标。 */
    private fun plainEntry(
        target: PinnedTarget,
        index: AppIndex,
        natives: Map<PinnedTarget, RadialEntry>,
    ): RadialEntry? {
        val info = index.activities[target] ?: return natives[target]
        val icon = rawIcon(target, info) ?: return null
        return RadialEntry(target, labelOf(target, index), icon)
    }

    /** 扇形条目：应用图标按系统形状重新塑形；侧边栏条目塑形克隆应用，其余沿用侧边栏给的图。 */
    private fun radialEntry(
        target: PinnedTarget,
        index: AppIndex,
        natives: Map<PinnedTarget, RadialEntry>,
    ): RadialEntry? {
        val plain = plainEntry(target, index, natives) ?: return null
        val info = index.activities[target]
        if (info != null) {
            val shaped = shapedCache[target] ?: shape(info)?.also { shapedCache[target] = it }
            return if (shaped == null) plain else plain.copy(icon = shaped)
        }
        nativeShapedCache[target]?.takeIf { it.first === plain.icon }?.let { return plain.copy(icon = it.second) }
        // 分身与主应用共用图标资源；包内只有一个入口时复用主用户的自适应图标，与普通应用同样原生塑形。
        val cloneInfo =
            if ((target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp) {
                val packageName = sidebarEntries.firstOrNull { it.target == target }?.packageName
                index.activities.values.filter { it.componentName.packageName == packageName }.singleOrNull()
            } else {
                null
            }
        val source =
            try {
                cloneInfo?.getIcon(context.resources.displayMetrics.densityDpi)
                    ?: plain.icon.toDrawable(context.resources)
            } catch (_: RuntimeException) {
                return plain
            }
        // 分身是应用图标；工具、快捷方式在方形模式下按应用图标模板裁成同样外形和大小。
        val isApp = (target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp
        val shaped = shapeDrawable(source, isApp) ?: return plain
        nativeShapedCache[target] = plain.icon to shaped
        return plain.copy(icon = shaped)
    }

    private fun shape(info: LauncherActivityInfo): Bitmap? =
        try {
            shapeDrawable(info.getIcon(context.resources.displayMetrics.densityDpi), isApp = true)
        } catch (_: RuntimeException) {
            null
        }

    /** 先由系统重绘自适应图标，再按「图标形状」统一（见 [IconShapes]）。 */
    private fun shapeDrawable(source: Drawable, isApp: Boolean): Bitmap? =
        try {
            IconShapes.apply(iconRenderer.shapedIcon(source).toBitmap(), iconShape, isApp, squareMask)
        } catch (_: RuntimeException) {
            null
        }

    /** 方形模式的外形模板：取前几个应用里第一个合格的系统图标，之后一直复用。 */
    private fun ensureSquareMask(index: AppIndex) {
        if (squareMask != null) return
        for (target in index.order.take(SQUARE_MASK_CANDIDATES)) {
            val info = index.activities[target] ?: continue
            val icon =
                try {
                    iconRenderer.shapedIcon(info.getIcon(context.resources.displayMetrics.densityDpi)).toBitmap()
                } catch (_: RuntimeException) {
                    continue
                }
            IconShapes.maskFrom(icon)?.let {
                squareMask = it
                return
            }
        }
    }

    private class AppIndex(
        val revision: Long,
        val activities: Map<PinnedTarget, LauncherActivityInfo>,
        val order: List<PinnedTarget>,
        /** 包名 → 该包第一个桌面入口；使用统计按包汇总，取这个入口代表整个应用。 */
        val byPackage: Map<String, PinnedTarget>,
    )

    /** 在目录工作线程测量可见范围；左侧只平移角标，避免翻转其中的数字。 */
    private fun Bitmap.leftOffsetFraction(): Float {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var right = 0
        pixels.forEachIndexed { index, pixel ->
            if (pixel ushr 24 != 0) {
                val x = index % width
                left = minOf(left, x)
                right = maxOf(right, x + 1)
            }
        }
        return if (left < right) (width - left - right).toFloat() / width else 0f
    }

    private fun ComponentName.toTarget() = PinnedTarget.App(packageName, className)

    /**
     * 近 [FREQUENT_WINDOW_MS] 内按前台时长排序的包名（降序），排除桌面与本模块。
     * system_server 读取使用统计无需额外授权；结果缓存 [FREQUENT_CACHE_MS]，避免每次刷新都读统计文件。
     * 统计服务尚未就绪时返回空列表且不缓存，下次刷新再试。
     */
    private fun frequentPackages(): List<String> {
        val now = SystemClock.uptimeMillis()
        frequentCache?.takeIf { now - it.first < FREQUENT_CACHE_MS }?.let { return it.second }
        val usageStats = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val end = System.currentTimeMillis()
        val stats =
            try {
                usageStats.queryAndAggregateUsageStats(end - FREQUENT_WINDOW_MS, end)
            } catch (_: RuntimeException) {
                return emptyList()
            }
        val home =
            try {
                context.packageManager
                    .resolveActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                        PackageManager.MATCH_DEFAULT_ONLY,
                    )?.activityInfo?.packageName
            } catch (_: RuntimeException) {
                null
            }
        val result =
            stats.values
                .filter { stat ->
                    stat.totalTimeInForeground > 0 &&
                        stat.packageName != home &&
                        stat.packageName != MODULE_PACKAGE
                }
                .sortedByDescending { it.totalTimeInForeground }
                .map { it.packageName }
        if (result.isNotEmpty()) frequentCache = now to result
        return result
    }

    private fun recentComponents(): List<ComponentName> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            activityManager.getRecentTasks(RECENT_LIMIT, ActivityManager.RECENT_WITH_EXCLUDED)
                .mapNotNull { task -> task.origActivity ?: task.baseIntent.component }
                .filterNot { component -> component.packageName == context.packageName }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun Drawable.toBitmap(): Bitmap {
        val systemIconSize =
            context.getSystemService(ActivityManager::class.java)?.launcherLargeIconSize ?: 0
        val targetSize =
            (systemIconSize.takeIf { it > 0 }
                ?: maxOf(intrinsicWidth, intrinsicHeight, 1)).coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        return createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }

    private companion object {
        const val PREVIEW_CACHE_SIZE = 48
        const val SQUARE_MASK_CANDIDATES = 30
        const val RECENT_LIMIT = 48
        const val FREQUENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val FREQUENT_CACHE_MS = 10L * 60 * 1000
        const val MODULE_PACKAGE = "io.github.zailinn.flymefreeform"
        const val PANEL_ICON_THREAD_NAME = "FlymeFreeformPanelIcons"
        const val LOG_TAG = "FlymeFreeform"
    }
}
