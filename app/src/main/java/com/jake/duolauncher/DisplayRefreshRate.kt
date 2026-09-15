package com.jake.duolauncher

import android.os.Build
import android.view.Window
import android.view.WindowManager

/** Prefer the display's best supported mode while leaving final scheduling to Android. */
internal fun Window.preferHighRefreshRate() {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else {
        @Suppress("DEPRECATION")
        (context.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    } ?: return
    val mode = display.supportedModes.maxByOrNull { it.refreshRate } ?: return
    if (mode.refreshRate <= 60f) return
    attributes = attributes.apply {
        preferredDisplayModeId = mode.modeId
        preferredRefreshRate = mode.refreshRate
    }
}
