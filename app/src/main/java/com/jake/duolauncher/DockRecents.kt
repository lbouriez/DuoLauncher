package com.jake.duolauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal fun visibleDockRecentIds(state: LauncherState, availableIds: Set<String>): List<String> =
    if (!state.showRecentApps) emptyList() else state.recentApps
        .filter { it !in state.dock && it in availableIds }.take(4)

/** Launch history displayed below pinned dock positions; it never changes the saved dock layout. */
@Composable
internal fun DockRecents(recentApps: List<AppEntry>, rowHeight: Float, iconSize: Float,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit) {
    if (recentApps.isEmpty()) return
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.width(26.dp).height(2.dp), color = Color.White.copy(alpha = .35f), shape = RoundedCornerShape(2.dp)) {}
    }
    recentApps.forEach { app ->
        val bounds = remember(app.id) { android.graphics.Rect() }
        val interaction = remember(app.id) { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) .92f else 1f, label = "recent press ${app.id}")
        Box(Modifier.fillMaxWidth().height(rowHeight.dp).semantics(mergeDescendants = true) { contentDescription = "${app.label} (Recent)" }
            .combinedClickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button,
                onClick = { onLaunch(app, bounds) }, onLongClick = { onActions(app) }), contentAlignment = Alignment.Center) {
            Image(app.icon.asImageBitmap(), null, Modifier.size(iconSize.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }.clip(RoundedCornerShape(11.dp)))
        }
    }
}
