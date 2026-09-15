package com.jake.duolauncher

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.view.Gravity
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter

/** Small, independently implemented client for Google's optional launcher-overlay service.
 * The wire method order is documented in docs/DISCOVER.md. No Google/Lawnchair code is bundled.
 * All state changes and listener calls run on the main thread. A generation owns its callbacks.
 */
internal class DiscoverClient(
    private val activity: Activity,
    private val verticalStatus: Boolean,
    private val onState: (String?) -> Unit,
    private val onVisible: () -> Unit,
    private val onProgress: (Float) -> Unit,
    private val onClosed: () -> Unit,
    private val pagerDriven: Boolean = false,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var desiredProgress = 0f
    private var pageScrolling = false
    private var pageRequest = 0
    private var connection: ServiceConnection? = null
    private var remote: IBinder? = null
    private var generation = 0
    private var resumed = false
    private val dismissal = DiscoverDismissal()
    private var closing = false
    private var everVisible = false
    private var ready = false
    private var openRequested = false
    private var lastProgress = 1f
    private var closeCompleted = false
    private var closeAnimator: ValueAnimator? = null

    fun connect() {
        if (activity.isDestroyed || activity.isFinishing) return
        disconnect()
        val googleVersion = runCatching {
            val info = activity.packageManager.getPackageInfo(GOOGLE_PACKAGE, 0)
            "${info.versionName}/${info.longVersionCode}"
        }.getOrDefault("unavailable")
        DiagnosticLog.event("discover", "connect_requested",
            "package=${activity.packageName} pagerDriven=$pagerDriven googleVersion=$googleVersion")
        onState("Connecting to Discover…")
        val attempt = generation
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                if (code !in 1..2) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK)
                val scroll = if (code == 1) data.readFloat() else 0f
                val status = if (code == 2) data.readInt() else 0
                handler.post {
                    if (generation != attempt || remote == null) return@post
                    if (code == 2) {
                        if (closing) return@post
                        ready = status and 1 != 0
                        if (ready && resumed) { if (pagerDriven) applyPage() else show() }
                        else if (!ready) { openRequested = false; dismissal.suspend(); onState("Discover is unavailable right now.") }
                    } else if (pagerDriven && scroll.isFinite() && scroll in 0f..1f) {
                        if (!resumed) return@post
                        lastProgress = scroll
                        if (scroll > 0f) { everVisible = true; onState(null); onVisible() }
                        onProgress(scroll)
                    } else if (!scroll.isFinite() || scroll !in 0f..1f) return@post
                    else if (closing) {
                        // Our settling animation now owns the position. Google may still emit
                        // a rebound from the native gesture that initiated this transition.
                        return@post
                    } else if (scroll >= .99f) {
                        if (resumed) dismissal.progress(scroll, feedWidthDp())
                        lastProgress = 1f
                        onProgress(1f)
                        everVisible = true
                        onState(null)
                        onVisible()
                    } else if (resumed && dismissal.tracking) {
                        lastProgress = scroll
                        onProgress(scroll)
                        if (dismissal.progress(scroll, feedWidthDp())) closeForHome()
                    }
                }
                return true
            }
        }
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (generation != attempt) return
                val descriptor = runCatching { service.interfaceDescriptor }.getOrNull()
                DiagnosticLog.event("discover", "service_connected", "component=${name.flattenToShortString()} descriptor=$descriptor")
                // A rejected or stale Google binding can accept one-way transactions without
                // supplying the overlay interface. Show recovery instead of waiting for callbacks.
                if (descriptor != OVERLAY) {
                    failed("Google didn't accept the feed connection. Restart Google, then retry.", attempt)
                    return
                }
                remote = service
                handler.post {
                    if (generation != attempt || activity.isDestroyed) return@post
                    val attrs = WindowManager.LayoutParams().apply {
                        copyFrom(activity.window.attributes)
                        gravity = Gravity.TOP or Gravity.LEFT
                        if (pagerDriven) {
                            // Feed touches go to Google, but revealing its window must not
                            // steal focus from a Home gesture already in progress.
                            flags = (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        }
                        // Google's overlay also reads the legacy window flags when attaching.
                        @Suppress("DEPRECATION")
                        if (verticalStatus) flags = flags or WindowManager.LayoutParams.FLAG_FULLSCREEN
                    }
                    // The activity token in LayoutParams is required; the decor's window token
                    // cannot be substituted. Actual feed bounds come from activity embedding.
                    val bundle = Bundle().apply {
                        putParcelable("layout_params", attrs)
                        putParcelable("configuration", activity.resources.configuration)
                        putInt("client_options", 1)
                    }
                    send(14) { writeInt(1); bundle.writeToParcel(this, 0); writeStrongBinder(callback) }
                    // Resume attachment, then wait for Google's ready status before opening.
                    if (resumed) send(8)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (generation == attempt) {
                    DiagnosticLog.event("discover", "service_disconnected", "component=${name.flattenToShortString()}")
                    remote = null; ready = false; openRequested = false; dismissal.suspend()
                    onState("Discover disconnected. Tap Retry to reconnect.")
                }
            }
            override fun onNullBinding(name: ComponentName) {
                DiagnosticLog.event("discover", "null_binding", "component=${name.flattenToShortString()}")
                failed("Google declined the launcher feed connection. Restart the Google app or reboot, then retry; otherwise disable Discover in Duo settings.", attempt)
            }
            override fun onBindingDied(name: ComponentName) {
                DiagnosticLog.event("discover", "binding_died", "component=${name.flattenToShortString()}")
                failed("Discover disconnected. Tap Retry to reconnect.", attempt)
            }
        }
        val intent = overlayIntent(activity)
        if (intent == null) {
            DiagnosticLog.event("discover", "service_unavailable")
            onState("The installed Google app doesn't provide a compatible Discover feed.")
            return
        }
        try {
            if (activity.bindService(intent, binding, Context.BIND_AUTO_CREATE)) {
                connection = binding
                DiagnosticLog.event("discover", "bind_started", "component=${intent.component?.flattenToShortString()}")
            } else {
                DiagnosticLog.event("discover", "bind_rejected")
                onState("Install or enable the Google app to use Discover.")
            }
        } catch (e: RuntimeException) {
            DiagnosticLog.event("discover", "bind_exception", "type=${e.javaClass.simpleName}")
            Log.w(TAG, "Cannot bind Discover", e)
            onState("The Google app couldn't connect to Discover.")
        }
        handler.postDelayed({
            if (generation == attempt && connection != null && !(if (pagerDriven) ready else everVisible)) {
                DiagnosticLog.event("discover", "connection_timeout", "ready=$ready everVisible=$everVisible")
                disconnect()
                onState("Discover is taking a while. You can retry or open Google.")
            }
        }, 12_000)
    }

    fun resume() { resumed = true; if (ready) { if (pagerDriven) { send(8); applyPage() } else show() } else if (remote != null) send(8) }
    fun page(progress: Float, scrolling: Boolean) {
        val request = ++pageRequest
        desiredProgress = progress.coerceIn(0f, 1f)
        if (!ready || !resumed) return
        if (scrolling && !pageScrolling) { pageScrolling = true; send(1) }
        send(2) { writeFloat(desiredProgress) }
        if (!scrolling && pageScrolling) { pageScrolling = false; send(3) }
        if (!scrolling && desiredProgress == 0f) send(6) { writeInt(0) }
        if (!scrolling && (desiredProgress == 0f || desiredProgress == 1f)) reconcileEndpoint(request, desiredProgress, 3)
    }
    private fun reconcileEndpoint(request: Int, endpoint: Float, attempts: Int) {
        if (attempts == 0 || kotlin.math.abs(lastProgress - endpoint) < .001f) return
        handler.postDelayed({
            if (request != pageRequest || !ready || !resumed || remote == null || pageScrolling ||
                kotlin.math.abs(lastProgress - endpoint) < .001f) return@postDelayed
            // Google's endScroll can finish an older settle after our final position,
            // especially after a window resize. Reassert the endpoint once it can
            // accept a new interaction; a new user drag cancels this reconciliation.
            send(1); send(2) { writeFloat(endpoint) }; send(3)
            if (endpoint == 0f) send(6) { writeInt(0) }
            reconcileEndpoint(request, endpoint, attempts - 1)
        }, 80)
    }
    private fun applyPage() {
        if (desiredProgress > 0f) { send(1); send(2) { writeFloat(desiredProgress) }; send(3) }
        else send(6) { writeInt(0) }
    }
    fun pause() { resumed = false; openRequested = false; dismissal.suspend(); if (!closing && !pagerDriven) onProgress(1f); if (remote != null) send(7) }
    private fun feedWidthDp() = activity.window.decorView.width / activity.resources.displayMetrics.density
    // The launcher has already entered Discover. Opening Google's window should not add a slide.
    private fun show() {
        if (closing || openRequested || !ready) return
        openRequested = true
        send(8); send(9) { writeInt(0) }
    }

    /** Keep the live feed moving until Home has been revealed underneath it. */
    fun closeForHome(): Boolean {
        if (closing) return true
        if (remote == null || !everVisible) return false
        closing = true
        dismissal.suspend()
        val attempt = generation
        send(1) // startScroll: the page now owns the native overlay position.
        closeAnimator = ValueAnimator.ofFloat(lastProgress, 0f).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator(1.3f)
            addUpdateListener {
                val position = it.animatedValue as Float
                onProgress(position)
                send(2) { writeFloat(position) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    send(3) // endScroll
                    send(6) { writeInt(0) }
                    finishClose(attempt)
                }
            })
            start()
        }
        return true
    }

    private fun finishClose(attempt: Int) {
        if (generation != attempt || !closing || closeCompleted) return
        closeCompleted = true
        onProgress(0f)
        // Let the final Home preview reach a display frame before handing back to Home.
        activity.window.decorView.postOnAnimation {
            activity.window.decorView.postOnAnimation {
                if (generation == attempt && closing) onClosed()
            }
        }
    }
    private fun failed(message: String, attempt: Int) {
        if (generation != attempt) return
        DiagnosticLog.event("discover", "connection_failed", message)
        disconnect(); onState(message)
    }

    private fun send(code: Int, payload: Parcel.() -> Unit = {}) {
        val binder = remote ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(OVERLAY); data.payload()
            if (!binder.transact(code, data, null, IBinder.FLAG_ONEWAY)) throw RemoteException("Unsupported transaction $code")
        } catch (e: RemoteException) {
            Log.w(TAG, "Discover connection lost", e)
            remote = null; ready = false; dismissal.suspend()
            onState("Discover disconnected. Tap Retry to reconnect.")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Discover protocol unavailable", e)
            remote = null; ready = false; dismissal.suspend()
            onState("This Google app version couldn't open Discover.")
        } finally { data.recycle() }
    }

    fun disconnect() {
        generation++; pageRequest++; pageScrolling = false
        closeAnimator?.removeAllListeners(); closeAnimator?.cancel(); closeAnimator = null
        handler.removeCallbacksAndMessages(null)
        if (remote != null) { send(6) { writeInt(0) }; send(5) { writeInt(0) } }
        remote = null; ready = false; closing = false; closeCompleted = false; openRequested = false; lastProgress = 1f; dismissal.suspend(); everVisible = false
        connection?.let { runCatching { activity.unbindService(it) } }
        connection = null
    }

    companion object {
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY"
        private const val TAG = "DuoDiscover"
        private const val OVERLAY = "com.google.android.libraries.launcherclient.ILauncherOverlay"
        private const val CALLBACK = "com.google.android.libraries.launcherclient.ILauncherOverlayCallback"

        @Suppress("DEPRECATION")
        private fun overlayIntent(context: Context): Intent? {
            // Google's service filter requires the app:// scheme, so resolve the exact
            // protocol-shaped intent rather than checking the action by itself.
            val query = Intent(OVERLAY_ACTION).setPackage(GOOGLE_PACKAGE)
                .setData(Uri.parse("app://${context.packageName}:${Process.myUid()}?v=5"))
            val service = context.packageManager.resolveService(query, 0)?.serviceInfo ?: return null
            if (!service.enabled || !service.exported || service.packageName != GOOGLE_PACKAGE) return null
            return query.setComponent(ComponentName(service.packageName, service.name))
        }

        fun isAvailable(context: Context): Boolean = overlayIntent(context) != null
    }
}
