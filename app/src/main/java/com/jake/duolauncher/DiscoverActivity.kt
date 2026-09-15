package com.jake.duolauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.window.WindowSdkExtensions
import androidx.window.embedding.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.lang.ref.WeakReference

class DuoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiscoverEmbedding.initialize(this)
        DiscoverBounds.initialize(this)
    }
}

internal object DiscoverEmbedding {
    private fun attributes(ratio: Float) = SplitAttributes.Builder()
        .setSplitType(SplitAttributes.SplitType.ratio(ratio))
        .setLayoutDirection(SplitAttributes.LayoutDirection.RIGHT_TO_LEFT).build()

    // Window 1.5.1's lint does not recognize these direct version guards with this toolchain.
    @SuppressLint("RequiresWindowSdk")
    fun initialize(context: Context) {
        val controller = SplitController.getInstance(context)
        RuleController.getInstance(context).addRule(SplitPairRule.Builder(setOf(SplitPairFilter(
            ComponentName(context, DiscoverActivity::class.java),
            ComponentName(context, DiscoverFeedActivity::class.java), null)))
            .setMinWidthDp(0).setMinHeightDp(0).setMinSmallestWidthDp(0)
            .setMaxAspectRatioInPortrait(EmbeddingAspectRatio.ALWAYS_ALLOW)
            .setMaxAspectRatioInLandscape(EmbeddingAspectRatio.ALWAYS_ALLOW)
            .setFinishSecondaryWithPrimary(SplitRule.FinishBehavior.ALWAYS)
            .setFinishPrimaryWithSecondary(SplitRule.FinishBehavior.ALWAYS)
            .setDefaultSplitAttributes(attributes(.05f)).setClearTop(true).setTag("duo-discover").build())
        if (WindowSdkExtensions.getInstance().extensionVersion >= 2) {
          controller.setSplitAttributesCalculator { params ->
            if (params.splitRuleTag != "duo-discover") return@setSplitAttributesCalculator params.defaultSplitAttributes
            // Discover is immersive: retain only a minimal host strip for the paired-activity
            // contract while giving the Google activity the remaining width.
            attributes(.05f)
          }
        }
    }

    fun supported(context: Context) = WindowSdkExtensions.getInstance().extensionVersion >= 6 &&
        SplitController.getInstance(context).splitSupportStatus == SplitController.SplitSupportStatus.SPLIT_AVAILABLE
}

