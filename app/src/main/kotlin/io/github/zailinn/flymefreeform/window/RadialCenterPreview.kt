package io.github.zailinn.flymefreeform.window

/**
 * 滑选时屏幕正中显示的所选应用卡片：圆角底板、放大的图标，可选一行应用名。
 *
 * 卡片以安全区中心为中心；图标边长取设置值（默认 [ICON_DP]），但不超过安全区短边的 [MAX_ICON_FRACTION]，
 * 横屏、分屏等窄区域下随之缩小。应用名字号随图标按比例缩放（[labelSp]）。只做纯计算，便于单测。
 */
internal object RadialCenterPreview {
    const val ICON_DP = 96f
    const val MAX_ICON_FRACTION = 0.45f
    /** 图标为 [ICON_DP] 时的应用名字号；其余大小按比例，限制在 12–24 sp。 */
    const val LABEL_SP = 16f

    fun labelSp(iconDp: Float): Float = (LABEL_SP * iconDp / ICON_DP).coerceIn(12f, 24f)

    /** 应用名最大宽度占安全区宽度的比例，超出省略。 */
    const val MAX_LABEL_WIDTH_FRACTION = 0.6f

    private const val PADDING_FRACTION = 0.25f
    private const val LABEL_GAP_FRACTION = 0.15f
    private const val CORNER_FRACTION = 0.32f

    data class Layout(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cornerRadius: Float,
        val iconCenterX: Float,
        val iconCenterY: Float,
        val iconSize: Float,
        /** 应用名基线；不显示应用名时为 null。 */
        val labelBaseline: Float?,
    )

    /** 安全区内的图标边长（px）。 */
    fun iconSize(safeWidth: Float, safeHeight: Float, density: Float, iconDp: Float = ICON_DP): Float =
        minOf(iconDp * density, minOf(safeWidth, safeHeight) * MAX_ICON_FRACTION).coerceAtLeast(0f)

    /**
     * [labelWidth]/[labelAscent]/[labelDescent] 是已按最大宽度省略后的应用名度量（ascent 为负，与 Paint 一致）；
     * [labelWidth] 为 null 表示不显示应用名。
     */
    fun layout(
        width: Float,
        height: Float,
        insets: OverlaySafeInsets,
        density: Float,
        labelWidth: Float?,
        iconDp: Float = ICON_DP,
        labelAscent: Float = 0f,
        labelDescent: Float = 0f,
    ): Layout {
        val safeWidth = (width - insets.left - insets.right).coerceAtLeast(0f)
        val safeHeight = (height - insets.top - insets.bottom).coerceAtLeast(0f)
        val centerX = insets.left + safeWidth / 2f
        val centerY = insets.top + safeHeight / 2f
        val icon = iconSize(safeWidth, safeHeight, density, iconDp)
        val padding = icon * PADDING_FRACTION
        val labelHeight = if (labelWidth == null) 0f else labelDescent - labelAscent
        val gap = if (labelWidth == null) 0f else icon * LABEL_GAP_FRACTION
        val cardWidth = maxOf(icon, labelWidth ?: 0f) + padding * 2f
        val cardHeight = icon + gap + labelHeight + padding * 2f
        val top = centerY - cardHeight / 2f
        val iconCenterY = top + padding + icon / 2f
        return Layout(
            left = centerX - cardWidth / 2f,
            top = top,
            right = centerX + cardWidth / 2f,
            bottom = top + cardHeight,
            cornerRadius = icon * CORNER_FRACTION,
            iconCenterX = centerX,
            iconCenterY = iconCenterY,
            iconSize = icon,
            labelBaseline = if (labelWidth == null) null else iconCenterY + icon / 2f + gap - labelAscent,
        )
    }
}
