package com.olympus.iptv.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.os.Build
import android.os.StatFs
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * Universal Android 7–16 / Fire OS / Google TV / Phone / Tablet / Foldable compatibility helpers
 * plus cached hardware codec & system diagnostics.
 */
object DeviceCompat {
    private const val REQ_POST_NOTIFICATIONS = 9413

    @Volatile private var cachedCodecs: Map<String, Boolean>? = null

    /**
     * Applies Android 15+ edge-to-edge safe insets (status bar, gesture bar, display cutout)
     * on touch devices without disturbing set-top TV overscan or mobile guide row heights.
     */
    fun applySafeInsets(root: View) {
        val ctx = root.context
        if (DeviceForm.isTelevision(ctx)) return
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * Requests POST_NOTIFICATIONS once on Android 13+ touch devices so DVR/Reminder/VPN
     * notifications appear cleanly. No-op on TVs or older Android versions.
     */
    fun requestNotificationPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (DeviceForm.isTelevision(activity)) return
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    fun hardwareCodecs(): Map<String, Boolean> {
        cachedCodecs?.let { return it }
        val result = linkedMapOf(
            "h264" to false,
            "hevc" to false,
            "vp9" to false,
            "av1" to false,
            "ac3" to false,
            "eac3" to false
        )
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in list) {
                if (info.isEncoder) continue
                val name = info.name.lowercase()
                val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains("sw")
                }
                if (isSoftware) continue
                for (type in info.supportedTypes) {
                    when (type.lowercase()) {
                        "video/avc" -> result["h264"] = true
                        "video/hevc" -> result["hevc"] = true
                        "video/x-vnd.on2.vp9" -> result["vp9"] = true
                        "video/av01" -> result["av1"] = true
                        "audio/ac3" -> result["ac3"] = true
                        "audio/eac3", "audio/eac3-joc" -> result["eac3"] = true
                    }
                }
            }
        }
        cachedCodecs = result
        return result
    }

    fun diagnostics(context: Context): Map<String, Any?> {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val freeBytes = runCatching { StatFs(app.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val totalBytes = runCatching { StatFs(app.filesDir.absolutePath).totalBytes }.getOrDefault(0L)
        val formFactor = when {
            DeviceForm.isGoogleTvStreamer(app) -> "Google TV Streamer (4K)"
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true) && DeviceForm.isTelevision(app) -> "Amazon Fire TV"
            DeviceForm.isTelevision(app) -> "Android TV / Set-Top Box"
            DeviceForm.isCompact(app) -> "Phone"
            else -> "Tablet / Foldable"
        }
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "apiLevel" to Build.VERSION.SDK_INT,
            "abis" to Build.SUPPORTED_ABIS.joinToString(", "),
            "formFactor" to formFactor,
            "lowRam" to DeviceForm.isLowRam(app),
            "highEndTv" to DeviceForm.isHighEndTv(app),
            "totalRamMb" to (mem.totalMem / (1024L * 1024L)),
            "availRamMb" to (mem.availMem / (1024L * 1024L)),
            "freeStorageMb" to (freeBytes / (1024L * 1024L)),
            "totalStorageMb" to (totalBytes / (1024L * 1024L)),
            "codecs" to hardwareCodecs()
        )
    }

    fun safeExternalFile(context: Context, name: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, name)
    }
}
