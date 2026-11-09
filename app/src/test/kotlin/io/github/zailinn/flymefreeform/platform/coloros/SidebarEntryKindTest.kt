package io.github.zailinn.flymefreeform.platform.coloros

import io.github.zailinn.flymefreeform.config.PinnedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidebarEntryKindTest {
    @Test
    fun cloneAppIsDistinctFromOrdinaryAndSpecialAppEntries() {
        assertEquals(PinnedTarget.Kind.CloneApp, SidebarEntryKind.fromNative(2, 0, true, 999, 0))
        assertEquals(PinnedTarget.Kind.AppEntry, SidebarEntryKind.fromNative(2, 11, false, 0, 0))
        assertNull(SidebarEntryKind.fromNative(2, 0, false, 0, 0))
        assertNull(SidebarEntryKind.fromNative(2, 11, true, 999, 0))
    }

    @Test
    fun cloneRequiresAnotherValidProfileAndApplicationType() {
        assertNull(SidebarEntryKind.fromNative(2, 0, true, 0, 0))
        assertNull(SidebarEntryKind.fromNative(2, 0, true, 999, 999))
        assertNull(SidebarEntryKind.fromNative(2, 0, true, 21_475, 0))
        assertNull(SidebarEntryKind.fromNative(1, 0, true, 999, 0))
    }

    @Test
    fun appFromAnotherUserIsCloneEvenWhenSidebarMisreports() {
        // 旧判断只认 999：非 999 的分身用户被侧边栏报成非分身。
        assertEquals(PinnedTarget.Kind.CloneApp, SidebarEntryKind.fromNative(2, 0, false, 11, 0))
        assertEquals(PinnedTarget.Kind.CloneApp, SidebarEntryKind.fromNative(2, 0, false, 999, 0))
        assertNull(SidebarEntryKind.fromNative(2, 0, false, 10, 10))
        // 只推断应用条目；工具、快捷方式和桌面快捷入口不因用户号变成分身。
        assertEquals(PinnedTarget.Kind.Tool, SidebarEntryKind.fromNative(0, 0, false, 999, 0))
        assertEquals(PinnedTarget.Kind.Shortcut, SidebarEntryKind.fromNative(1, 0, false, 999, 0))
        assertEquals(PinnedTarget.Kind.AppEntry, SidebarEntryKind.fromNative(2, 11, false, 999, 0))
    }
}
