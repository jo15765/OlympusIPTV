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
    @Volatile private var cachedPowerSave = false
    @Volatile private var powerSaveCheckedAt = 0L

    fun isTelevision(context: Context): Boolean {
        cachedTelevision?.let { return it }
        val uiMode = context.applicationContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val tv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
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
        val density = context.resources.displayMetrics.density
        if (context is Activity && Build.VERSION.SDK_INT >= 30 && density > 0f) {
            val px = context.windowManager.currentWindowMetrics.bounds.width()
            if (px > 0) return (px / density).toInt()
        }
        val px = context.resources.displayMetrics.widthPixels
        if (px > 0 && density > 0f) return (px / density).toInt()
        return context.resources.configuration.screenWidthDp
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

    /** Fire Stick / Onn-class sticks versus Shield / Streamer-class boxes. */
    fun isLowRam(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        @Suppress("DEPRECATION")
        val low = manager?.isLowRamDevice == true
        return isLowRam(low, Runtime.getRuntime().maxMemory())
    }

    fun isLowRam(lowRamDevice: Boolean, maxMemoryBytes: Long): Boolean =
        lowRamDevice || maxMemoryBytes <= LOW_HEAP_BYTES
}
