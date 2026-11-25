package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.data.ShowNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object EpisodeSequence {
    const val INTRO_MS = 60_000L

    fun ordered(episodes: List<Channel>): List<Channel> {
        if (episodes.size <= 1) return episodes

        val cached = orderedCache
        if (cached != null && cached.first === episodes) return cached.second
        val rows = episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.number ?: Int.MAX_VALUE }, { it.id }))
        orderedCache = episodes to rows
        return rows
    }

    @Volatile private var orderedCache: Pair<List<Channel>, List<Channel>>? = null

    fun nextAfter(episodes: List<Channel>, current: Channel): Channel? {
        val rows = ordered(episodes)
        val at = rows.indexOfFirst { it.id == current.id }
        if (at >= 0) return rows.getOrNull(at + 1)
        val season = current.season ?: 1
        val number = current.number ?: 0
        return rows.firstOrNull { (it.season ?: 1) > season }
            ?: rows.firstOrNull { (it.season ?: 1) == season && (it.number ?: 0) > number }
    }

    fun heading(showName: String, episode: Channel): String =
        ShowNames.episodeHeading(showName, episode.season ?: 1, episode.number ?: 1, episode.name)

    suspend fun fetch(playlist: SavedPlaylist, seriesId: String): List<Channel> =
        runCatching { XtreamClient().seriesEpisodes(playlist, seriesId) }
            .getOrDefault(emptyList())
            .map { it.copy(seriesId = seriesId) }

    suspend fun load(
        filesDir: File,
        playlist: SavedPlaylist,
        seriesId: String,
        refresh: CoroutineScope? = null
    ): List<Channel> {
        val cached = withContext(Dispatchers.IO) { EpisodeCache.peek(filesDir, playlist.id, seriesId) }
        if (!cached.isNullOrEmpty()) {
            refresh?.launch(Dispatchers.IO) {
                val fresh = fetch(playlist, seriesId)
                if (fresh.isNotEmpty()) EpisodeCache.store(filesDir, playlist.id, seriesId, fresh)
            }
            return cached
        }
        val fresh = fetch(playlist, seriesId)
        if (fresh.isNotEmpty()) {
            withContext(Dispatchers.IO) { EpisodeCache.store(filesDir, playlist.id, seriesId, fresh) }
        }
        return fresh
    }
}
