package com.olympus.iptv.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager

object DeviceForm {

    const val LOW_HEAP_BYTES = 256L * 1024L * 1024L

    @Volatile private var cachedTelevision: Boolean? = null
    @Volatile private var cachedTouchPrimary: Boolean? = null
    @Volatile private var cachedLowRam: Boolean? = null
    @Volatile private var cachedGoogleTvStreamer: Boolean? = null
    @Volatile private var cachedPowerSave = false
    @Volatile private var powerSaveCheckedAt = 0L
    @Volatile private var cachedWidthKey = 0L
    @Volatile private var cachedWidthAt = 0L
    @Volatile private var cachedWidthDp = 0

    fun isTelevision(context: Context): Boolean {
        cachedTelevision?.let { return it }
        val app = context.applicationContext
        val pm = app.packageManager
        val fireTv = pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            (Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
                Build.MODEL.startsWith("AFT", ignoreCase = true))
        @Suppress("DEPRECATION")
        val tv = fireTv ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
            (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        cachedTelevision = tv
        return tv
    }

    fun isTouchPrimary(context: Context): Boolean {
        cachedTouchPrimary?.let { return it }
        val touch = !isTelevision(context) &&
            context.applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        cachedTouchPrimary = touch
        return touch
    }

    fun windowWidthDp(context: Context): Int {
        val res = context.resources
        val dm = res.displayMetrics
        val density = dm.density
        val cfgWidth = res.configuration.screenWidthDp
        val key = (dm.widthPixels.toLong() shl 32) xor
            (cfgWidth.toLong() shl 16) xor
            java.lang.Float.floatToRawIntBits(density).toLong().and(0xFFFFL)
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedWidthKey == key && cachedWidthDp > 0 && now - cachedWidthAt < 500L) {
            return cachedWidthDp
        }
        val result = if (context is Activity && Build.VERSION.SDK_INT >= 30 && density > 0f) {
            val px = context.windowManager.currentWindowMetrics.bounds.width()
            if (px > 0) (px / density).toInt()
            else if (dm.widthPixels > 0) (dm.widthPixels / density).toInt()
            else cfgWidth
        } else if (dm.widthPixels > 0 && density > 0f) {
            (dm.widthPixels / density).toInt()
        } else {
            cfgWidth
        }
        cachedWidthKey = key
        cachedWidthAt = now
        cachedWidthDp = result
        return result
    }

    fun isCompact(context: Context): Boolean {
        if (isTelevision(context)) return false
        return windowWidthDp(context) < 900
    }

    fun isPortrait(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    fun powerSave(context: Context): Boolean {
        if (isTelevision(context)) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - powerSaveCheckedAt < 5_000L) return cachedPowerSave
        val power = context.applicationContext.getSystemService(PowerManager::class.java) ?: return false
        val on = power.isPowerSaveMode
        cachedPowerSave = on
        powerSaveCheckedAt = now
        return on
    }

    fun contentColumns(context: Context): Int {
        if (isTelevision(context)) return contentColumnsFor(1000)
        return contentColumnsFor(windowWidthDp(context))
    }

    fun contentColumnsFor(widthDp: Int): Int = when {
        widthDp >= 1000 -> 6
        widthDp >= 720 -> 4
        widthDp >= 520 -> 3
        else -> 2
    }

    fun categoryColumns(context: Context): Int {
        if (isTelevision(context)) return categoryColumnsFor(1000)
        return categoryColumnsFor(windowWidthDp(context))
    }

    fun categoryColumnsFor(widthDp: Int): Int = when {
        widthDp >= 1000 -> 4
        widthDp >= 700 -> 4
        widthDp >= 480 -> 3
        else -> 2
    }

    fun isLowRam(context: Context): Boolean {
        cachedLowRam?.let { return it }
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        @Suppress("DEPRECATION")
        val low = manager?.isLowRamDevice == true
        val smallTotalRam = if (manager != null) {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.totalMem in 1L..2_200_000_000L
        } else {
            false
        }
        val result = smallTotalRam || isLowRam(low, Runtime.getRuntime().maxMemory())
        cachedLowRam = result
        return result
    }

    fun isLowRam(lowRamDevice: Boolean, maxMemoryBytes: Long): Boolean =
        lowRamDevice || maxMemoryBytes <= LOW_HEAP_BYTES

    fun isGoogleTvStreamer(context: Context): Boolean {
        cachedGoogleTvStreamer?.let { return it }
        val result = isTelevision(context) &&
            Build.MANUFACTURER.equals("Google", ignoreCase = true) &&
            (Build.MODEL.orEmpty().contains("Streamer", ignoreCase = true) ||
                Build.DEVICE.orEmpty().equals("kirkwood", ignoreCase = true) ||
                Build.VERSION.SDK_INT >= 34)
        cachedGoogleTvStreamer = result
        return result
    }

    fun isHighEndTv(context: Context): Boolean = isTelevision(context) && !isLowRam(context)
}
