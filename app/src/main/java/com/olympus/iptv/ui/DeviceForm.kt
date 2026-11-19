package com.olympus.iptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceForm {
    fun isTelevision(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun isTouchPrimary(context: Context): Boolean {
        if (isTelevision(context)) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
