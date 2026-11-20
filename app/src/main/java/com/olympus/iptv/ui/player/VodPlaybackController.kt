package com.olympus.iptv.ui.player

import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.WatchEntry
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.ui.browse.GuideFeel
import com.olympus.iptv.playlist.Scrobbler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PlayerActivity.tickUpNext(token: Int) {
        if (token != upNextToken) return
        val exo = player ?: return
        val channel = current ?: return
        if (!exo.isPlaying) return
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) return
        val duration = exo.duration
        val left = if (duration > 0L) duration - exo.currentPosition else Long.MAX_VALUE
        if (duration > 0L && left <= 25_000L) offerNext(channel)
        else hideHandler.postDelayed({ tickUpNext(token) }, 1_000L)
    }


internal fun PlayerActivity.armUpNext(exo: ExoPlayer, channel: Channel?) {
        val token = ++upNextToken
        if (channel == null) return
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) return
        hideHandler.postDelayed({ tickUpNext(token) }, 1_000L)
    }


internal fun PlayerActivity.offerNext(channel: Channel) {
        if (countdownFor == channel.id) return
        saveWatch(channel)
        countdownFor = channel.id
        nextHanded = false
        introJob?.cancel()
        nextIsEpisode = !channel.seriesId.isNullOrBlank()
        val seriesQueued = queue.getOrNull(index + 1)?.takeIf {
            it.id != channel.id &&
                !channel.seriesId.isNullOrBlank() &&
                it.seriesId == channel.seriesId &&
                it.streamUrl.isNotBlank()
        }
        val listQueued = queue.getOrNull(index + 1)?.takeIf {
            it.id != channel.id && it.streamUrl.isNotBlank()
        }
        pendingNext = seriesQueued ?: listQueued?.takeIf { channel.seriesId.isNullOrBlank() }
        resolvedSeries = null
        showCountdown(pendingNext)
        if (pendingNext == null && !channel.seriesId.isNullOrBlank()) {
            nextLookup = lifecycleScope.launch {
                val savedId = playlistId
                val seriesId = channel.seriesId
                val saved = if (savedId.isNullOrBlank() || seriesId.isNullOrBlank()) null else savedPlaylist(savedId)
                val episodes = if (saved == null || seriesId.isNullOrBlank()) emptyList()
                else EpisodeSequence.load(filesDir, saved, seriesId)
                if (!isActive || countdownFor != channel.id || nextHanded) return@launch
                val ordered = EpisodeSequence.ordered(episodes)
                resolvedSeries = ordered
                val next = EpisodeSequence.nextAfter(ordered, channel)
                pendingNext = next
                nextIsEpisode = next != null
                if (next == null) {
                    val after = queue.getOrNull(index + 1)?.takeIf {
                        it.id != channel.id && it.streamUrl.isNotBlank()
                    }
                    pendingNext = after
                    nextIsEpisode = false
                }
                if (pendingNext == null) {
                    countdownJob?.cancel()
                    hideNextPanel()
                    return@launch
                }
                styleNextPanel(pendingNext)
            }
        } else if (pendingNext == null) {
            hideNextPanel()
            return
        }
        countdownJob = lifecycleScope.launch {
            for (second in 10 downTo 1) {
                if (!isActive || nextHanded) return@launch
                binding.nextCount.text = getString(
                    if (nextIsEpisode) R.string.next_episode_in else R.string.next_title_in,
                    second
                )
                delay(1_000)
            }
            nextLookup?.join()
            val next = pendingNext
            if (next == null || nextHanded) {
                if (!nextHanded) hideNextPanel()
                return@launch
            }
            openNext(next, skipIntro = shouldAutoSkip(next))
        }
    }


internal fun PlayerActivity.showCountdown(next: Channel?) {
        binding.nextPanel.visibility = View.VISIBLE
        binding.nextCount.visibility = View.VISIBLE
        styleNextPanel(next)
        val focus = if (binding.btnSkipIntro.visibility == View.VISIBLE) {
            binding.btnSkipIntro
        } else {
            binding.btnPlayNext
        }
        focus.post { focus.requestFocus() }
    }


internal fun PlayerActivity.styleNextPanel(next: Channel?) {
        if (next == null) {
            binding.nextName.visibility = View.GONE
        } else {
            binding.nextName.visibility = View.VISIBLE
            binding.nextName.text = if (nextIsEpisode) {
                EpisodeSequence.heading("", next)
            } else {
                next.name
            }
        }
        binding.btnSkipIntro.visibility = if (nextIsEpisode && next != null) View.VISIBLE else View.GONE
        binding.btnPlayNext.visibility = if (!nextIsEpisode && next != null) View.VISIBLE else View.GONE
        binding.btnCancelNext.visibility = if (next != null) View.VISIBLE else View.GONE
        binding.btnNextMenu.visibility = View.VISIBLE
        val art = next?.logoUrl?.takeIf { it.isNotBlank() }
        if (art == null) {
            binding.nextArt.visibility = View.GONE
        } else {
            binding.nextArt.visibility = View.VISIBLE
            binding.nextArt.load(art)
        }
        binding.btnSkipIntro.nextFocusDownId = binding.btnCancelNext.id
        binding.btnPlayNext.nextFocusDownId = binding.btnCancelNext.id
        binding.btnCancelNext.nextFocusUpId =
            if (binding.btnSkipIntro.visibility == View.VISIBLE) binding.btnSkipIntro.id else binding.btnPlayNext.id
        binding.btnCancelNext.nextFocusDownId = binding.btnNextMenu.id
        binding.btnNextMenu.nextFocusUpId = binding.btnCancelNext.id
    }


