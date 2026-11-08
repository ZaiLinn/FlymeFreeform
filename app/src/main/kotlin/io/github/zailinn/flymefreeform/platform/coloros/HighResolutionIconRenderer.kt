package io.github.zailinn.flymefreeform.platform.coloros

import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.DisplayMetrics
import androidx.core.graphics.createBitmap
import io.github.zailinn.flymefreeform.apps.IconShapes
import io.github.zailinn.flymefreeform.log.ModuleLog

/**
 * 中央预览的高清图标，与扇形图标走同一条处理链：系统图标 → ColorOS 原生塑形 → [IconShapes]，只是按预览像素绘制。
 *
 * 曾直接绘制 AdaptiveIconDrawable、取不到高清时改读 APK 原始资源，跳过了 ColorOS 的塑形与深色模式处理；
 * 中央先显示扇形小图、再换成高清图时亮度和占比不同，看起来像闪一下变亮。
 * 只在图标工作线程使用；[shaper] 为独立实例，不与目录线程共用。
 */
internal class HighResolutionIconRenderer(private val shaper: ColorOsRadialIconRenderer) {
    fun render(info: LauncherActivityInfo, sizePx: Int, shape: Int, squareMask: Bitmap?): Bitmap? = runCatching {
        val source = info.getIcon(DisplayMetrics.DENSITY_XXXHIGH)
        val drawable = shaper.shapedIcon(source, sizePx)
        val raw = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(raw))
        ModuleLog.debug("FlymeFreeform") {
            "PREVIEW_ICON_SOURCE_" + info.componentName.packageName + "_" + source.javaClass.simpleName +
                "_" + source.intrinsicWidth + "x" + source.intrinsicHeight + "_TO" + sizePx +
                "_native" + (if (drawable !== source) 1 else 0)
        }
        // 与扇形 shapeDrawable 相同的参数，保证两张图外形一致。
        IconShapes.apply(raw, shape, isApp = true, squareMask = squareMask)
    }.getOrNull()
}
