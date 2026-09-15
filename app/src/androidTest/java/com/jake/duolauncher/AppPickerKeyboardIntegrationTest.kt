package com.jake.duolauncher

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the real IME path while Google's persistent Discover host is enabled. */
class AppPickerKeyboardIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun findInWindows(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        find(automation.rootInActiveWindow, predicate)?.let { return it }
        automation.windows.forEach { window -> find(window.root, predicate)?.let { return it } }
        return null
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(100)
        }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty) { "Cannot tap an empty accessibility bound" }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun imeShown() = shell("dumpsys input_method").contains("mInputShown=true")

    @Test fun emptyDockPickerSearchShowsKeyboardWithDiscoverEnabled() {
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = instrumentation.targetContext
        val launcherPackage = context.packageName
        val previousHome = shell("cmd role get-role-holders android.app.role.HOME")
            .lineSequence().firstOrNull().orEmpty()
        val previousHardwareIme = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        shell("am force-stop com.google.android.inputmethod.latin")
        shell("cmd role add-role-holder android.app.role.HOME $launcherPackage 0")
        SetupExperience(context).finish()
        var pickerBaseline: HomeLayout? = null
        var hadState = false
        var savedState: String? = null
        var hadInitialized = false
        var savedInitialized = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: LauncherModel
                scenario.onActivity { model = ViewModelProvider(it)[LauncherModel::class.java] }
                await("Launcher model did not load") { !model.state.value.loading }
                scenario.onActivity { activity ->
                    val preferences = activity.getSharedPreferences("launcher", 0)
                    hadState = preferences.contains("state")
                    savedState = preferences.getString("state", null)
                    hadInitialized = preferences.contains("initialized")
                    savedInitialized = preferences.getBoolean("initialized", false)
                    model.setDock(0, null)
                }
                await("Dock slot did not become empty") { model.state.value.dock[0] == null }
                scenario.onActivity { pickerBaseline = model.state.value.layout }
                await("Discover host did not attach") { LiveDiscover.host.get() != null }
                val add = awaitNode("Choose dock app 1")
                tap(add)
                val field = awaitEditable(launcherPackage)
                if (!imeShown()) tap(field)
                await("App picker search did not open an IME session", ::imeShown)
                shell("input text chrome")
                await("App picker search did not accept input and filter the app list") {
                    findInWindows { it.packageName?.toString() == launcherPackage && it.isEditable &&
                        it.text?.toString() == "chrome" } != null &&
                        findInWindows { it.contentDescription?.toString() == "Choose Chrome" } != null &&
                        findInWindows { it.contentDescription?.toString() == "Choose Calendar" } == null
                }
                scenario.onActivity {
                    assertEquals(pickerBaseline, model.state.value.layout)
                }
                shell("input keyevent BACK")
                await("Keyboard did not hide after Back") { !imeShown() }
                shell("input keyevent BACK")
                await("Dock app picker did not close") { findInWindows { it.isEditable } == null }
            }
        } finally {
            val editor = context.getSharedPreferences("launcher", 0).edit()
            if (hadState) editor.putString("state", savedState) else editor.remove("state")
            if (hadInitialized) {
                editor.putBoolean("initialized", savedInitialized)
            } else {
                editor.remove("initialized")
            }
            check(editor.commit()) { "Could not restore launcher preferences after keyboard test" }
            if (previousHardwareIme == "null" || previousHardwareIme.isEmpty()) {
                shell("settings delete secure show_ime_with_hard_keyboard")
            } else {
                shell("settings put secure show_ime_with_hard_keyboard $previousHardwareIme")
            }
            val cleanupHome = previousHome.takeIf { it.isNotEmpty() && it != launcherPackage }
                ?: "com.google.android.apps.nexuslauncher"
            shell("cmd role add-role-holder android.app.role.HOME $cleanupHome 0")
            if (previousHome.isNotEmpty()) shell("cmd role add-role-holder android.app.role.HOME $previousHome 0")
        }
    }

    private fun awaitNode(description: String): AccessibilityNodeInfo {
        await("$description was not available") {
            findInWindows { it.contentDescription?.toString() == description } != null
        }
        return checkNotNull(findInWindows { it.contentDescription?.toString() == description })
    }

    private fun awaitEditable(packageName: String): AccessibilityNodeInfo {
        await("App picker search field was not available") {
            findInWindows { it.packageName?.toString() == packageName && it.isEditable } != null
        }
        return checkNotNull(findInWindows { it.packageName?.toString() == packageName && it.isEditable })
    }
}
