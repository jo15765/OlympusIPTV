package com.olympus.iptv.playlist

import android.content.Context
import android.net.ConnectivityManager

/** True when the active network is cellular or otherwise metered. */
object MeteredNetwork {
    @Volatile private var cached = false
    @Volatile private var checkedAt = 0L

    fun limited(context: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - checkedAt < 3_000L) return cached
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val metered = manager.isActiveNetworkMetered
        cached = metered
        checkedAt = now
        return metered
    }
}
