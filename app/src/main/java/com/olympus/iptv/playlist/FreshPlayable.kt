package com.olympus.iptv.playlist

import com.olympus.iptv.data.SavedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps New/Just Added cards that have a playable stream in this playlist. */
object FreshPlayable {
    private val gate = Semaphore(4)

    suspend fun filter(
        filesDir: File,
        playlist: SavedPlaylist,
        catalogDb: CatalogDb,
        cards: List<CatalogDb.FreshTitle>
    ): List<CatalogDb.FreshTitle> {
        if (cards.isEmpty()) return emptyList()
        return coroutineScope {
            cards.map { card ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (playable(filesDir, playlist, catalogDb, card)) card else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun playable(
        filesDir: File,
        playlist: SavedPlaylist,
        catalogDb: CatalogDb,
        card: CatalogDb.FreshTitle
    ): Boolean = withContext(Dispatchers.IO) {
        if (card.bucket == CatalogDb.SERIES) {
            val channel = runCatching {
                catalogDb.readChannel(playlist.id, CatalogDb.SERIES, card.id)
            }.getOrNull() ?: return@withContext false
            val seriesId = channel.seriesId?.takeIf { it.isNotBlank() } ?: channel.id
            if (seriesId.isBlank()) return@withContext false
            val want = card.season?.takeIf { it > 0 }
            val cached = runCatching {
                EpisodeCache.peek(filesDir, playlist.id, seriesId)
            }.getOrNull()?.filter { it.streamUrl.isNotBlank() }
            if (!cached.isNullOrEmpty()) {
                if (want == null) return@withContext true
                return@withContext cached.any { (it.season ?: 1) == want }
            }
            val episodes = runCatching {
                EpisodeSequence.load(filesDir, playlist, seriesId)
            }.getOrDefault(emptyList()).filter { it.streamUrl.isNotBlank() }
            if (episodes.isEmpty()) return@withContext false
            if (want == null) return@withContext true
            return@withContext episodes.any { (it.season ?: 1) == want }
        }
        val channel = runCatching {
            catalogDb.readChannel(playlist.id, card.bucket, card.id)
        }.getOrNull()
        !channel?.streamUrl.isNullOrBlank()
    }
}
