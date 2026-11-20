package com.olympus.iptv.ui.browse

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.databinding.ActivityBrowseBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the guide corner preview / mini-PiP player so BrowseActivity does not.
 */
class BrowsePreviewController(
    private val activity: AppCompatActivity,
    private val binding: ActivityBrowseBinding,
    private val touchPrimary: Boolean,
    private val epgNowTitle: (String) -> String?,
    private val focusAllowsPreview: () -> Boolean,
    private val focusedChannelId: () -> String?
) {
    private var previewPlayer: ExoPlayer? = null
    private var previewChannelId: String? = null
    private var previewJob: Job? = null

    fun queue(channel: Channel, liveSection: Boolean) {
        if (!liveSection || channel.kind != ContentKind.LIVE || channel.streamUrl.isBlank()) return
        updateCaption(channel)
        if (touchPrimary) return
        if (channel.id == previewChannelId && previewPlayer != null) return
        previewJob?.cancel()
        previewJob = activity.lifecycleScope.launch {
            delay(400)
            if (activity.isDestroyed) return@launch
            if (focusAllowsPreview() && focusedChannelId() != null && focusedChannelId() != channel.id) return@launch
            start(channel)
        }
    }

    fun start(channel: Channel) {
        val player = previewPlayer ?: ExoPlayer.Builder(activity)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_000, 6_000, 250, 500)
                    .setTargetBufferBytes(4 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(activity).setDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setUserAgent("Olympus-IPTV")
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(20_000)
                        .setAllowCrossProtocolRedirects(true)
                )
            )
            .build()
            .also { created ->
                created.volume = 0f
                previewPlayer = created
                binding.previewPlayer.player = created
            }
        previewChannelId = channel.id
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(channel.streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(8_000)
                        .setMinOffsetMs(2_000)
                        .setMaxOffsetMs(24_000)
                        .build()
                )
                .build()
        )
        player.prepare()
        player.playWhenReady = true
        updateCaption(channel)
    }

    fun handOff(channel: Channel) {
        val warm = previewPlayer
        if (warm != null && previewChannelId == channel.id) {
            val app = activity.application as OlympusIptvApp
            app.warmPlayer = warm
            app.warmChannelId = channel.id
            previewPlayer = null
            previewChannelId = null
            previewJob?.cancel()
            binding.previewPlayer.player = null
        } else {
            stop()
        }
    }

    fun stop() {
        previewJob?.cancel()
        previewChannelId = null
        previewPlayer?.stop()
        previewPlayer?.clearMediaItems()
    }

    fun pause() {
        previewPlayer?.playWhenReady = false
    }

    fun release() {
        previewJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        previewChannelId = null
        binding.previewPlayer.player = null
    }

    fun showGuidePip(channel: Channel, onExpand: (Channel) -> Unit) {
        if (touchPrimary) return
        if (channel.kind != ContentKind.LIVE || !channel.seriesId.isNullOrBlank() || channel.streamUrl.isBlank()) return
        binding.previewCard.visibility = android.view.View.VISIBLE
        binding.previewCard.setOnClickListener { onExpand(channel) }
        binding.previewCard.isClickable = true
        binding.previewCard.isFocusable = true
        queue(channel, liveSection = true)
    }

    fun hideCard() {
        binding.previewCard.visibility = android.view.View.GONE
    }

    private fun updateCaption(channel: Channel) {
        val title = epgNowTitle(channel.id)?.takeIf { it.isNotBlank() } ?: channel.name
        binding.previewCaption.text = activity.getString(R.string.preview_on_now, title)
    }
}
