package com.olympus.iptv.data

import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.playlist.ChannelFailover

object MagicCleanup {
    data class Result(val renamed: Int, val favorites: Int)

    suspend fun run(app: OlympusIptvApp, playlistId: String): Result {
        val store = app.playlistStore
        val db = app.catalogDb
        val config = store.lineup(playlistId)
        val prefixes = NameStrip.merge(config.namePrefixes, NameStrip.sanitizeStreams)
        val renamed = db.rewriteNames(playlistId, prefixes, config.nameSuffixes.orEmpty())
        store.saveLineup(playlistId, config.copy(namePrefixes = prefixes))
        val guide = db.liveGuide(playlistId, 4000)
        val picks = (AutoFavorites.nationalPicks(guide) + AutoFavorites.sportsPicks(guide))
        val seen = HashSet<String>()
        var added = 0
        for (pick in picks) {
            val stem = ChannelFailover.stem(pick.name)
            if (stem.isNotBlank() && !seen.add(stem)) continue
            val key = "live:${pick.id}"
            val already = store.favorites(playlistId)
            if (key in already || pick.id in already) continue
            store.setFavorite(playlistId, key, true)
            added += 1
            if (added >= 24) break
        }
        return Result(renamed, added)
    }
}
