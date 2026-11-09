package io.github.zailinn.flymefreeform.platform.coloros

import io.github.zailinn.flymefreeform.config.PinnedTarget

internal object SidebarEntryKind {
    private const val APP_TYPE = 2
    private const val LAUNCHER_SHORTCUT_CATEGORY = 11
    private const val MAX_USER_ID = 21_474

    /**
     * [nativeCloned] 是侧边栏 `EntryBean.isCloned()` 的结果。侧边栏只在系统版本判断通过时才向
     * `OplusMultiAppManager` 查询分身用户，否则只认 999；判断不符的版本组合会把非 999 的分身用户
     * 报成非分身。应用条目（type 2）由侧边栏按 `LauncherActivityInfo.getUser()` 填用户号，
     * 用户号不同于当前用户时同样视为分身，不再只依赖侧边栏的判断。是否真有该用户的入口由调用方核对。
     */
    fun fromNative(
        type: Int,
        category: Int,
        nativeCloned: Boolean,
        entryUserId: Int,
        ownerUserId: Int,
    ): PinnedTarget.Kind? {
        val otherUser = entryUserId in 1..MAX_USER_ID && entryUserId != ownerUserId
        val cloned = nativeCloned || (type == APP_TYPE && category != LAUNCHER_SHORTCUT_CATEGORY && otherUser)
        return when {
            type == 0 && !cloned -> PinnedTarget.Kind.Tool
            type == 1 && !cloned -> PinnedTarget.Kind.Shortcut
            type == APP_TYPE && !cloned && category == LAUNCHER_SHORTCUT_CATEGORY -> PinnedTarget.Kind.AppEntry
            type == APP_TYPE && cloned && category != LAUNCHER_SHORTCUT_CATEGORY && otherUser -> PinnedTarget.Kind.CloneApp
            else -> null
        }
    }
}
