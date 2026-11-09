package io.github.zailinn.flymefreeform.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.zailinn.flymefreeform.apps.AppIconPlaceholder
import io.github.zailinn.flymefreeform.apps.IconShapes
import io.github.zailinn.flymefreeform.apps.LauncherAppRepository
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.config.PinnedTarget

/** 当前「图标形状」设置，由导航根节点提供。 */
internal val LocalIconShape = compositionLocalOf { ModulePreferences.DEFAULT_ICON_SHAPE }

/** 方形模式下侧边栏条目使用的外形模板（取自某个应用图标），由导航根节点提供。 */
internal val LocalSquareIconMask = compositionLocalOf<Bitmap?> { null }

/** 设置页应用图标的按需加载器，由导航根节点提供。 */
internal val LocalAppIcons = staticCompositionLocalOf<LauncherAppRepository.AppIconLoader?> { null }

/** 应用（含分身）图标；方形模式下保持系统原样，其余条目按应用图标外形裁切。 */
internal val SelectableEntry.isAppIcon: Boolean
    get() = target is PinnedTarget.App || (target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp

/**
 * 列表行的图标：应用条目只带占位图，按需向 [LocalAppIcons] 请求（已按外形处理）；
 * 侧边栏条目自带图标，经 [rememberShapedIcon] 处理。图标未就绪时返回 null，调用方留空位。
 */
@Composable
internal fun rememberEntryIcon(entry: SelectableEntry, isApp: Boolean = entry.isAppIcon): ImageBitmap? {
    if (!AppIconPlaceholder.isPlaceholder(entry.icon)) return rememberShapedIcon(entry.icon, isApp)
    val target = entry.target as? PinnedTarget.App
    val loader = LocalAppIcons.current
    val shape = LocalIconShape.current
    // 包更新后版本号递增：重新取图，不再持有旧位图。
    val version = loader?.version ?: 0L
    var bitmap by remember(target, shape, loader, version) { mutableStateOf(target?.let { loader?.cached(it, shape) }) }
    DisposableEffect(target, shape, loader, version) {
        val cancel = if (bitmap == null && target != null && loader != null) loader.request(target, shape) { bitmap = it } else null
        onDispose { cancel?.invoke() }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

/**
 * 按当前图标形状处理过的图标；与扇形使用同一份 [IconShapes] 逻辑。
 * 结果放进应用进程共用的有上限缓存，行滚出列表再回来不必重新扫描像素、生成位图。
 */
@Composable
internal fun rememberShapedIcon(bitmap: Bitmap, isApp: Boolean): ImageBitmap {
    val shape = LocalIconShape.current
    val mask = LocalSquareIconMask.current
    return remember(bitmap, shape, isApp, mask) { ShapedIconCache.get(bitmap, shape, isApp, mask).asImageBitmap() }
}

/** 按源图、外形与模板区分；源图与模板按对象身份比较。 */
private object ShapedIconCache {
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val cache =
        object : LruCache<Key, Bitmap>(MAX_BYTES) {
            override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
        }

    fun get(source: Bitmap, shape: Int, isApp: Boolean, mask: Bitmap?): Bitmap {
        val key = Key(source, shape, isApp, mask)
        cache.get(key)?.let { return it }
        val shaped = IconShapes.apply(source, shape, isApp, mask)
        // 原样返回的（方形模式下的应用图标）不必缓存。
        if (shaped !== source) cache.put(key, shaped)
        return shaped
    }

    private class Key(val source: Bitmap, val shape: Int, val isApp: Boolean, val mask: Bitmap?) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.source === source && other.shape == shape && other.isApp == isApp && other.mask === mask

        override fun hashCode(): Int =
            ((System.identityHashCode(source) * 31 + shape) * 31 + isApp.hashCode()) * 31 + System.identityHashCode(mask)
    }
}
