package io.github.zailinn.flymefreeform.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTransferTest {
    @Test
    fun fullSnapshotSurvivesRoundTrip() {
        val settings =
            ModuleSettingsSnapshot(
                enabled = true,
                leftCornerEnabled = false,
                cornerTriggerRangeDp = 120,
                pinsSaved = true,
                pinnedTargets =
                    listOf(
                        PinnedTarget.App("com.first", "com.first.A"),
                        PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0),
                    ),
                outsideTapCloseMode = OutsideTapCloseMode.DoubleTap,
                handleSwipeUpToMiniEnabled = false,
                blacklistPackages = listOf("com.game.one", "com.game.two"),
                zoomLaunch = true,
                zoomLaunchCard = false,
                radialMultiArcEnabled = true,
                radialArcCount = 3,
                iconShape = 1,
                innerArcTargets =
                    listOf(
                        listOf(PinnedTarget.App("com.second", "com.second.B")),
                        emptyList(),
                        emptyList(),
                    ),
            )

        assertEquals(settings, SettingsTransfer.decode(SettingsTransfer.encode(settings)))
    }

    @Test
    fun defaultSnapshotSurvivesRoundTrip() {
        val settings = ModuleSettingsSnapshot()
        assertEquals(settings, SettingsTransfer.decode(SettingsTransfer.encode(settings)))
    }
}
