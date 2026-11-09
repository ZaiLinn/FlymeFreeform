package io.github.zailinn.flymefreeform.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import androidx.core.graphics.createBitmap
import io.github.zailinn.flymefreeform.config.PinnedTarget
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AppListStatus { Loading, Ready, Failed }

internal data class AppListState(
    val entries: List<SelectableEntry> = emptyList(),
    val status: AppListStatus = AppListStatus.Loading,
)

/** 设置页应用条目的图标占位：列表只带名称，图标由 [LauncherAppRepository.icons] 按需加载。 */
internal object AppIconPlaceholder {
    val bitmap: Bitmap by lazy { createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    fun isPlaceholder(bitmap: Bitmap): Boolean = bitmap === this.bitmap
}

/**
 * 设置页的桌面应用列表。
 *
 * 列表只保存组件与名称，图标用 [AppIconPlaceholder] 占位，由页面按可见行向 [icons] 请求，
 * 结果放进有字节上限的缓存；数百个应用不再全部画成位图常驻内存，第一次也不必等图标画完才显示。
 * 包变化只重读该包的入口与名称；切换系统语言时丢弃全部名称重读。
 */
internal class LauncherAppRepository(private val context: Context) {
    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val mutableState = MutableStateFlow(AppListState())
    val state: StateFlow<AppListState> = mutableState.asStateFlow()
    private val mutableSquareMask = MutableStateFlow<Bitmap?>(null)

    /** 方形模式下侧边栏条目使用的外形模板，取自前几个应用的图标。 */
    val squareMask: StateFlow<Bitmap?> = mutableSquareMask.asStateFlow()

    /** 组件 → 入口信息；图标线程按它取图，工作线程写。 */
    private val activities = ConcurrentHashMap<PinnedTarget.App, LauncherActivityInfo>()
    private val dirtyPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val fullReload = AtomicBoolean(true)
    private val labelsInvalidated = AtomicBoolean()

    // 以下字段仅工作线程访问。
    private val labels = HashMap<PinnedTarget.App, String>()
    private val main = Handler(Looper.getMainLooper())

