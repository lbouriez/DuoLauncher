package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class DockRecentsTest {
    @Test fun visibleRecentsExcludePinnedAndLimitToFour() {
        val apps = (1..6).map { "app$it" }.toSet()
        val state = LauncherState(dock = listOf("app1", null, null, null), recentApps = (1..6).map { "app$it" })
        assertEquals(listOf("app2", "app3", "app4", "app5"), visibleDockRecentIds(state, apps))
    }

    @Test fun disabledRecentsAreNotRendered() {
        assertEquals(emptyList<String>(), visibleDockRecentIds(LauncherState(recentApps = listOf("app"), showRecentApps = false), setOf("app")))
    }
}
