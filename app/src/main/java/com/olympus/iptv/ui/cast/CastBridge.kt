package com.olympus.iptv.ui.cast

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.images.WebImage
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel

/**
 * Cast is initialized only after the user confirms the disclaimer — never on player open.
 */
object CastBridge {
    fun showChooser(context: Context, channel: Channel?) {
        AlertDialog.Builder(context)
            .setTitle(R.string.cast_title)
            .setMessage(R.string.cast_disclaimer)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cast_continue) { _, _ ->
                openRouteChooser(context, channel)
            }
            .show()
    }

    private fun openRouteChooser(context: Context, channel: Channel?) {
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
        val dialog = MediaRouteChooserDialog(context)
        dialog.routeSelector = selector
        dialog.setOnDismissListener {
            if (channel != null) loadOntoSession(context, channel)
        }
        dialog.show()
    }

    private fun loadOntoSession(context: Context, channel: Channel) {
        val url = channel.streamUrl.trim()
        if (url.isBlank()) {
            Toast.makeText(context, R.string.playback_no_url, Toast.LENGTH_SHORT).show()
            return
        }
        // CastContext is created here on first use only.
        runCatching {
            val cast = CastContext.getSharedInstance(context.applicationContext)
            val client = cast.sessionManager.currentCastSession?.remoteMediaClient
            if (client == null) {
                Toast.makeText(context, R.string.cast_no_session, Toast.LENGTH_SHORT).show()
                return
            }
            val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, channel.name)
                channel.logoUrl?.takeIf { it.isNotBlank() }?.let {
                    addImage(WebImage(Uri.parse(it)))
                }
            }
            val contentType = when {
                url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                else -> "video/mp4"
            }
            val info = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType)
                .setMetadata(meta)
                .build()
            client.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .build()
            )
            Toast.makeText(context, R.string.cast_started, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, R.string.cast_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
