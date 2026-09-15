package com.jake.duolauncher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Bounded, app-private diagnostics. Callers provide operational data only, never user content. */
internal object DiagnosticLog {
    private const val TAG = "DuoDiagnostics"
    private const val MAX_BYTES = 512 * 1024L
    private const val RETAINED_BYTES = 256 * 1024
    private lateinit var app: Context
    private val lock = Any()

    fun initialize(context: Context) {
        app = context.applicationContext
        event("app", "started", buildIdentity(app))
    }

    fun event(component: String, event: String, detail: String = "") {
        Log.i(TAG, "$component/$event${if (detail.isBlank()) "" else ": $detail"}")
        if (!::app.isInitialized) return
        val clean = detail.replace(Regex("[\\r\\n\\t]+"), " ").take(1_000)
        val line = "${Instant.now()} ${component.take(40)} ${event.take(80)}${if (clean.isBlank()) "" else " $clean"}\n"
        synchronized(lock) {
            runCatching {
                val file = File(app.filesDir, "diagnostics.log")
                if (file.length() >= MAX_BYTES) {
                    val bytes = file.readBytes()
                    file.writeBytes(bytes.copyOfRange((bytes.size - RETAINED_BYTES).coerceAtLeast(0), bytes.size))
                }
                file.appendText(line)
            }.onFailure { Log.w(TAG, "Could not write diagnostic event", it) }
        }
    }

    fun snapshot(): String = synchronized(lock) {
        val body = if (::app.isInitialized) runCatching { File(app.filesDir, "diagnostics.log").readText() }.getOrDefault("") else ""
        "Duo Launcher diagnostic log\n${if (::app.isInitialized) buildIdentity(app) else "app=uninitialized"}\n" +
            "Generated=${Instant.now()}\n\n$body"
    }

    private fun buildIdentity(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val certificates = info.signingInfo?.let { signing ->
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        }.orEmpty()
        val fingerprints = certificates.joinToString(",") { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return "package=${context.packageName} versionName=${info.versionName} versionCode=${info.longVersionCode} " +
            "debuggable=$debuggable sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "signerSha256=$fingerprints"
    }
}

internal class DiagnosticLogController(
    private val activity: ComponentActivity,
    private val onExternalResultChanged: (Boolean) -> Unit,
) {
    private val createDocument = activity.activityResultRegistry.register(
        "duo.diagnostics.create", activity, ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            onExternalResultChanged(false)
            return@register
        }
        activity.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                    it.write(DiagnosticLog.snapshot())
                } ?: error("The selected document could not be opened")
            } }
            onExternalResultChanged(false)
            Toast.makeText(activity, if (result.isSuccess) "Diagnostic log saved." else "Diagnostic log could not be saved.",
                Toast.LENGTH_LONG).show()
        }
    }

    fun export() {
        onExternalResultChanged(true)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        runCatching { createDocument.launch("duo-launcher-diagnostics-$stamp.txt") }
            .onFailure {
                onExternalResultChanged(false)
                Toast.makeText(activity, "The document picker is unavailable.", Toast.LENGTH_LONG).show()
            }
    }
}
