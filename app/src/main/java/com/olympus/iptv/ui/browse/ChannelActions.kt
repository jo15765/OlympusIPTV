package com.olympus.iptv.ui.browse

import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.ui.browse.BrowseActivity.GuideSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BrowseActivity.showRowOptions(seed: com.olympus.iptv.data.Channel? = null) {
    val now = android.os.SystemClock.uptimeMillis()
    if (now - rowMenuAt < 400L) return
    rowMenuAt = now
    val channel = seed
        ?: (currentFocus?.tag as? ProgramGuideAdapter.Slot)?.let { visibleChannels.getOrNull(it.row) }
        ?: focusedChannel
        ?: return
    val saved = playlist ?: return
    val queueItem = WatchQueue.itemFor(channel, section == GuideSection.SHOWS)
    val app = application as OlympusIptvApp
    lifecycleScope.launch {
        val queued = queueItem != null && withContext(Dispatchers.IO) {
            app.playlistStore.queue(saved.id).any { it.bucket == queueItem.bucket && it.id == queueItem.id }
        }
        if (isDestroyed) return@launch
        val favorite = Favorites.matches(channel, favoriteIds)
        val labels = ArrayList<String>()
        val actions = ArrayList<Int>()
        labels += getString(R.string.fresh_play)
        actions += 8
        labels += getString(if (favorite) R.string.guide_remove_favorite else R.string.guide_add_favorite)
        actions += 0
        if (section == GuideSection.TV) {
            labels += getString(R.string.guide_hide_channel)
            actions += 9
        }
        if (section == GuideSection.TV && !touchPrimary) {
            labels += getString(R.string.guide_move_up)
            actions += 1
            labels += getString(R.string.guide_move_down)
            actions += 2
        }
        labels += getString(R.string.record)
        actions += 3
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) {
            labels += getString(R.string.remind_me)
            actions += 4
        }
        if (queueItem != null) {
            labels += getString(if (queued) R.string.queue_remove else R.string.queue_add)
            actions += 5
        }
        if (section != GuideSection.TV) {
            labels += getString(R.string.vod_ratings_reviews)
            actions += 6
        }
        if (touchPrimary) {
            labels += getString(R.string.open_in_pip)
            actions += 7
            if (section != GuideSection.TV && channel.streamUrl.isNotBlank()) {
                labels += "📥 Download for Offline"
                actions += 10
            }
        }
        if (channel.streamUrl.isNotBlank()) {
            labels += "▶ Open in External Player"
            actions += 11
        }
        val runAction: (Int) -> Unit = { action ->
            when (action) {
                0 -> toggleGuideFavorite(channel)
                1 -> moveFavorite(channel, -1)
                2 -> moveFavorite(channel, 1)
                3 -> offerRecording(channel)
                4 -> offerReminder(channel)
                5 -> if (queueItem != null) {
                    app.appScope.launch {
                        if (queued) app.playlistStore.removeQueue(saved.id, queueItem.bucket, queueItem.id)
                        else app.playlistStore.addQueue(saved.id, queueItem)
                    }
                }
                6 -> openVodRatings(channel)
                7 -> play(channel, enterPip = true)
                8 -> play(channel)
                10 -> {
                    com.olympus.iptv.record.OfflineDownloadManager.enqueue(
                        this@showRowOptions,
                        channel.name,
                        channel.id,
                        channel.streamUrl
                    )
                    android.widget.Toast.makeText(
                        this@showRowOptions,
                        "Downloading ${channel.name} for offline viewing…",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                11 -> {
                    val pkg = com.olympus.iptv.data.ProFeatureStore.playback(this@showRowOptions).externalPlayerPackage
                    val launched = com.olympus.iptv.ui.player.ExternalPlayerBridge.launch(
                        this@showRowOptions,
                        channel,
                        pkg
                    )
                    if (!launched) {
                        android.widget.Toast.makeText(
                            this@showRowOptions,
                            "No external player app found",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                9 -> hideGuideChannel(channel)
            }
        }
        com.olympus.iptv.ui.GuideActionSheet.show(screen(), channel.name, labels) { which ->
            runAction(actions[which])
        }
    }
}
