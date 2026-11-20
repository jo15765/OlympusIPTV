package com.olympus.iptv.playlist

import com.olympus.iptv.OlympusIptvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URI

/** Pulls the first few favorite stream hosts and warms DNS plus HTTP keep-alive. */
object HostWarmup {
    @Volatile private var warmedFor: String? = null

    fun launch(app: OlympusIptvApp, playlistId: String, favoriteKeys: List<String>) {
        if (playlistId.isBlank() || favoriteKeys.isEmpty() || warmedFor == playlistId) return
        warmedFor = playlistId
        app.appScope.launch(Dispatchers.IO) {
            val hosts = ArrayList<String>(3)
            for (key in favoriteKeys) {
                if (!key.startsWith("live:")) continue
                val id = key.substringAfter(':')
                val channel = runCatching {
                    app.catalogDb.readChannel(playlistId, CatalogDb.LIVE, id)
                }.getOrNull() ?: continue
                val host = runCatching { URI(channel.streamUrl).host }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: continue
                if (host !in hosts) hosts.add(host)
                if (hosts.size == 3) break
            }
            HttpClients.warm(hosts)
        }
    }
}
