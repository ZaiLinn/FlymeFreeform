package io.github.zailinn.flymefreeform.window

import kotlin.math.ceil

/**
 * 以 1.5 倍线性像素重绘边缘，最后下采样到屏幕；单张最多 4 MiB。
 * 2 倍时默认尺寸单张约 2.8 MB，常驻于 system_server，1.5 倍降到约 1.6 MB，清晰度差别肉眼难辨。
 */
internal object PreviewIconSizing {
    const val MAX_RENDER_PX = 1024
    private const val RENDER_SCALE = 1.5
    fun renderSize(displayPx: Float): Int {
        if (!displayPx.isFinite() || displayPx <= 0f) return 1
        return ceil(displayPx.toDouble() * RENDER_SCALE).coerceIn(1.0, MAX_RENDER_PX.toDouble()).toInt()
    }
}
