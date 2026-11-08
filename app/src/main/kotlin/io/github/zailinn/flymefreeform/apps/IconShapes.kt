package io.github.zailinn.flymefreeform.apps

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import io.github.zailinn.flymefreeform.config.ModulePreferences

/**
 * 「图标形状」的统一处理。扇形（system_server）与模块应用列表共用，两边外观一致。
 *
 * - 方形：保持系统原样。应用图标是 ColorOS 画好的圆角方形（四周带透明留白），不做处理；
 *   工具、快捷方式等侧边栏条目裁成与应用图标一模一样的外形和大小——直接拿一个应用图标的
 *   alpha 作模板（[maskFrom]），把条目本体铺进模板里。圆形底的工具先用它边缘内侧的颜色补满四角。
 * - 圆形：所有图标先裁到本体（不透明部分的包围盒），统一缩放到格子的 [CONTENT_FRACTION]，再裁成圆。
 *
 * ColorOS 重绘自适应图标时不理会传入的外形路径，所以外形只能在这里统一做。
 */
internal object IconShapes {
    /** 圆形模式下图标本体占格子边长的比例，与系统应用图标原有的视觉大小相当。 */
    const val CONTENT_FRACTION = 0.86f

    /** 没有可用模板时，方形退回的圆角比例。 */
    const val SQUARE_CORNER_FRACTION = 0.25f

    fun isSquare(shape: Int): Boolean =
        ModulePreferences.coerceIconShape(shape) == ModulePreferences.ICON_SHAPE_SQUARE

    fun cornerFraction(shape: Int): Float = if (isSquare(shape)) SQUARE_CORNER_FRACTION else 0.5f

    /**
     * 按外形处理一个图标。
     *
     * @param isApp 应用（含分身）图标；方形模式下原样返回。
     * @param squareMask 方形模式下侧边栏条目使用的模板（来自 [maskFrom]）；为 null 时退回固定圆角。
     */
    fun apply(source: Bitmap, shape: Int, isApp: Boolean, squareMask: Bitmap?): Bitmap =
        when {
            !isSquare(shape) -> ownShape(source, ModulePreferences.ICON_SHAPE_CIRCLE)
            isApp -> source
            squareMask != null -> fitToMask(source, squareMask)
            else -> ownShape(source, ModulePreferences.ICON_SHAPE_SQUARE)
        }

    /**
     * 从一个系统应用图标取外形模板：只保留 alpha。
     * 只接受本体接近正方形、不是圆形、内部基本不透明的图标，否则返回 null，由调用方换下一个。
     */
    fun maskFrom(reference: Bitmap): Bitmap? {
        val width = reference.width
        val height = reference.height
        if (width < 16 || height < 16) return null
        val pixels = IntArray(width * height).also { reference.getPixels(it, 0, width, 0, 0, width, height) }
        val alpha = { x: Int, y: Int -> pixels[y * width + x] ushr 24 }
        val bounds = squareBounds(width, height, alpha) ?: return null
        val boxWidth = bounds.width()
        val boxHeight = bounds.height()
        val local = { x: Int, y: Int -> alpha(bounds.left + x, bounds.top + y) }
        if (isRoundBadge(boxWidth, boxHeight, local)) return null
        var opaque = 0
        for (y in 0 until boxHeight) for (x in 0 until boxWidth) if (local(x, y) >= OPAQUE_ALPHA) opaque++
        if (opaque < boxWidth * boxHeight * MASK_MIN_FILL) return null
        return reference.extractAlpha()
    }