/** Keeps the full launcher out of the split. Its pages and drag coordinates stay unchanged. */
internal object DiscoverSession {
    var host = WeakReference<DiscoverActivity>(null)
    var feed = WeakReference<DiscoverFeedActivity>(null)
    var apps: List<AppEntry> = emptyList()
    fun dismiss() { host.get()?.finishAndRemoveTask(); host.clear(); feed.clear(); apps = emptyList(); DiscoverMotion.reset() }
    fun requestHome(activity: Activity) { feed.get()?.returnHome() ?: home(activity) }
    fun home(activity: Activity, search: Boolean = false) {
        activity.startActivity(Intent.makeMainActivity(ComponentName(activity, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra("duo_destination", if (search) "search" else "home"))
        // Main removes this task after its first draw, keeping the preview visible through
        // the window handoff instead of exposing a blank compositor frame.
    }
}

private fun ComponentActivity.configureDiscoverWindow(vertical: Boolean) {
    // The page coordinates its own motion. Also clear the window-level style that Google
    // inherits from LayoutParams; NO_ANIMATION alone only governs the activity handoff.
    window.setWindowAnimations(0)
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (vertical) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
    }
}

/** Observe gestures delivered to our own windows; native feed touches stay entirely with Google. */
abstract class DiscoverPageActivity : ComponentActivity() {
    private val homeSwipe by lazy {
        DiscoverHomeSwipe(72f * resources.displayMetrics.density, ViewConfiguration.get(this).scaledTouchSlop.toFloat())
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> homeSwipe.down(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> homeSwipe.cancel()
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && homeSwipe.move(event.x, event.y)) {
                // Cancel the in-progress press/scroll before leaving, so no dock app or Retry
                // button can fire when this gesture ends on Home.
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                DiscoverSession.requestHome(this)
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

class DiscoverActivity : DiscoverPageActivity() {
    private val model: LauncherModel by viewModels()
    private val fullSize = mutableStateOf(Size.Zero)
    private var feedLaunched = false
    private var viewportReady = false
    @SuppressLint("RequiresWindowSdk") // Collection is directly guarded by extensionVersion >= 6.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedLaunched = savedInstanceState != null
        DiscoverSession.host = WeakReference(this)
        DiscoverBounds.resetViewport()
        configureDiscoverWindow(model.state.value.verticalStatus)
        val bounds = windowManager.currentWindowMetrics.bounds
        fullSize.value = Size(bounds.width().toFloat(), bounds.height().toFloat())
        if (!DiscoverEmbedding.supported(this)) {
            Toast.makeText(this, "This device can't show Discover beside the dock.", Toast.LENGTH_LONG).show()
            DiscoverSession.home(this); return
        }
        lifecycleScope.launch {
          if (WindowSdkExtensions.getInstance().extensionVersion >= 6) {
            ActivityEmbeddingController.getInstance(this@DiscoverActivity).embeddedActivityWindowInfo(this@DiscoverActivity).collect {
                fullSize.value = Size(it.parentHostBounds.width().toFloat(), it.parentHostBounds.height().toFloat())
            }
          }
        }
        setContent {
            DuoTheme(rememberSavedAppearance().dark) {
                BackHandler { DiscoverSession.requestHome(this) }
                DiscoverViewport(onReady = { viewportReady = true; openFeed() })
            }
        }
        if (!DiscoverBounds.available) window.decorView.post { viewportReady = true; openFeed() }
    }
    private fun openFeed() {
        if (!viewportReady || feedLaunched || isFinishing) return
        feedLaunched = true
        // Attach in place: this activity is content within the Discover page, not another page.
        startActivity(Intent(this, DiscoverFeedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION), DiscoverBounds.launchOptions())
    }
    override fun onResume() { super.onResume(); model.refresh(); configureDiscoverWindow(model.state.value.verticalStatus) }
    override fun onDestroy() { if (DiscoverSession.host.get() === this) DiscoverSession.host.clear(); super.onDestroy() }
    private fun launchApp(app: AppEntry) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startMainActivity(
                app.component, user, null, null)
            model.recordAppLaunch(app.id)
        } catch (_: RuntimeException) { Toast.makeText(this, "${app.label} is unavailable.", Toast.LENGTH_SHORT).show() }
    }
}

class DiscoverFeedActivity : DiscoverPageActivity() {
    private lateinit var client: DiscoverClient
    private lateinit var frame: DiscoverFrame
    private val message = mutableStateOf<String?>("Connecting to Discover…")
    private var returnAnimator: ValueAnimator? = null
    private var connectRequest = 0
    @SuppressLint("RequiresWindowSdk") // Collection is directly guarded by extensionVersion >= 6.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiscoverSession.feed = WeakReference(this)
        val vertical = runCatching { JSONObject(getSharedPreferences("launcher", 0).getString("state", "{}") ?: "{}").optBoolean("verticalStatus", true) }.getOrDefault(true)
        configureDiscoverWindow(vertical)
        frame = DiscoverFrame(this, vertical)
        val bounds = windowManager.maximumWindowMetrics.bounds
        frame.fullSize = Size(bounds.width().toFloat(), bounds.height().toFloat())
        lifecycleScope.launch {
          if (WindowSdkExtensions.getInstance().extensionVersion >= 6) {
            ActivityEmbeddingController.getInstance(this@DiscoverFeedActivity).embeddedActivityWindowInfo(this@DiscoverFeedActivity).collect {
                frame.fullSize = Size(it.parentHostBounds.width().toFloat(), it.parentHostBounds.height().toFloat())
                frame.origin = androidx.compose.ui.geometry.Offset(it.boundsInParentHost.left.toFloat(), it.boundsInParentHost.top.toFloat())
            }
          }
        }
        client = DiscoverClient(this, vertical,
            onState = { message.value = it; if (it != null) frame.hide() },
            onVisible = frame::reveal, onProgress = { DiscoverMotion.progress.floatValue = it; frame.invalidate() },
            onClosed = { DiscoverSession.home(this) })
        setContent {
            DuoTheme(rememberSavedAppearance().dark) {
                BackHandler { returnHome() }
                var showMessage by remember { mutableStateOf(false) }
                LaunchedEffect(message.value) {
                    showMessage = false
                    // A fast connection should not flash a Retry/loading panel for one frame.
                    if (message.value == "Connecting to Discover…") { delay(650); frame.hide() }
                    showMessage = message.value != null
                }
                val progress = DiscoverMotion.progress.floatValue
                Box(Modifier.fillMaxSize()) {
                    if (showMessage) Surface(Modifier.fillMaxSize(), color = Glass,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .4f))) {
                        // Recovery is only shown while connecting or after a real error. A native
                        // swipe must never reveal the old loading controls behind a loaded feed.
                        if (showMessage) Column(Modifier.fillMaxSize().graphicsLayer {
                            translationX = -(1f - progress) * DiscoverMotion.pageWidth
                        }.padding(24.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Discover", style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(16.dp))
                            Text(message.value ?: "Google Discover", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(20.dp))
                            FilledTonalButton(onClick = ::connectSafely, Modifier.testTag("discover-retry")) {
                                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Retry")
                            }
                            TextButton(onClick = ::openGoogle) { Text("Open Google") }
                            TextButton(onClick = ::returnHome) { Text("Back to home") }
                        }
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        // Read the same progress as the native frame so both windows agree.
                        if (progress < 1f) DiscoverMotion.drawHome(drawContext.canvas.nativeCanvas,
                            frame.origin.x, frame.origin.y, frame.fullSize.width, frame.fullSize.height)
                    }
                }
            }
        }
        // Never attach a full-screen overlay when a host failed to embed this activity.
        window.decorView.post { connectSafely() }
    }
    override fun onResume() { super.onResume(); if (::client.isInitialized) client.resume() }
    override fun onPause() { if (::client.isInitialized) client.pause(); super.onPause() }
    override fun onDestroy() {
        connectRequest++
        returnAnimator?.removeAllListeners(); returnAnimator?.cancel()
        if (DiscoverSession.feed.get() === this) DiscoverSession.feed.clear()
        if (::client.isInitialized) client.disconnect()
        if (::frame.isInitialized) frame.hide()
        super.onDestroy()
    }
    fun returnHome() {
        if (isFinishing || returnAnimator != null) return
        if (::client.isInitialized && client.closeForHome()) return
        if (::client.isInitialized) client.disconnect()
        // Recovery and pre-connection swipes use the same incoming Home preview.
        returnAnimator = ValueAnimator.ofFloat(DiscoverMotion.progress.floatValue, 0f).apply {
            duration = 240
            addUpdateListener { DiscoverMotion.progress.floatValue = it.animatedValue as Float; frame.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { DiscoverSession.home(this@DiscoverFeedActivity) }
            })
            start()
        }
    }
    private fun connectSafely() {
        if (isDestroyed || isFinishing) return
        frame.hide()
        val request = ++connectRequest
        fun attachWhenSized(remaining: Int) {
            if (request != connectRequest || isDestroyed || isFinishing || returnAnimator != null) return
            val decor = window.decorView
            if (ActivityEmbeddingController.getInstance(this).isActivityEmbedded(this) &&
                DiscoverBounds.matchesViewport(decor.width, decor.height)) client.connect()
            else if (remaining > 0) decor.postOnAnimation { attachWhenSized(remaining - 1) }
            else message.value = "Discover couldn't fit beside the dock. Return home and try again."
        }
        // Embedding can be reported before the decor has received its inset size. Attaching
        // Google during that gap can briefly create a full-screen white native window.
        window.decorView.postOnAnimation { attachWhenSized(20) }
    }
    private fun openGoogle() {
        val intent = packageManager.getLaunchIntentForPackage(DiscoverClient.GOOGLE_PACKAGE)
        if (intent != null) runCatching { startActivity(intent) }
        else Toast.makeText(this, "Install or enable the Google app first.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun DiscoverViewport(onReady: () -> Unit) {
    val context = LocalContext.current
    val progress = DiscoverMotion.progress.floatValue
    Box(Modifier.fillMaxSize().testTag("discover-chrome").semantics { testTagsAsResourceId = true }
        .background(if (DuoAppearanceRuntime.dark) Color(0xFF263A43) else Color(0xFFE8EFF2))) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).onGloballyPositioned {
            val bounds = it.boundsInWindow()
            DiscoverBounds.updateViewport(context, android.graphics.Rect(
                bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
            onReady()
        })
        Canvas(Modifier.fillMaxSize()) {
            if (progress < 1f) DiscoverMotion.drawHome(drawContext.canvas.nativeCanvas, 0f, 0f, size.width, size.height)
        }
    }
}
