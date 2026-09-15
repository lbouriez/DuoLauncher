package com.jake.duolauncher

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

/** A focusable window for dock selection, kept separate from Discover's embedded host. */
class DockAppPickerActivity : ComponentActivity() {
    private val model: LauncherModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        overridePendingTransition(0, 0)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val verticalStatus = JSONObject(
            getSharedPreferences("launcher", 0).getString("state", "{}") ?: "{}"
        ).optBoolean("verticalStatus", true)
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (verticalStatus) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
        }
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        if (slot !in 0..3) { finish(); return }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            DuoTheme {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f))
                    .pointerInput(Unit) { detectTapGestures { close() } }) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.88f)
                            .pointerInput(Unit) { detectTapGestures { } },
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Box(Modifier.padding(top = 28.dp)) {
                            AppPicker(
                                apps = state.apps,
                                dockSlot = slot,
                                onSelect = { app ->
                                    if (canPlaceInDock(state.layout, app.id)) complete(slot, app.id)
                                },
                                onClear = { complete(slot, null) },
                                onLongClick = { app ->
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${app.packageName}")))
                                },
                                canSelect = { canPlaceInDock(state.layout, it.id) },
                                blockedHint = if (state.dock.none { it == null })
                                    "Dock full • Move an app out first" else null,
                                heightFraction = 1f,
                                requestSearchFocus = true,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun complete(slot: Int, appId: String?) {
        LiveDiscover.owner.get()?.applyDockPickerSelection(slot, appId) ?: run {
            if (appId == null) model.removePlacement(DropTarget.Dock(slot))
            else if (canPlaceInDock(model.state.value.layout, appId))
                model.applyDrop(appId, DropTarget.Dock(slot))
        }
        close()
    }

    private fun close() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_SLOT = "duo_dock_slot"
    }
}
