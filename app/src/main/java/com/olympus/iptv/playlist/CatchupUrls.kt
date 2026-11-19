package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CatchupUrls {
    private val xtreamStart = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun url(playlist: SavedPlaylist, channel: Channel, program: EpgProgram): String? {
        if (!channel.supportsCatchup()) return null
        val days = channel.catchupDays ?: channel.tvArchiveDuration
        if (days != null && days > 0) {
            val oldest = System.currentTimeMillis() - days * 86_400_000L
            if (program.startMillis < oldest) return null
        }
        val durationMs = (program.endMillis - program.startMillis).coerceAtLeast(60_000L)
        val durationSec = (durationMs / 1000L).toInt()
        val durationMin = ((durationMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        val startSec = program.startMillis / 1000L
        if (channel.tvArchive == true && playlist.type == PlaylistType.XTREAM) {
            val host = playlist.xtreamHost?.trim()?.trimEnd('/') ?: return null
            val user = playlist.xtreamUsername?.trim().orEmpty()
            val pass = playlist.xtreamPassword.orEmpty()
            if (user.isBlank() || pass.isBlank()) return null
            val stamp = xtreamStart.format(Date(program.startMillis))
            return "$host/timeshift/$user/$pass/$durationMin/$stamp/${channel.id}.ts"
        }
        val type = channel.catchupType?.trim()?.lowercase(Locale.US).orEmpty()
        if (type.isEmpty() && channel.tvArchive != true) return null
        val source = channel.catchupSource?.trim().orEmpty()
        if (source.isNotEmpty()) {
            return source
                .replace("\${start}", startSec.toString(), ignoreCase = true)
                .replace("\${timestamp}", startSec.toString(), ignoreCase = true)
                .replace("\${utc}", startSec.toString(), ignoreCase = true)
                .replace("\${duration}", durationSec.toString(), ignoreCase = true)
                .replace("\${offset}", durationSec.toString(), ignoreCase = true)
        }
        val live = channel.streamUrl.trim()
        if (live.isEmpty()) return null
        return when (type) {
            "shift" -> {
                val join = if (live.contains('?')) "&" else "?"
                "$live${join}utc=$startSec&lutc=$startSec"
            }
            "append" -> {
                val base = live.substringBefore('?')
                val query = live.substringAfter('?', "")
                val path = base.trimEnd('/') + "/$startSec/$durationSec"
                if (query.isEmpty()) path else "$path?$query"
            }
            else -> {
                val join = if (live.contains('?')) "&" else "?"
                "$live${join}utc=$startSec&lutc=$startSec"
            }
        }
    }
}
