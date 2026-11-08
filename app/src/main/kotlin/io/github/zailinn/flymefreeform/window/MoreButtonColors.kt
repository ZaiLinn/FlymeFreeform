package io.github.zailinn.flymefreeform.window

import kotlin.math.pow

/**
 * 自定义「更多」底板颜色时三个点的取色：在深、浅两种点色里取与底板对比度（WCAG 相对亮度）更高的一个。
 * 白色或浅色底板配黑点，深色底板配白点；两种点色与跟随系统时的深浅色取值一致。
 * 半透明底板按叠在黑色上计算：扇形背后是压暗的屏幕。
 */
internal object MoreButtonColors {
    const val DARK_DOT = 0xFF181818.toInt()
    const val LIGHT_DOT = 0xFFE9E9E9.toInt()

    fun dotFor(plate: Int): Int {
        val opaque = overBlack(plate)
        return if (contrast(opaque, DARK_DOT) >= contrast(opaque, LIGHT_DOT)) DARK_DOT else LIGHT_DOT
    }

    private fun overBlack(argb: Int): Int {
        val alpha = (argb ushr 24) / 255.0
        fun scaled(shift: Int) = (((argb shr shift) and 0xFF) * alpha).toInt() and 0xFF
        return (0xFF shl 24) or (scaled(16) shl 16) or (scaled(8) shl 8) or scaled(0)
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
