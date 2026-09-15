package com.jake.duolauncher

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Test

/** Uses the installed Google app; never submits a query or changes account data. */
class GoogleSearchIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).use { it.readBytes() }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun findInWindows(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        find(automation.rootInActiveWindow, predicate)?.let { return it }
        automation.windows.forEach { window -> find(window.root, predicate)?.let { return it } }
        return null
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!predicate()) { check(SystemClock.uptimeMillis() < deadline) { "Google search UI timed out" }; SystemClock.sleep(100) }
    }
    private fun imeShown() = shell("dumpsys input_method").toString(Charsets.UTF_8).contains("mInputShown=true")
    @Test fun searchButtonOpensEmptyGoogleSearchWithKeyboardAndPreservesHome() {
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        SetupExperience(instrumentation.targetContext).finish()
        shell("input touchscreen motionevent CANCEL 0 0")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var preferences: String? = null
            scenario.onActivity { preferences = it.getSharedPreferences("launcher", 0).getString("state", null) }
            await { findInWindows { it.contentDescription == "Search Google" } != null }
            val search = findInWindows { it.contentDescription == "Search Google" }!!
            val bounds = android.graphics.Rect().also(search::getBoundsInScreen)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            await { findInWindows { it.packageName == DiscoverClient.GOOGLE_PACKAGE && it.isEditable } != null }
            val field = findInWindows { it.packageName == DiscoverClient.GOOGLE_PACKAGE && it.isEditable }!!
            assertTrue("Google must open without a submitted query", field.text.isNullOrBlank() || field.isShowingHintText)
            await(::imeShown)
            shell("input keyevent KEYCODE_BACK")
            SystemClock.sleep(400)
            if (automation.rootInActiveWindow?.packageName == DiscoverClient.GOOGLE_PACKAGE) shell("input keyevent KEYCODE_BACK")
            // ActivityScenario is not the emulator's selected HOME app, so Android may
            // return to Pixel Launcher. Bring its activity back before checking state.
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { findInWindows { it.contentDescription == "Search Google" } != null }
            scenario.onActivity { assertEquals(preferences, it.getSharedPreferences("launcher", 0).getString("state", null)) }
        }
    }
}