    /** 把条目本体铺进模板的包围盒，再按模板的 alpha 裁切；输出与模板同尺寸。 */
    private fun fitToMask(source: Bitmap, mask: Bitmap): Bitmap {
        val maskPixels = IntArray(mask.width * mask.height)
        // ALPHA_8 取像素时颜色分量为 0，alpha 在高 8 位。
        mask.getPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)
        val maskBounds =
            squareBounds(mask.width, mask.height) { x, y -> maskPixels[y * mask.width + x] ushr 24 }
                ?: Rect(0, 0, mask.width, mask.height)
        val result = createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawBody(this, source, RectF(maskBounds), fillRoundBadge = true)
            drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        return result
    }

    /** 自绘外形：本体缩放到 [CONTENT_FRACTION] 后按 [shape] 裁切。 */
    private fun ownShape(source: Bitmap, shape: Int): Bitmap {
        val size = maxOf(source.width, source.height).coerceAtLeast(1)
        val inset = size * (1f - CONTENT_FRACTION) / 2f
        val contentSize = size - inset * 2f
        val content = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawBody(
            Canvas(content),
            source,
            RectF(inset, inset, inset + contentSize, inset + contentSize),
            fillRoundBadge = isSquare(shape),
        )
        val result = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        val radius = contentSize * cornerFraction(shape)
        Canvas(result).drawPath(
            Path().apply {
                addRoundRect(inset, inset, inset + contentSize, inset + contentSize, radius, radius, Path.Direction.CW)
            },
            paint,
        )
        content.recycle()
        return result
    }

    /** 把 [source] 的本体画进 [destination]；圆形底且 [fillRoundBadge] 时先用其底色铺满。 */
    private fun drawBody(canvas: Canvas, source: Bitmap, destination: RectF, fillRoundBadge: Boolean) {
        val width = source.width
        val height = source.height
        var body = Rect(0, 0, width, height)
        var round = false
        if (width >= 8 && height >= 8) {
            val pixels = IntArray(width * height).also { source.getPixels(it, 0, width, 0, 0, width, height) }
            val alpha = { x: Int, y: Int -> pixels[y * width + x] ushr 24 }
            squareBounds(width, height, alpha)?.let { bounds ->
                body = bounds
                round = isRoundBadge(bounds.width(), bounds.height()) { x, y -> alpha(bounds.left + x, bounds.top + y) }
            }
        }
        if (fillRoundBadge && round) {
            val color = source.getPixel(body.centerX(), body.top + (body.height() * BADGE_SAMPLE_INSET).toInt())
            canvas.drawRect(destination, Paint().apply { this.color = color or (0xFF shl 24) })
        }
        canvas.drawBitmap(source, body, destination, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** 不透明本体的包围盒；本体不接近正方形（异形旧式图标）时返回 null，避免拉伸。 */
    private fun squareBounds(width: Int, height: Int, alphaAt: (Int, Int) -> Int): Rect? {
        val (left, top, right, bottom) = opaqueBounds(width, height, alphaAt) ?: return null
        val boxWidth = right - left + 1
        val boxHeight = bottom - top + 1
        if (boxWidth < 8 || boxHeight < 8 || boxWidth.toFloat() / boxHeight !in 0.92f..1.08f) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    /** 不透明像素的包围盒（含端点）：left, top, right, bottom；全透明返回 null。 */
    fun opaqueBounds(width: Int, height: Int, alphaAt: (Int, Int) -> Int): List<Int>? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (alphaAt(x, y) > TRANSPARENT_ALPHA) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else listOf(left, top, right, bottom)
    }

    /**
     * 判断本体是否为圆形底：上下左右边缘内侧不透明，而对角线上圆外、圆角方形内的点透明。
     * 取样点 [ROUND_CORNER_SAMPLE] 落在圆外，但在圆角半径不超过 0.35 的圆角方形之内，
     * 因此 ColorOS 的圆角方形应用图标不会被误判为圆形。只看 alpha，便于脱离 Bitmap 单测。
     */
    fun isRoundBadge(width: Int, height: Int, alphaAt: (Int, Int) -> Int): Boolean {
        val cornerX = (width * ROUND_CORNER_SAMPLE).toInt()
        val cornerY = (height * ROUND_CORNER_SAMPLE).toInt()
        val corners =
            listOf(
                cornerX to cornerY,
                width - 1 - cornerX to cornerY,
                cornerX to height - 1 - cornerY,
                width - 1 - cornerX to height - 1 - cornerY,
            )
        if (corners.any { (x, y) -> alphaAt(x, y) > TRANSPARENT_ALPHA }) return false
        val insetX = (width * BADGE_SAMPLE_INSET).toInt()
        val insetY = (height * BADGE_SAMPLE_INSET).toInt()
        val edges =
            listOf(
                width / 2 to insetY,
                width / 2 to height - 1 - insetY,
                insetX to height / 2,
                width - 1 - insetX to height / 2,
            )
        return edges.all { (x, y) -> alphaAt(x, y) >= OPAQUE_ALPHA }
    }

    private const val ROUND_CORNER_SAMPLE = 0.13f
    private const val BADGE_SAMPLE_INSET = 0.08f
    private const val MASK_MIN_FILL = 0.8f
    private const val TRANSPARENT_ALPHA = 16
    private const val OPAQUE_ALPHA = 224
}
