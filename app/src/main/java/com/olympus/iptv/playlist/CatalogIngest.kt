package com.olympus.iptv.playlist

import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as BatchChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object CatalogIngest {
    class Progress {
        @Volatile var live: Int = 0
        @Volatile var movies: Int = 0
        @Volatile var shows: Int = 0

        fun overall(includeVod: Boolean): Int {
            val value = if (includeVod) (live + movies + shows) / 3 else live
            val capped = value.coerceIn(0, 100)
            if (capped >= 100) return 100
            return (capped / 5) * 5
        }
    }

    val progress = Progress()
    private val loader = PlaylistLoader()
    private val settled = HashSet<String>()

    fun alreadySettled(playlistId: String): Boolean = synchronized(settled) { playlistId in settled }

    fun start(app: OlympusIptvApp, playlist: SavedPlaylist): CompletableDeferred<Boolean> {
        val running = app.catalogRefresh
        val ready = app.catalogLiveReady
        if (running?.isActive == true && ready != null && !ready.isCompleted) return ready
        progress.live = 0
        progress.movies = 0
        progress.shows = 0
        val liveReady = CompletableDeferred<Boolean>()
        app.catalogLiveReady = liveReady
        app.catalogRefresh = app.appScope.launch {
            try {
                ingest(app, playlist, liveReady)
            } catch (cancelled: CancellationException) {
                if (!liveReady.isCompleted) liveReady.cancel(cancelled)
                throw cancelled
            } catch (_: Exception) {
                if (!liveReady.isCompleted) liveReady.complete(false)
            }
        }
        return liveReady
    }

    private suspend fun ingest(
        app: OlympusIptvApp,
        playlist: SavedPlaylist,
        liveReady: CompletableDeferred<Boolean>
    ) {
        val db = app.catalogDb
        var reached = false
        val international = app.playlistStore.lineup(playlist.id).includeInternational
        val scoped = playlist.copy(includeInternational = international)
        try {
            if (playlist.type != PlaylistType.XTREAM) {
                val fresh = runCatching { CountryIndex.annotate(loader.load(scoped)) }.getOrNull()
                if (fresh == null) {
                    liveReady.complete(false)
                    return
                }
                StreamHeaders.remember(app, scoped.id, fresh.liveChannels + fresh.vodChannels)
                store(app, db, scoped.id, CatalogDb.LIVE, fresh.liveChannels, true, db.knownChannelIds(scoped.id, CatalogDb.LIVE))
                db.insertMissing(scoped.id, CatalogDb.LIVE, fresh.liveCategories, emptyList())
                reached = true
                liveReady.complete(true)
                store(app, db, scoped.id, CatalogDb.VOD, fresh.vodChannels, true, db.knownChannelIds(scoped.id, CatalogDb.VOD))
                db.insertMissing(scoped.id, CatalogDb.VOD, fresh.vodCategories, emptyList())
                return
            }
            coroutineScope {
                val liveJob = async { pullLive(app, db, scoped) }
                val showJob = async { pullShows(app, db, scoped) }
                val movieJob = async { pullMovies(app, db, scoped) }
                val live = liveJob.await()
                reached = live || reached
                if (!liveReady.isCompleted) liveReady.complete(live)
                if (showJob.await()) reached = true
                if (movieJob.await()) reached = true
            }
        } finally {
            if (!liveReady.isCompleted) liveReady.complete(false)
            if (reached) {
                synchronized(settled) { settled.add(playlist.id) }
                app.playlistStore.saveLastRefreshAt(playlist.id, System.currentTimeMillis())
            }
        }
    }

    private suspend fun pullLive(app: OlympusIptvApp, db: CatalogDb, playlist: SavedPlaylist): Boolean {
        val known = db.knownChannelIds(playlist.id, CatalogDb.LIVE)
        val loaded = runCatching {
            pipeBatches(
                write = { batch -> store(app, db, playlist.id, CatalogDb.LIVE, batch, true, known) }
            ) { send ->
                loader.loadLive(playlist, onPercent = { progress.live = it.coerceIn(0, 99) }) { batch -> send(batch) }
            }
        }.getOrNull() ?: return false
        progress.live = 100
        db.insertMissing(playlist.id, CatalogDb.LIVE, loaded.liveCategories, emptyList())
        store(app, db, playlist.id, CatalogDb.LIVE, loaded.liveChannels, true, known)
        return true
    }

    private suspend fun pullShows(app: OlympusIptvApp, db: CatalogDb, playlist: SavedPlaylist): Boolean {
        val known = db.knownChannelIds(playlist.id, CatalogDb.SERIES)
        val loaded = runCatching {
            pipeBatches(
                write = { batch -> store(app, db, playlist.id, CatalogDb.SERIES, batch, false, known) }
            ) { send ->
                loader.loadShows(playlist, onPercent = { progress.shows = it.coerceIn(0, 99) }) { batch -> send(batch) }
            }
        }.getOrNull() ?: return false
        progress.shows = 100
        if (loaded.seriesLoadFailed) return false
        db.insertMissing(playlist.id, CatalogDb.SERIES, loaded.seriesCategories.orEmpty(), emptyList())
        store(app, db, playlist.id, CatalogDb.SERIES, loaded.seriesChannels.orEmpty(), false, known)
        return true
    }

    private suspend fun pullMovies(app: OlympusIptvApp, db: CatalogDb, playlist: SavedPlaylist): Boolean {
        val known = db.knownChannelIds(playlist.id, CatalogDb.VOD)
        var loaded = runCatching {
            pipeBatches(
                write = { batch -> store(app, db, playlist.id, CatalogDb.VOD, batch, true, known) }
            ) { send ->
                CountryIndex.annotate(
                    loader.loadMovies(playlist, onPercent = { progress.movies = it.coerceIn(0, 99) }) { batch ->
                        send(batch)
                    }
                )
            }
        }.getOrNull()
        if (loaded != null && loaded.vodLoadFailed && loaded.vodChannels.isEmpty()) {
            loaded = runCatching {
                pipeBatches(
                    write = { batch -> store(app, db, playlist.id, CatalogDb.VOD, batch, true, known) }
                ) { send ->
                    CountryIndex.annotate(
                        loader.loadMovies(playlist, onPercent = { progress.movies = it.coerceIn(0, 99) }) { batch ->
                            send(batch)
                        }
                    )
                }
            }.getOrNull()
        }
        if (loaded == null || loaded.vodLoadFailed) return false
        progress.movies = 100
        db.insertMissing(playlist.id, CatalogDb.VOD, loaded.vodCategories, emptyList())
        store(app, db, playlist.id, CatalogDb.VOD, loaded.vodChannels, true, known)
        return true
    }

    private fun store(
        app: OlympusIptvApp,
        db: CatalogDb,
        playlistId: String,
        bucket: String,
        channels: List<Channel>,
        tag: Boolean,
        known: MutableSet<String>
    ) {
        if (channels.isEmpty()) return
        val cache = CatalogCache(app)
        val previous = cache.readHashes(playlistId, bucket)
        val changed = CatalogDelta.writes(previous, channels, known)
        if (changed.isNotEmpty()) {
            val rows = if (tag) CountryIndex.tagChannels(changed) else changed
            db.insertMissing(playlistId, bucket, emptyList(), rows)
            for (channel in changed) known.add(channel.id)
        }
        val next = HashMap(previous)
        for (channel in channels) next[channel.id] = CatalogDelta.rowHash(channel)
        cache.writeHashes(playlistId, bucket, next)
    }

    private suspend fun <T> pipeBatches(
        write: (List<Channel>) -> Unit,
        read: suspend ((List<Channel>) -> Unit) -> T
    ): T = coroutineScope {
        val incoming = BatchChannel<List<Channel>>(4)
        val writer = launch(Dispatchers.IO) {
            for (batch in incoming) write(batch)
        }
        val result = try {
            read { batch ->
                while (!incoming.trySend(batch).isSuccess) {
                    Thread.sleep(2L)
                }
            }
        } finally {
            incoming.close()
        }
        writer.join()
        result
    }
}
