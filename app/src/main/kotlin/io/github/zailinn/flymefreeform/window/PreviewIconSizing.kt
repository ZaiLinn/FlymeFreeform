package io.github.zailinn.flymefreeform.window

import kotlin.math.ceil

/** 以 2 倍线性像素重绘边缘，最后下采样到屏幕；单张最多 4 MiB。 */
internal object PreviewIconSizing {
    const val MAX_RENDER_PX = 1024
    fun renderSize(displayPx: Float): Int {
        if (!displayPx.isFinite() || displayPx <= 0f) return 1
        return ceil(displayPx.toDouble() * 2).coerceIn(1.0, MAX_RENDER_PX.toDouble()).toInt()
    }
}
