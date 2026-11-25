package com.olympus.iptv.portal

import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.Channel
import com.olympus.iptv.playlist.CatalogDb
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

object RemotePad {
    data class Command(val action: String, val text: String = "")

    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 16)

    fun post(action: String, text: String = ""): Boolean {
        return commands.tryEmit(Command(action.trim().lowercase(), text))
    }

    suspend fun resolveChannel(app: OlympusIptvApp, playlistId: String?, rawId: String): Pair<String, Channel>? {
        val trimmed = rawId.trim()
        if (trimmed.isEmpty()) return null
        val savedId = playlistId?.takeIf { it.isNotBlank() }
            ?: app.playlistStore.playlists.first().firstOrNull()?.id
            ?: return null
        val prefix = trimmed.substringBefore(':', "").lowercase()
        val bare = if (trimmed.contains(':')) trimmed.substringAfter(':') else trimmed
        val buckets = when (prefix) {
            "vod" -> listOf(CatalogDb.VOD, CatalogDb.LIVE, CatalogDb.SERIES)
            "series" -> listOf(CatalogDb.SERIES, CatalogDb.VOD, CatalogDb.LIVE)
            "live" -> listOf(CatalogDb.LIVE, CatalogDb.VOD, CatalogDb.SERIES)
            else -> listOf(CatalogDb.LIVE, CatalogDb.VOD, CatalogDb.SERIES)
        }
        val db = app.catalogDb
        for (bucket in buckets) {
            db.readChannel(savedId, bucket, bare)?.let { return savedId to it }
            if (bare != trimmed) {
                db.readChannel(savedId, bucket, trimmed)?.let { return savedId to it }
            }
        }
        return null
    }
}
