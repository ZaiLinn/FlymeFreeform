package io.github.zailinn.flymefreeform.platform.coloros

import java.util.Locale

/**
 * 「更多」面板两组的标题文案，随系统语言切换。
 *
 * 面板运行在智能侧边栏进程，取不到模块自身的字符串资源，因此在这里按语言给出；
 * 中文显示「收藏 / 最近」，其他语言显示「Favorites / Recent」。
 */
internal object PanelGroupLabels {
    fun pinned(locale: Locale = Locale.getDefault()): String =
        if (isChinese(locale)) "收藏" else "Favorites"

    fun recent(locale: Locale = Locale.getDefault()): String =
        if (isChinese(locale)) "最近" else "Recent"

    fun of(role: FavoriteGroupInjector.GroupRole, locale: Locale = Locale.getDefault()): String =
        when (role) {
            FavoriteGroupInjector.GroupRole.Pinned -> pinned(locale)
            FavoriteGroupInjector.GroupRole.Recent -> recent(locale)
        }

    private fun isChinese(locale: Locale): Boolean = locale.language == "zh"
}
