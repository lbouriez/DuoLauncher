package com.jake.duolauncher

import android.app.role.RoleManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun DiscoverContent(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val message = LiveDiscover.message.value
    // Google grants its launcher overlay only to the active Home app. A signed fork is a
    // separate Android package from the development APK, so make that prerequisite explicit
    // instead of implying that release signing or the Google app itself is broken.
    val isDefaultHome = context.getSystemService(RoleManager::class.java)
        ?.isRoleHeld(RoleManager.ROLE_HOME) == true
    val recoveryMessage = if (!isDefaultHome && message != null)
        "Set Duo as your Home app below, then tap Retry."
    else message
    val googleIntent = remember(message) {
        context.packageManager.getLaunchIntentForPackage(DiscoverClient.GOOGLE_PACKAGE)
    }
    var showMessage by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        showMessage = false
        if (message != null) { delay(650); showMessage = true }
    }
    Box(modifier.testTag("discover-page")) {
        // The healthy native feed moves above this page. Keep its backing page transparent
        // so the retained Home layer is revealed during entry and exit, not an empty glass card.
        if (showMessage && recoveryMessage != null) Surface(Modifier.fillMaxSize().testTag("discover-recovery-surface"),
            shape = RoundedCornerShape(30.dp), color = Glass.copy(alpha = .92f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .5f))) {
            Column(Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Discover", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Text(recoveryMessage, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                FilledTonalButton(onClick = LiveDiscover::retry, Modifier.testTag("discover-retry")) { Text("Retry") }
                if (googleIntent != null) TextButton(onClick = {
                    runCatching { context.startActivity(googleIntent) }
                }, Modifier.testTag("discover-open-google")) { Text("Open Google") }
                TextButton(onClick = { LiveDiscover.onHomeRequest?.invoke() },
                    Modifier.testTag("discover-return-home")) { Text("Back to Home") }
            }
        }
    }
}
