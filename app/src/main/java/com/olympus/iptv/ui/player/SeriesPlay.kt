package com.olympus.iptv.ui.player

import android.app.Activity
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.Channel
import com.olympus.iptv.playlist.EpisodeSequence

object SeriesPlay {
    fun open(activity: Activity, playlistId: String, episodes: List<Channel>, episode: Channel) {
        val ordered = EpisodeSequence.ordered(episodes)
        val index = ordered.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val playing = ordered.getOrElse(index) { episode }
        val app = activity.application as OlympusIptvApp
        app.playbackQueue = ordered
        activity.startActivity(
            PlayerActivity.intent(
                activity,
                channel = playing,
                channelMeta = playing.blurb.orEmpty(),
                channelNext = "",
                startIndex = index,
                playlistId = playlistId,
                zapMode = PlayerActivity.ZAP_LIST
            )
        )
    }
}
