package com.olympus.iptv.ui.player

import android.content.Context
import com.olympus.iptv.data.Channel

/** Keeps the old call site and sends the title to the Android TV home row. */
object WatchNext {
    fun publish(context: Context, channel: Channel, playlistId: String?) {
        TvLauncherBridge.publishContinue(context, channel, playlistId, 0L, 0L)
    }
}
