package com.olympus.iptv.ui.player

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.record.RecordPrompt
import kotlinx.coroutines.launch

/** Picture-in-picture, sleep stop, and DVR prompt kept off the player activity body. */
internal fun PlayerActivity.stopForSleep() {
    if (isDestroyed) return
    player?.playWhenReady = false
    finish()
}

internal fun PlayerActivity.canEnterPip(): Boolean {
    if (!touchPrimary) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}

internal fun PlayerActivity.enterPip(manual: Boolean) {
    if (!canEnterPip()) {
        if (manual) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    hidePlaybackMenu()
    binding.overlay.visibility = View.GONE
    pipPending = true
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    runCatching { enterPictureInPictureMode(params) }
        .onFailure {
            pipPending = false
            if (manual) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        }
}

internal fun PlayerActivity.offerRecording() {
    val channel = current ?: return
    val savedId = playlistId ?: return
    lifecycleScope.launch {
        val playlist = savedPlaylist(savedId) ?: return@launch
        val epg = runCatching { epgRepo.nowNext(playlist, channel) }.getOrNull()
        if (isDestroyed) return@launch
        RecordPrompt.show(this@offerRecording, savedId, channel, epg?.now, epg?.next)
    }
}

internal fun PlayerActivity.liveNow(channel: com.olympus.iptv.data.Channel? = current): Boolean =
    channel != null && channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()
