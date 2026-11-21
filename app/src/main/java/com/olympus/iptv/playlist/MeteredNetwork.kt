package com.olympus.iptv.playlist

import android.content.Context
import android.net.ConnectivityManager

/** True when the active network is cellular or otherwise metered. */
object MeteredNetwork {
    fun limited(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.isActiveNetworkMetered
    }
}
