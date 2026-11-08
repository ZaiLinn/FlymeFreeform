package io.github.zailinn.flymefreeform.config

/**
 * 小窗关闭方式；存储值属于已发布配置格式，不得改作其他含义。
 *
 * [CaptionDoubleTap] 只认底部横条自身的双击，窗外点击不做处理。
 */
internal enum class OutsideTapCloseMode(val storedValue: Int) {
    Disabled(0),
    SingleTap(1),
    DoubleTap(2),
    CaptionDoubleTap(3),
    ;

    /** 是否由窗外点击触发关闭；只有它为真时才扩大横条的触控区域去接收窗外点击。 */
    val closesOnOutsideTap: Boolean
        get() = this == SingleTap || this == DoubleTap

    companion object {
        fun fromStoredValue(value: Int): OutsideTapCloseMode =
            entries.firstOrNull { it.storedValue == value } ?: Disabled
    }
}
