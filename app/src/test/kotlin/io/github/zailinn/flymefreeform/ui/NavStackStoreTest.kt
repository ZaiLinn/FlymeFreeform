package io.github.zailinn.flymefreeform.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavStackStoreTest {
    @Test fun roundTripKeepsWholeStack() {
        val stack = listOf(AppRoute.Settings, AppRoute.ZoomOptions, AppRoute.Blacklist, AppRoute.BlacklistPicker)
        assertEquals(stack, NavStackStore.decodeStack(NavStackStore.encodeStack(stack)))
        val radial = listOf(AppRoute.Settings, AppRoute.RadialSettings, AppRoute.PinnedApps)
        assertEquals(radial, NavStackStore.decodeStack(NavStackStore.encodeStack(radial)))
    }

    @Test fun legacyEnvironmentScopeRestoresAsZoomOptions() {
        assertEquals(
            listOf(AppRoute.Settings, AppRoute.ZoomOptions),
            NavStackStore.decodeStack(NavStackStore.encodeStack(listOf(AppRoute.Settings, AppRoute.EnvironmentScope))),
        )
    }

    @Test fun missingOrBrokenRecordFallsBackToHome() {
        val home = listOf(AppRoute.Settings)
        assertEquals(home, NavStackStore.decodeStack(null))
        assertEquals(home, NavStackStore.decodeStack(""))
        assertEquals(home, NavStackStore.decodeStack("radial,settings"))
        assertEquals(listOf(AppRoute.Settings, AppRoute.RadialSettings), NavStackStore.decodeStack("settings,unknown,radial"))
    }
}