    val icons = AppIconLoader()

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val packageName = intent?.data?.schemeSpecificPart
                if (packageName.isNullOrEmpty()) {
                    fullReload.set(true)
                } else {
                    dirtyPackages += packageName
                    icons.invalidatePackages(setOf(packageName))
                }
                schedule()
            }
        }

    private val localeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_LOCALE_CHANGED) return
                labelsInvalidated.set(true)
                schedule()
            }
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        // Repository 由 Application 持有到进程结束，因此接收器也只注册一次。
        context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(localeReceiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    }

    /** 全量重读；读取失败后的「重试」也走这里。 */
    fun refresh() {
        fullReload.set(true)
        schedule()
    }

    private fun schedule() {
        // 排队任务被丢弃也不会漏掉变化：待办记在上面的标记里，由下一次执行统一处理。
        worker.execute { load() }
    }

    private fun load() {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (launcherApps == null) {
            mutableState.value = mutableState.value.copy(status = AppListStatus.Failed)
            return
        }
        val user = Process.myUserHandle()
        val changed = dirtyPackages.toHashSet().also(dirtyPackages::removeAll)
        if (labelsInvalidated.getAndSet(false)) labels.clear()
        labels.keys.removeAll { it.packageName in changed }
        val full = fullReload.getAndSet(false) || activities.isEmpty()
        try {
            if (full) {
                val fresh = HashMap<PinnedTarget.App, LauncherActivityInfo>()
                launcherApps.getActivityList(null, user).forEach { info -> put(fresh, info) }
                activities.keys.retainAll(fresh.keys)
                activities.putAll(fresh)
            } else {
                for (packageName in changed) {
                    activities.keys.removeAll { it.packageName == packageName }
                    launcherApps.getActivityList(packageName, user).forEach { info -> put(activities, info) }
                }
            }
        } catch (_: RuntimeException) {
            // 读取失败：保留旧列表，变化留待下次；从未读到过列表时页面显示失败与重试。
            dirtyPackages += changed
            fullReload.set(true)
            mutableState.value = mutableState.value.copy(status = AppListStatus.Failed)
            return
        }
        labels.keys.retainAll(activities.keys)
        val collator = Collator.getInstance(Locale.getDefault())
        val entries =
            activities.entries
                .map { (target, info) ->
                    val label = labels.getOrPut(target) { labelOf(info) }
                    collator.getCollationKey(label) to
                        SelectableEntry(target, label, target.packageName, AppIconPlaceholder.bitmap)
                }
                .sortedWith { first, second -> first.first.compareTo(second.first) }
                .map { it.second }
        mutableState.value = AppListState(entries, AppListStatus.Ready)
        // 入口信息已换成新版本：再作废一次，广播后、重读前按旧入口画出的图不留，可见行据新版本号重新请求。
        if (changed.isNotEmpty()) main.post { icons.invalidatePackages(changed) }
        // 模板只在全量读取（启动、重试）或还没有时重取；单个包变化不影响前几个应用的外形。
        if (full || mutableSquareMask.value == null) mutableSquareMask.value = squareMaskOf(entries)
    }

    private fun put(into: MutableMap<PinnedTarget.App, LauncherActivityInfo>, info: LauncherActivityInfo) {
        val component = info.componentName
        if (component.packageName == context.packageName) return
        into.putIfAbsent(PinnedTarget.App(component.packageName, component.className), info)
    }

    private fun labelOf(info: LauncherActivityInfo): String =
        try {
            info.label?.toString()?.trim().orEmpty()
        } catch (_: RuntimeException) {
            ""
        }.ifEmpty { info.componentName.packageName }

    /** 前几个应用里第一个合格的系统图标；只取模板，原图不留。 */
    private fun squareMaskOf(entries: List<SelectableEntry>): Bitmap? =
        entries.asSequence().take(SQUARE_MASK_CANDIDATES).firstNotNullOfOrNull { entry ->
            val info = activities[entry.target] ?: return@firstNotNullOfOrNull null
            rawIcon(info)?.let(IconShapes::maskFrom)
        }

    private fun rawIcon(info: LauncherActivityInfo): Bitmap? =
        try {
            info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap()
        } catch (_: RuntimeException) {
            null
        }

    private fun Drawable.toBitmap(): Bitmap {
        val targetSize = (ICON_CACHE_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        val result = createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return result
    }

    /**
     * 按需加载设置页的应用图标，结果已按「图标形状」处理（应用图标不需要方形模板）。
     * 后请求的先画（快速滚动时眼前的行先出图），行离开视野即取消；回调在主线程。
     *
     * 包变化时 [version] 递增：可见行以它为键重新取图（其余包命中缓存，不闪），
     * 作废前开始的任务结果既不进缓存也不回调。[version] 只在主线程写。
     */
    // 内部类的稳定性 Compose 编译器推断不出，会在调用处运行时读 `$stable`，而 R8 删掉了该字段（1.4.9 打开即闪退，
    // NoSuchFieldError）。标 @Stable：变化都经快照状态 [version] 通知，编译期即按稳定处理，不再运行时读取。
    @Stable
    inner class AppIconLoader {
        private val cache =
            object : LruCache<Key, Bitmap>(ICON_CACHE_BYTES) {
                override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
            }
        private val jobs = LinkedBlockingDeque<Runnable>()
        private val executor =
            Executors.newSingleThreadExecutor { task -> Thread(task, ICON_THREAD_NAME).apply { isDaemon = true } }

        /** 包变化时递增；旧代数的结果不写缓存、不回调。 */
        private val generation = CacheRevision()

        /** 页面据此重新请求图标；与 [generation] 同步递增。 */
        var version by mutableLongStateOf(0L)
            private set

        fun cached(target: PinnedTarget.App, shape: Int): Bitmap? = cache.get(Key(target, shape))

        fun request(target: PinnedTarget.App, shape: Int, onLoaded: (Bitmap) -> Unit): () -> Unit {
            val key = Key(target, shape)
            cache.get(key)?.let {
                onLoaded(it)
                return {}
            }
            val cancelled = AtomicBoolean()
            val requested = generation.current()
            jobs.offerFirst(
                Runnable {
                    if (cancelled.get()) return@Runnable
                    val icon =
                        cache.get(key) ?: activities[target]?.let(::rawIcon)?.let { raw ->
                            IconShapes.apply(raw, shape, isApp = true, squareMask = null)
                        } ?: return@Runnable
                    icon.prepareToDraw()
                    if (!generation.commit(requested) { cache.put(key, icon) }) return@Runnable
                    main.post { if (!cancelled.get() && generation.isCurrent(requested)) onLoaded(icon) }
                },
            )
            executor.execute { jobs.pollFirst()?.run() }
            return { cancelled.set(true) }
        }

        /** 主线程调用。 */
        fun invalidatePackages(packageNames: Set<String>) {
            generation.invalidate {
                cache.snapshot().keys.filter { it.target.packageName in packageNames }.forEach { cache.remove(it) }
            }
            version++
        }
    }

    data class Key(val target: PinnedTarget.App, val shape: Int)

    private companion object {
        const val THREAD_NAME = "FlymeFreeform-AppCatalog"
        const val ICON_THREAD_NAME = "FlymeFreeform-AppIcons"
        const val ICON_CACHE_SIZE_DP = 48f
        const val SQUARE_MASK_CANDIDATES = 30

        /** 48dp 图标在常见密度下每张约 80–120 KB，约能放一百多个，够几屏来回滚动。 */
        const val ICON_CACHE_BYTES = 12 * 1024 * 1024
    }
}
