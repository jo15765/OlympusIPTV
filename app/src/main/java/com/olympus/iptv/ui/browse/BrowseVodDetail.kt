package com.olympus.iptv.ui.browse

import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.playlist.TitleRatings
import com.olympus.iptv.playlist.XtreamClient
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.FOCUS_SETTLE_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

internal fun BrowseActivity.glowFrom(url: String?) {
    if (url.isNullOrBlank()) return
    val request = coil.request.ImageRequest.Builder(this)
        .data(url)
        .size(com.olympus.iptv.ui.BackdropGlow.THUMB_W, com.olympus.iptv.ui.BackdropGlow.THUMB_H)
        .target { drawable ->
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@target
            com.olympus.iptv.ui.BackdropGlow.apply(bitmap, binding.guideShell)
        }
        .build()
    coil.Coil.imageLoader(this).enqueue(request)
}

internal fun BrowseActivity.showChannelDetail(channel: Channel) {
    focusedChannel = channel
    queuePreview(channel)
    epgWait?.let { focusHandler.removeCallbacks(it) }
    epgWait = null
    if (channel.kind != ContentKind.LIVE) {
        loadTitleLine(channel)
        glowFrom(channel.logoUrl)
        return
    }
    if (epgCache.containsKey(channel.id)) {
        channelAdapter.notifyEpgChanged(channel.id)
        return
    }
    val run = Runnable {
        epgWait = null
        if (focusedChannel?.id != channel.id || epgCache.containsKey(channel.id)) return@Runnable
        detailJob?.cancel()
        val saved = playlist ?: return@Runnable
        detailJob = lifecycleScope.launch {
            val nowNext = runCatching { epgRepo.nowNext(saved, channel) }.getOrDefault(EpgNowNext())
            if (focusedChannel?.id != channel.id) return@launch
            rememberEpg(channel.id, nowNext)
            channelAdapter.notifyEpgChanged(channel.id)
            queuePreview(channel)
        }
    }
    epgWait = run
    focusHandler.postDelayed(run, FOCUS_SETTLE_MS)
}

internal fun BrowseActivity.loadTitleLine(channel: Channel) {
    val saved = playlist ?: return
    detailJob?.cancel()
    val run = object : Runnable {
        override fun run() {
            if (focusedChannel?.id != channel.id) return
            if (channelAdapter.isScrolling()) {
                focusHandler.postDelayed(this, FOCUS_SETTLE_MS)
                return
            }
            detailJob = lifecycleScope.launch {
                val series = section == BrowseActivity.GuideSection.SHOWS || !channel.seriesId.isNullOrBlank()
                val id = channel.seriesId?.takeIf { it.isNotBlank() } ?: channel.id
                val xtream = withContext(Dispatchers.IO) {
                    runCatching { XtreamClient().titleLine(saved, series, id) }.getOrDefault("")
                }
                if (focusedChannel?.id != channel.id) return@launch
                val cached = TitleRatings.peek(channel.name, series)
                if (cached != null) {
                    val line = listOf(xtream, cached.scoreLine(2)).filter { it.isNotBlank() }.joinToString(" · ")
                    if (line.isNotBlank()) channelAdapter.setDetail(channel.id, line)
                    return@launch
                }
                if (channelAdapter.isScrolling() || focusedChannel?.id != channel.id) {
                    if (xtream.isNotBlank()) channelAdapter.setDetail(channel.id, xtream)
                    return@launch
                }
                val scores = withContext(Dispatchers.IO) {
                    TitleRatings.loadScores(channel.name, preferTv = series)
                }
                if (focusedChannel?.id != channel.id) return@launch
                val line = listOf(xtream, scores.scoreLine(2)).filter { it.isNotBlank() }.joinToString(" · ")
                if (line.isNotBlank()) channelAdapter.setDetail(channel.id, line)
            }
        }
    }
    focusHandler.postDelayed(run, if (touchPrimary) 280L else FOCUS_SETTLE_MS)
}

internal fun BrowseActivity.openVodRatings(channel: Channel) {
    VodDetailSheet.showLoading(this)
    lifecycleScope.launch {
        val preferTv = section == BrowseActivity.GuideSection.SHOWS || !channel.seriesId.isNullOrBlank()
        val limit = withContext(Dispatchers.IO) {
            (application as OlympusIptvApp).playlistStore.tmdbItemsLimit()
        }
        val bundle = withContext(Dispatchers.IO) {
            TitleRatings.loadFull(channel.name, preferTv = preferTv, tmdbLimit = limit)
        }
        if (isDestroyed) return@launch
        VodDetailSheet.show(
            context = screen(),
            bundle = bundle,
            onTrailer = { openYoutubeTrailer(channel.name, preferTv) },
            onPlay = { play(channel) }
        )
    }
}
