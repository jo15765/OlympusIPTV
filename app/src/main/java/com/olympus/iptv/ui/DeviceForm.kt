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
    /** Heap cap typical of 1.5–2 GB sticks. Larger boxes get a bigger runtime heap. */
    const val LOW_HEAP_BYTES = 256L * 1024L * 1024L

    @Volatile private var cachedTelevision: Boolean? = null
    @Volatile private var cachedTouchPrimary: Boolean? = null
    @Volatile private var cachedLowRam: Boolean? = null
    @Volatile private var cachedPowerSave = false
    @Volatile private var powerSaveCheckedAt = 0L
    @Volatile private var cachedWidthKey = 0L
    @Volatile private var cachedWidthAt = 0L
    @Volatile private var cachedWidthDp = 0

    fun isTelevision(context: Context): Boolean {
        cachedTelevision?.let { return it }
        val app = context.applicationContext
        val pm = app.packageManager
        val uiMode = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val fireTv = pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            (Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
                Build.MODEL.startsWith("AFT", ignoreCase = true))
        val tv = fireTv ||
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
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

    /**
     * Width of the window the user can actually see. On a Galaxy Fold this updates when the
     * cover closes and the inner screen opens. Configuration.screenWidthDp often stays at the
     * cover width, which left the cards at the small size.
     */
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

    /** A Galaxy Fold inner screen stays in the phone card layout so the cards use the full display. */
    fun isCompact(context: Context): Boolean {
        if (isTelevision(context)) return false
        return windowWidthDp(context) < 900
    }

    fun isPortrait(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** Battery saver on a phone or tablet. Televisions ignore the system saver. */
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

    /** Poster columns for foldables (SM-F976U Z Fold), tablets, phones, and TVs. */
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

    /** Category card columns for foldables, tablets, and TVs. */
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

    /** Fire Stick / Onn-class sticks versus Shield / Streamer-class boxes. Cached for process lifetime. */
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
}
