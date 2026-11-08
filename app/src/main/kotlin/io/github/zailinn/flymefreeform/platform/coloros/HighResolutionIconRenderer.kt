package io.github.zailinn.flymefreeform.platform.coloros

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import android.util.DisplayMetrics
import androidx.core.graphics.createBitmap
import io.github.zailinn.flymefreeform.apps.IconShapes
import io.github.zailinn.flymefreeform.log.ModuleLog

/** 独立于扇形的小图标缓存，直接按预览像素重绘可缩放资源。只在图标工作线程使用。 */
internal class HighResolutionIconRenderer(private val context: Context) {
    fun render(info: LauncherActivityInfo, sizePx: Int, shape: Int): Bitmap? = runCatching {
        var drawable = info.getIcon(DisplayMetrics.DENSITY_XXXHIGH)
        var originalResource = false
        // 某些主题加载器只给出小位图；放大不会补回细节，优先尝试 APK 的自适应或矢量源。
        val nativePixels = (drawable as? BitmapDrawable)?.bitmap?.let { maxOf(it.width, it.height) } ?: 0
        if (nativePixels in 1 until sizePx) {
            runCatching {
                val resources = context.packageManager.getResourcesForApplication(info.applicationInfo)
                val resource = info.activityInfo.iconResource
                val raw = resources.getDrawableForDensity(resource, DisplayMetrics.DENSITY_XXXHIGH, null)
                if (raw is AdaptiveIconDrawable || raw is VectorDrawable ||
                    (raw is BitmapDrawable && maxOf(raw.bitmap.width, raw.bitmap.height) > nativePixels)) {
                    drawable = raw
                    originalResource = true
                }
            }
        }
        val source = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        val raw = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        source.setBounds(0, 0, sizePx, sizePx)
        source.draw(Canvas(raw))
        ModuleLog.debug("FlymeFreeform") {
            "PREVIEW_ICON_SOURCE_" + info.componentName.packageName + "_" + drawable.javaClass.simpleName +
                "_" + drawable.intrinsicWidth + "x" + drawable.intrinsicHeight + "_TO" + sizePx +
                "_raw" + (if (originalResource) 1 else 0)
        }
        // 中央预览即使使用原始资源，也必须服从设置的圆形 / 圆角方形。
        IconShapes.apply(raw, shape, isApp = false, squareMask = null)
    }.getOrNull()
}
