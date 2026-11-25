package com.olympus.iptv.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.record.RecordPrompt
import kotlinx.coroutines.launch

internal const val ACTION_PIP_TOGGLE_PLAY = "com.olympus.iptv.action.PIP_TOGGLE_PLAY"
internal const val ACTION_PIP_CLOSE = "com.olympus.iptv.action.PIP_CLOSE"
private const val REQ_PIP_PLAY = 4101
private const val REQ_PIP_CLOSE = 4102

internal fun PlayerActivity.stopForSleep() {
    if (isDestroyed) return
    player?.playWhenReady = false
    finish()
}

internal fun PlayerActivity.canEnterPip(): Boolean {
    val tvPip = Build.VERSION.SDK_INT >= 33 && com.olympus.iptv.ui.DeviceForm.isHighEndTv(this)
    if (!touchPrimary && !tvPip) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}

@RequiresApi(Build.VERSION_CODES.O)
internal fun PlayerActivity.buildPipParams(): PictureInPictureParams {
    val playing = player?.playWhenReady != false
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val toggleIntent = PendingIntent.getBroadcast(
        this,
        REQ_PIP_PLAY,
        Intent(ACTION_PIP_TOGGLE_PLAY).setPackage(packageName),
        flags
    )
    val closeIntent = PendingIntent.getBroadcast(
        this,
        REQ_PIP_CLOSE,
        Intent(ACTION_PIP_CLOSE).setPackage(packageName),
        flags
    )
    val toggleAction = RemoteAction(
        Icon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        ),
        if (playing) "Pause" else "Play",
        if (playing) "Pause playback" else "Resume playback",
        toggleIntent
    )
    val closeAction = RemoteAction(
        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
        "Close",
        "Close player",
        closeIntent
    )
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setActions(listOf(toggleAction, closeAction))
    if (Build.VERSION.SDK_INT >= 31) {
        builder.setAutoEnterEnabled(true)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

internal fun PlayerActivity.refreshPipActions() {
    if (!inPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isDestroyed) return
    runCatching { setPictureInPictureParams(buildPipParams()) }
}

internal fun PlayerActivity.enterPip(manual: Boolean) {
    if (!canEnterPip()) {
        if (manual) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    hidePlaybackMenu()
    binding.overlay.visibility = View.GONE
    binding.phoneDock.visibility = View.GONE
    pipPending = true
    runCatching { enterPictureInPictureMode(buildPipParams()) }
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
