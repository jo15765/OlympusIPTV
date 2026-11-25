package com.olympus.iptv.playlist

import com.olympus.iptv.OlympusIptvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URI

object HostWarmup {
    @Volatile private var warmedFor: String? = null

    fun launch(app: OlympusIptvApp, playlistId: String, favoriteKeys: List<String>) {
        if (playlistId.isBlank() || favoriteKeys.isEmpty() || warmedFor == playlistId) return
        if (MeteredNetwork.limited(app)) return
        warmedFor = playlistId
        app.appScope.launch(Dispatchers.IO) {
            val liveIds = favoriteKeys.asSequence()
                .filter { it.startsWith("live:") }
                .map { it.substringAfter(':') }
                .filter { it.isNotBlank() }
                .distinct()
                .take(6)
                .toList()
            if (liveIds.isEmpty()) return@launch
            val channels = runCatching {
                app.catalogDb.readChannelsByIds(playlistId, CatalogDb.LIVE, liveIds)
            }.getOrDefault(emptyList())
            val hosts = ArrayList<String>(3)
            for (channel in channels) {
                val host = runCatching { URI(channel.streamUrl).host }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: continue
                if (host !in hosts) hosts.add(host)
                if (hosts.size == 3) break
            }
            HttpClients.warm(hosts)
        }
    }
}
