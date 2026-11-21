package com.olympus.iptv.ui

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.PowerManager

object DeviceForm {
    /** Heap cap typical of 1.5–2 GB sticks. Larger boxes get a bigger runtime heap. */
    const val LOW_HEAP_BYTES = 256L * 1024L * 1024L

    fun isTelevision(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun isTouchPrimary(context: Context): Boolean {
        if (isTelevision(context)) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** Phones. Tablets (smallest width 600dp and up) keep the wider layout. */
    fun isCompact(context: Context): Boolean {
        if (isTelevision(context)) return false
        return context.resources.configuration.smallestScreenWidthDp < 600
    }

    fun isPortrait(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** Battery saver on a phone or tablet. Televisions ignore the system saver. */
    fun powerSave(context: Context): Boolean {
        if (isTelevision(context)) return false
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isPowerSaveMode
    }

    /** Poster columns: 2 on a portrait phone, 4 on a tablet, 5 on TV / wide screens. */
    fun contentColumns(context: Context): Int {
        val width = context.resources.configuration.screenWidthDp
        return when {
            isTelevision(context) || width >= 1000 -> 6
            width >= 600 -> 4
            else -> 2
        }
    }

    /** Category card columns: locked to 3 on TV set-top boxes, 2 on compact phones, 3–4 on tablets/wide screens. */
    fun categoryColumns(context: Context): Int {
        if (isTelevision(context)) return 3
        val width = context.resources.configuration.screenWidthDp
        return when {
            width >= 1100 -> 4
            width >= 600 -> 3
            else -> 2
        }
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