internal fun PlayerActivity.playPendingNext() {
        lifecycleScope.launch {
            nextLookup?.join()
            val next = pendingNext
            if (next == null) {
                hideNextPanel()
                return@launch
            }
            openNext(next, skipIntro = false)
        }
    }


internal fun PlayerActivity.escapeToMenu() {
        cancelNext()
        PlayerActivity.armBrowseBack()
        finish()
    }


internal fun PlayerActivity.showSkipButton() {
        binding.nextPanel.visibility = View.VISIBLE
        binding.nextCount.visibility = View.GONE
        binding.nextName.visibility = View.GONE
        binding.btnSkipIntro.visibility = View.VISIBLE
        binding.btnPlayNext.visibility = View.GONE
        binding.btnNextMenu.visibility = View.GONE
        binding.btnSkipIntro.post { binding.btnSkipIntro.requestFocus() }
        introJob?.cancel()
        introJob = lifecycleScope.launch {
            while (isActive) {
                val exo = player
                if (exo == null) break
                val duration = exo.duration
                if (duration > 0L && duration < EpisodeSequence.INTRO_MS + 30_000L) break
                if (exo.currentPosition >= EpisodeSequence.INTRO_MS) break
                delay(400)
            }
            hideNextPanel()
        }
    }


internal fun PlayerActivity.onSkipIntro() {
        lifecycleScope.launch {
            rememberSkipIntroFor(current?.seriesId ?: pendingNext?.seriesId)
            if (nextHanded) {
                seekIntro()
                return@launch
            }
            nextLookup?.join()
            val next = pendingNext
            if (next == null) {
                hideNextPanel()
                return@launch
            }
            openNext(next, skipIntro = true)
        }
    }


internal fun PlayerActivity.rememberSkipIntroFor(seriesId: String?) {
        val id = seriesId?.trim().orEmpty()
        if (id.isEmpty() || id in skipIntroSeries) return
        skipIntroSeries = skipIntroSeries + id
        (application as OlympusIptvApp).appScope.launch {
            runCatching { (application as OlympusIptvApp).playlistStore.rememberSkipIntro(id) }
        }
    }


internal fun PlayerActivity.shouldAutoSkip(channel: Channel?): Boolean =
        GuideFeel.autoSkipIntro(channel?.seriesId, skipIntroSeries)


internal fun PlayerActivity.openNext(next: Channel, skipIntro: Boolean) {
        if (nextHanded) return
        nextHanded = true
        countdownJob?.cancel()
        val full = resolvedSeries
        if (full != null && full.any { it.id == next.id }) {
            queue = full
            (application as OlympusIptvApp).playbackQueue = full
        }
        val at = queue.indexOfFirst { it.id == next.id }
        if (at >= 0) {
            index = at
        } else {
            queue = listOf(next)
            index = 0
        }
        current = next
        stepping = true
        val auto = skipIntro || shouldAutoSkip(next)
        lifecycleScope.launch {
            val resume = resumePosition(next)
            val start = if (auto && resume < EpisodeSequence.INTRO_MS) EpisodeSequence.INTRO_MS else resume
            playCurrent(startAt = start)
            if (auto || start >= EpisodeSequence.INTRO_MS) hideNextPanel() else showSkipButton()
        }
    }


internal fun PlayerActivity.seekIntro() {
        val exo = player ?: return
        val duration = exo.duration
        if (duration > 0L && duration < EpisodeSequence.INTRO_MS + 30_000L) {
            hideNextPanel()
            return
        }
        exo.seekTo(EpisodeSequence.INTRO_MS)
        hideNextPanel()
    }


internal fun PlayerActivity.hideNextPanel() {
        binding.nextPanel.visibility = View.GONE
    }


internal fun PlayerActivity.cancelNext() {
        countdownJob?.cancel()
        nextLookup?.cancel()
        introJob?.cancel()
        pendingNext = null
        nextHanded = true
        hideNextPanel()
    }


internal fun PlayerActivity.scrobbleStart(channel: Channel) {
        scrobbleJob?.cancel()
        stopScrobbleIfStarted()
        if (scrobble.provider == Scrobbler.NONE || scrobble.activeToken().isBlank()) return
        scrobbleChannel = channel
        scrobbleStarted = false
        val config = scrobble
        scrobbleJob = lifecycleScope.launch {
            delay(1_500L)
            if (!isActive || isDestroyed || current?.id != channel.id) return@launch
            scrobbleStarted = true
            withContext(Dispatchers.IO) {
                runCatching {
                    Scrobbler.start(config.provider, config.activeToken(), config.activeClientId(), channel)
                }
            }
        }
    }


internal fun PlayerActivity.scrobbleStop() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        stopScrobbleIfStarted()
    }


internal fun PlayerActivity.stopScrobbleIfStarted() {
        val channel = scrobbleChannel ?: return
        val started = scrobbleStarted
        scrobbleChannel = null
        scrobbleStarted = false
        if (!started) return
        if (scrobble.provider == Scrobbler.NONE || scrobble.activeToken().isBlank()) return
        val config = scrobble
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                Scrobbler.stop(config.provider, config.activeToken(), config.activeClientId(), channel)
            }
        }
    }
