package com.olympus.iptv.ui

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

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

    /** Fire Stick / Onn-class sticks versus Shield / Streamer-class boxes. */
    fun isLowRam(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return isLowRam(manager?.isLowRamDevice == true, Runtime.getRuntime().maxMemory())
    }

    fun isLowRam(lowRamDevice: Boolean, maxMemoryBytes: Long): Boolean =
        lowRamDevice || maxMemoryBytes <= LOW_HEAP_BYTES
}
