package com.olympus.iptv.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.Player
import com.olympus.iptv.playlist.MeteredNetwork
import com.olympus.iptv.ui.DeviceForm

/** Adaptive cellular video cap based on downstream bandwidth. Wi-Fi and TV stay unlimited. */
object MobileBitrateCap {
    const val CELLULAR_SLOW_BPS = 2_500_000
    const val CELLULAR_FAST_BPS = 6_000_000

    fun apply(context: Context, player: Player) {
        if (DeviceForm.isTelevision(context)) return
        val cap = if (MeteredNetwork.limited(context)) {
            val downKbps = downstreamKbps(context)
            when {
                downKbps >= 50_000 -> Int.MAX_VALUE
                downKbps >= 15_000 -> CELLULAR_FAST_BPS
                else -> CELLULAR_SLOW_BPS
            }
        } else {
            Int.MAX_VALUE
        }
        val next = player.trackSelectionParameters.buildUpon()
            .setMaxVideoBitrate(cap)
            .build()
        if (next != player.trackSelectionParameters) {
            player.trackSelectionParameters = next
        }
    }

    private fun downstreamKbps(context: Context): Int {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 0
        val network = manager.activeNetwork ?: return 0
        val caps = manager.getNetworkCapabilities(network) ?: return 0
        return caps.linkDownstreamBandwidthKbps.coerceAtLeast(0)
    }
}
