package com.jake.duolauncher

import android.app.role.RoleManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

class MainActivity : ComponentActivity() {
    private val model: LauncherModel by viewModels()
    private lateinit var widgets: WidgetController
    internal lateinit var backups: BackupController
        private set
    internal lateinit var backgrounds: LauncherBackgroundController
        private set
    private val homeRequests = mutableIntStateOf(0)
    private val searchRequests = mutableIntStateOf(0)
    private val defaultHome = mutableStateOf(false)
    private val showFirstRun = mutableStateOf(false)
    private lateinit var setupExperience: SetupExperience
    private lateinit var status: DeviceStatusMonitor
    private lateinit var appearance: AppearanceStore
    private var appearanceLocationGeneration = 0
    private var appearancePermissionGeneration = -1
    private var appearanceLocationCancellation: CancellationSignal? = null
    private var timeReceiverRegistered = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { appearance.refresh(systemDark()) }
    }
    private val locationPermission = activityResultRegistry.register("duo.appearance.location", this,
        ActivityResultContracts.RequestPermission(), permissionResult@{ granted ->
        if (appearancePermissionGeneration != appearanceLocationGeneration || isDestroyed) return@permissionResult
        appearancePermissionGeneration = -1
        if (granted) requestAppearanceLocation(keepPending = true)
        else finishAppearanceLocation("Location permission wasn’t granted. Using the system theme until you set a place.")
    })
    private var openingDiscover = false
    private var shadeSetupDialog: android.app.AlertDialog? = null
    private var returningFromShadeSettings = false
    private var shadeSetupOwnsExternalUi = false
    private var recreatingShadeSetup = false
    private var returningFromGoogleSearch = false
    private var googleSearchOwnsExternalUi = false
    private var returningFromDockPicker = false
    private var dockPickerOwnsExternalUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupExperience = SetupExperience(this)
        showFirstRun.value = setupExperience.entryDecision(SetupExperience.hadLauncherState(this)) ==
            SetupEntryDecision.SHOW
        returningFromShadeSettings = savedInstanceState?.getBoolean(SHADE_SETTINGS_PENDING) == true
        returningFromGoogleSearch = savedInstanceState?.getBoolean(GOOGLE_SEARCH_PENDING) == true
        returningFromDockPicker = savedInstanceState?.getBoolean(DOCK_PICKER_PENDING) == true
        val restoreShadeDialog = savedInstanceState?.getBoolean(SHADE_DIALOG_VISIBLE) == true
        appearance = AppearanceStore(this)
        window.preferHighRefreshRate()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        widgets = WidgetController(this, model) { active ->
            LiveDiscover.setExternalResultPending(this, "main", "widget-setup", active)
        }.also { it.restore(savedInstanceState) }
        backups = BackupController(this, model, widgets) { active ->
            LiveDiscover.setExternalResultPending(this, "main", "layout-backup", active)
        }.also { it.restore() }
        backgrounds = LauncherBackgroundController(this) { active ->
            LiveDiscover.setExternalResultPending(this, "main", "launcher-background", active)
        }
        status = DeviceStatusMonitor(this).also { lifecycle.addObserver(it) }
        updateDefaultHome()
        if (savedInstanceState == null && intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        intent.removeExtra("duo_destination")
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            val deviceStatus = status.state.collectAsStateWithLifecycle().value
            DuoTheme(appearance.state.dark) {
                LauncherScreen(state, model, widgets, homeRequests.intValue,
                    onLaunch = { launchApp(it) }, onMakeDefault = ::makeDefault, onAppInfo = ::appInfo,
                    isDefaultHome = defaultHome.value, deviceStatus = deviceStatus, onStatusMode = ::setStatusMode, onWallpaperPreview = ::previewWallpaper,
                    onDiscover = ::openDiscover, searchRequests = searchRequests.intValue,
                    onChooseDock = ::chooseDockApp,
                    onLaunchFrom = ::launchApp, onGoogleSearch = ::openGoogleSearch,
                    appearance = appearance.state,
                    onAppearanceMode = { cancelAppearanceLocation(); appearance.setMode(it, systemDark()) },
                    onAppearanceManual = { place, lat, lon -> cancelAppearanceLocation(); appearance.setManual(place, lat, lon, systemDark()) },
                    onAppearanceDeviceLocation = ::useAppearanceLocation,
                    onAppearanceClear = { cancelAppearanceLocation(); appearance.clearLocation(systemDark()) },
                    showFirstRun = showFirstRun.value,
                    onFinishFirstRun = ::finishFirstRun,
                    onShadeSetup = ::showShadeSetup,
                    onLockScreen = ::lockScreen)
            }
        }
        FoldRenderExperiment.attach(this)
        // Reassert the token after recreation (and after process restoration, where the
        // in-memory owner set is empty) before any external UI can uncover Discover.
        if (returningFromShadeSettings || restoreShadeDialog) ownShadeSetupExternally()
        if (returningFromGoogleSearch) ownGoogleSearchExternally()
        if (returningFromDockPicker) ownDockPickerExternally()
        if (restoreShadeDialog) window.decorView.post { if (!isFinishing && !isDestroyed) showShadeSetup() }
    }

    override fun onStart() {
        super.onStart(); widgets.host.startListening()
        if (!timeReceiverRegistered) {
            ContextCompat.registerReceiver(this, timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            timeReceiverRegistered = true
        }
        appearance.refresh(systemDark())
    }
    override fun onStop() {
        if (timeReceiverRegistered) { unregisterReceiver(timeReceiver); timeReceiverRegistered = false }
        widgets.host.stopListening(); super.onStop()
    }
    override fun onDestroy() {
        recreatingShadeSetup = isChangingConfigurations
        shadeSetupDialog?.dismiss()
        if (!isChangingConfigurations) releaseShadeSetupOwnership()
        if (!isChangingConfigurations) releaseGoogleSearchOwnership()
        if (!isChangingConfigurations) releaseDockPickerOwnership()
        cancelAppearanceLocation()
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        window.preferHighRefreshRate()
        if (returningFromShadeSettings) {
            returningFromShadeSettings = false
            releaseShadeSetupOwnership()
        }
        if (returningFromGoogleSearch) {
            returningFromGoogleSearch = false
            releaseGoogleSearchOwnership()
        }
        if (returningFromDockPicker) {
            returningFromDockPicker = false
            releaseDockPickerOwnership()
        }
        val discover = DiscoverSession.host.get()
        if (discover != null) window.decorView.doOnPreDraw {
            it.postOnAnimation { if (DiscoverSession.host.get() === discover) DiscoverSession.dismiss() }
        }
        model.refresh(); appearance.refresh(systemDark()); updateDefaultHome()
        window.decorView.post {
            if (!isFinishing && !isDestroyed && !LiveDiscover.viewport.isEmpty)
                LiveDiscover.prepare(this, LiveDiscover.viewport, LiveDiscover.pageWidth)
        }
    }

    internal fun openSystemShade(panel: ShadePanel) {
        when (SystemShadeAccessibilityService.open(this, panel)) {
            ShadeOpenResult.OPENED -> Unit
            ShadeOpenResult.SERVICE_DISABLED -> showShadeSetup()
            ShadeOpenResult.SERVICE_STARTING -> Toast.makeText(this,
                "Shade gestures are starting. Swipe down again.", Toast.LENGTH_SHORT).show()
            ShadeOpenResult.ACTION_REJECTED -> Toast.makeText(this,
                "Android couldn’t open the system panel.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShadeSetup() {
        if (shadeSetupDialog?.isShowing == true) return
        ownShadeSetupExternally()
        shadeSetupDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Turn on shade gestures")
            .setMessage("Android requires you to enable Duo Launcher shade gestures in Accessibility settings. This service only opens Notifications or Quick Settings; it doesn’t read screen content or watch other apps.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    returningFromShadeSettings = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: android.content.ActivityNotFoundException) {
                    returningFromShadeSettings = false
                    releaseShadeSetupOwnership()
                    Toast.makeText(this, "Accessibility settings are unavailable.", Toast.LENGTH_LONG).show()
                }
            }
            .also { dialog -> dialog.setOnDismissListener {
                shadeSetupDialog = null
                if (!returningFromShadeSettings && !recreatingShadeSetup) releaseShadeSetupOwnership()
            } }
            .show()
    }

    private fun finishFirstRun() {
        setupExperience.finish()
        showFirstRun.value = false
    }

    private fun ownShadeSetupExternally() {
        if (shadeSetupOwnsExternalUi) return
        shadeSetupOwnsExternalUi = true
        LiveDiscover.setExternalResultPending(this, "main", "shade-service-setup", true)
    }

    private fun releaseShadeSetupOwnership() {
        if (!shadeSetupOwnsExternalUi) return
        shadeSetupOwnsExternalUi = false
        LiveDiscover.setExternalResultPending(this, "main", "shade-service-setup", false)
    }

    private fun lockScreen() {
        when (SystemShadeAccessibilityService.lock(this)) {
            ShadeOpenResult.OPENED -> Unit
            ShadeOpenResult.SERVICE_DISABLED -> showShadeSetup()
            ShadeOpenResult.SERVICE_STARTING -> Toast.makeText(this,
                "Lock gesture is starting. Double tap again.", Toast.LENGTH_SHORT).show()
            ShadeOpenResult.ACTION_REJECTED -> Toast.makeText(this,
                "Android couldn’t lock the screen.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ownGoogleSearchExternally() {
        if (googleSearchOwnsExternalUi) return
        googleSearchOwnsExternalUi = true
        LiveDiscover.setExternalResultPending(this, "main", "google-search", true)
    }

    private fun releaseGoogleSearchOwnership() {
        if (!googleSearchOwnsExternalUi) return
        googleSearchOwnsExternalUi = false
        LiveDiscover.setExternalResultPending(this, "main", "google-search", false)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setStatusMode(model.state.value.verticalStatus)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        widgets.save(outState)
        outState.putBoolean(SHADE_DIALOG_VISIBLE, shadeSetupDialog?.isShowing == true && !returningFromShadeSettings)
        outState.putBoolean(SHADE_SETTINGS_PENDING, returningFromShadeSettings)
        outState.putBoolean(GOOGLE_SEARCH_PENDING, returningFromGoogleSearch)
        outState.putBoolean(DOCK_PICKER_PENDING, returningFromDockPicker)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        FoldRenderExperiment.onNewIntent(this, intent)
        if (intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        else if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.getStringExtra("duo_destination") == "home") homeRequests.intValue++
        intent.removeExtra("duo_destination")
    }

    @Deprecated("Widget configuration uses the platform host request-code API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun launchApp(app: AppEntry, bounds: android.graphics.Rect? = null) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startMainActivity(app.component, user, screenBounds(bounds), launchOptions(bounds))
            model.recordAppLaunch(app.id)
        } catch (_: Exception) { Toast.makeText(this, "${app.label} is unavailable.", Toast.LENGTH_SHORT).show(); model.refresh() }
    }

    private fun screenBounds(bounds: android.graphics.Rect?): android.graphics.Rect? = bounds?.takeUnless { it.isEmpty }?.let {
        val location = IntArray(2); window.decorView.getLocationOnScreen(location)
        android.graphics.Rect(it).apply { offset(location[0], location[1]) }
    }
    private fun launchOptions(bounds: android.graphics.Rect?): Bundle? = bounds?.takeUnless { it.isEmpty }?.let {
        android.app.ActivityOptions.makeScaleUpAnimation(window.decorView, it.left, it.top, it.width(), it.height()).toBundle()
    }
    private fun openGoogleSearch(bounds: android.graphics.Rect?): Boolean {
        ownGoogleSearchExternally()
        returningFromGoogleSearch = true
        return try {
            startActivity(googleSearchIntent().apply { sourceBounds = screenBounds(bounds) }, launchOptions(bounds))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            returningFromGoogleSearch = false
            releaseGoogleSearchOwnership()
            false
        } catch (_: SecurityException) {
            returningFromGoogleSearch = false
            releaseGoogleSearchOwnership()
            false
        }
    }

    private fun chooseDockApp(slot: Int) {
        ownDockPickerExternally()
        returningFromDockPicker = true
        val intent = Intent(this, DockAppPickerActivity::class.java)
            .putExtra(DockAppPickerActivity.EXTRA_SLOT, slot)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            returningFromDockPicker = false
            releaseDockPickerOwnership()
        }
    }

    private fun ownDockPickerExternally() {
        if (dockPickerOwnsExternalUi) return
        dockPickerOwnsExternalUi = true
        LiveDiscover.setExternalResultPending(this, "main", "dock-picker", true)
    }

    private fun releaseDockPickerOwnership() {
        if (!dockPickerOwnsExternalUi) return
        dockPickerOwnsExternalUi = false
        LiveDiscover.setExternalResultPending(this, "main", "dock-picker", false)
    }

    internal fun applyDockPickerSelection(slot: Int, appId: String?) {
        if (slot !in model.state.value.dock.indices) return
        if (appId == null) model.removePlacement(DropTarget.Dock(slot))
        else if (canPlaceInDock(model.state.value.layout, appId))
            model.applyDrop(appId, DropTarget.Dock(slot))
    }

    private fun openDiscover() {
        if (DiscoverEmbedding.supported(this) && DiscoverClient.isAvailable(this)) {
            if (openingDiscover) return
            openingDiscover = true
            // A very quick reopen can arrive before the previous return's deferred cleanup.
            // Finish that session before taking a new image, so it cannot invalidate this copy.
            DiscoverSession.dismiss()
            DiscoverMotion.capture(this) {
                openingDiscover = false
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@capture
                DiscoverSession.apps = model.state.value.apps
                startActivity(Intent(this, DiscoverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
        else showDiscoverFallback()
    }

    private fun showDiscoverFallback() {
        val google = packageManager.getLaunchIntentForPackage(DiscoverClient.GOOGLE_PACKAGE)
        android.app.AlertDialog.Builder(this)
            .setTitle("Discover isn’t available here")
            .setMessage("Duo can’t place the Discover feed beside Home on this device. You can open the Google app or stay on Home.")
            .setNegativeButton("Stay on Home", null)
            .apply {
                if (google != null) setPositiveButton("Open Google") { _, _ ->
                    runCatching { startActivity(google) }
                }
            }
            .show()
    }

    private fun makeDefault() {
        // Samsung may immediately cancel a role request; its Home settings is reliable.
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            val role = getSystemService(RoleManager::class.java)
            if (role.isRoleAvailable(RoleManager.ROLE_HOME)) startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun updateDefaultHome() {
        defaultHome.value = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun systemDark() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun useAppearanceLocation() {
        cancelAppearanceLocation()
        appearance.locationStatus("Waiting for approximate device location…")
        LiveDiscover.setExternalResultPending(this, "main", "appearance-location", true)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            requestAppearanceLocation(keepPending = true)
        else {
            appearancePermissionGeneration = appearanceLocationGeneration
            runCatching { locationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
                .onFailure { finishAppearanceLocation("Location permission couldn’t be requested. Using the system theme.") }
        }
    }

    private fun requestAppearanceLocation(keepPending: Boolean = false) {
        if (!keepPending) LiveDiscover.setExternalResultPending(this, "main", "appearance-location", true)
        val generation = ++appearanceLocationGeneration
        val manager = getSystemService(LocationManager::class.java)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            finishAppearanceLocation("Location permission isn’t available. Using the system theme."); return
        }
        val cached = runCatching { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time <= 15 * 60_000 } }.getOrNull()
        if (cached != null) {
            if (generation == appearanceLocationGeneration) appearance.setDeviceLocation(cached.latitude, cached.longitude, systemDark())
            finishAppearanceLocation(null); return
        }
        val provider = runCatching { when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        } }.getOrNull() ?: run { finishAppearanceLocation("No approximate location provider is available. Using the system theme."); return }
        val cancellation = CancellationSignal()
        appearanceLocationCancellation = cancellation
        window.decorView.postDelayed({
            if (generation == appearanceLocationGeneration && appearanceLocationCancellation === cancellation) {
                cancellation.cancel(); finishAppearanceLocation("Location timed out. Using the system theme until you try again or enter a place.")
            }
        }, 10_000)
        runCatching { manager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(this)) { location ->
            if (generation != appearanceLocationGeneration || isDestroyed) return@getCurrentLocation
            if (location != null) appearance.setDeviceLocation(location.latitude, location.longitude, systemDark())
            finishAppearanceLocation(if (location == null) "Location is unavailable. Using the system theme." else null)
        } }.onFailure { finishAppearanceLocation("Location is unavailable. Using the system theme.") }
    }

    private fun cancelAppearanceLocation() {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation?.cancel(); appearanceLocationCancellation = null
        if (::appearance.isInitialized) appearance.locationStatus(null)
        LiveDiscover.setExternalResultPending(this, "main", "appearance-location", false)
    }

    private fun finishAppearanceLocation(message: String?) {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation = null
        appearance.locationStatus(message)
        LiveDiscover.setExternalResultPending(this, "main", "appearance-location", false)
    }

    private fun setStatusMode(vertical: Boolean) {
        LiveDiscover.host.get()?.statusMode(vertical)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (vertical) controller.hide(WindowInsetsCompat.Type.statusBars()) else controller.show(WindowInsetsCompat.Type.statusBars())
    }

    private fun previewWallpaper() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
        } catch (_: Exception) {
            try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            catch (_: Exception) { Toast.makeText(this, "The system wallpaper picker is unavailable.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun appInfo(app: AppEntry) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, user, null, null)
        } catch (_: Exception) {
            Toast.makeText(this, "${app.label} is unavailable.", Toast.LENGTH_SHORT).show()
            model.refresh()
        }
    }

    private companion object {
        const val SHADE_DIALOG_VISIBLE = "duo.shade.dialog_visible"
        const val SHADE_SETTINGS_PENDING = "duo.shade.settings_pending"
        const val GOOGLE_SEARCH_PENDING = "duo.google.search_pending"
        const val DOCK_PICKER_PENDING = "duo.dock.picker_pending"
    }
}
