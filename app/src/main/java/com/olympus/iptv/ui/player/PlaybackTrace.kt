package com.olympus.iptv.ui.player

import androidx.media3.common.PlaybackException
import com.olympus.iptv.AppLog
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import java.net.URI

object PlaybackTrace {
    fun start(channel: Channel) {
        AppLog.info(
            "Player",
            "Start ${label(channel)} kind=${kind(channel)} ${streamHint(channel.streamUrl)}"
        )
    }

    fun spool(channel: Channel?, initial: Boolean, waitedMs: Long) {
        val phase = if (initial) "initial-spool" else "rebuffer"
        val hint = if (initial) {
            "startup fill (app buffers or slow first bytes from provider)"
        } else {
            "mid-play stall (often provider/CDN or network)"
        }
        AppLog.info(
            "Player",
            "$phase ${waitedMs}ms ${label(channel)} ${streamHint(channel?.streamUrl)} hint=$hint"
        )
    }

    fun stillBuffering(channel: Channel?, waitedMs: Long) {
        AppLog.info(
            "Player",
            "still-spooling ${waitedMs}ms ${label(channel)} ${streamHint(channel?.streamUrl)} " +
                "hint=provider/network slow or app waiting on underfilled buffer"
        )
    }

    fun error(channel: Channel?, error: PlaybackException, recovering: Boolean) {
        val action = if (recovering) "recovering" else "failed"
        AppLog.error(
            "Player",
            "Playback $action code=${error.errorCodeName} (${error.errorCode}) " +
                "cause=${likelyCause(error.errorCode)} ${label(channel)} ${streamHint(channel?.streamUrl)} " +
                "msg=${error.message.orEmpty()}",
            error
        )
    }

    fun recover(channel: Channel, attempt: Int, behindLive: Boolean) {
        AppLog.info(
            "Player",
            "Recover attempt=$attempt behindLive=$behindLive ${label(channel)} ${streamHint(channel.streamUrl)} " +
                "hint=${if (behindLive) "live window lag (often provider)" else "reload after stream error"}"
        )
    }

    fun blankUrl(channel: Channel) {
        AppLog.error(
            "Player",
            "No stream URL ${label(channel)} hint=catalog/provider missing address"
        )
    }

    fun loadFailed(channel: Channel?, status: Int?, message: String?) {
        AppLog.error(
            "Player",
            "Load error http=${status ?: "-"} ${message.orEmpty()} ${label(channel)} " +
                "${streamHint(channel?.streamUrl)} hint=likely provider/CDN HTTP or network"
        )
    }

    fun likelyCause(error: PlaybackException): String = likelyCause(error.errorCode)

    fun likelyCause(code: Int): String {
        return when {
            code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "live window (often provider lag)"
            code in 2_000..2_999 -> "likely provider/network IO"
            code in 3_000..3_999 -> "likely provider manifest/container"
            code in 4_000..4_999 -> "likely device/app decoder"
            code in 5_000..5_999 -> "likely device/app audio track"
            code in 6_000..6_999 -> "DRM (provider license or device)"
            else -> "unclear (review stack for app vs provider)"
        }
    }

    fun streamHint(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "url=-"
        return runCatching {
            val uri = URI(value)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: "unknown-host"
            val path = uri.rawPath.orEmpty()
            val safePath = CREDENTIAL_PATH.replace(path, "/$1/***/***/").ifBlank { "/" }
            "url=$host$safePath"
        }.getOrElse { "url=unparsed" }
    }

    private val CREDENTIAL_PATH = Regex("/(live|movie|series)/[^/]+/[^/]+/")

    private fun label(channel: Channel?): String {
        if (channel == null) return "channel=-"
        return "channel=${channel.id}/${channel.name}"
    }

    private fun kind(channel: Channel): String =
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) "LIVE" else "VOD"
}
