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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val reported = HashSet<String>()

    private fun report(message: String, error: Throwable? = null) {
        val key = message.take(180)
        val fresh = synchronized(reported) { reported.add(key) }
        if (!fresh) return
        com.olympus.iptv.AppLog.error("Catalog", message, error)
    }

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
            } catch (error: Throwable) {
                report("Catalog ingest stopped: ${error.message ?: error.javaClass.simpleName}", error)
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
        val lowRam = com.olympus.iptv.ui.DeviceForm.isLowRam(app)
        db.beginBulkSearch()
        var reached = false
        val international = app.playlistStore.lineup(playlist.id).includeInternational
        val scoped = playlist.copy(includeInternational = international)
        try {
            if (playlist.type != PlaylistType.XTREAM) {
                val fresh = runCatching { CountryIndex.annotate(loader.load(scoped)) }.getOrNull()
                if (fresh == null) {
                    report("Playlist did not load for ${playlist.name}")
                    liveReady.complete(false)
                    return
                }
                StreamHeaders.remember(app, scoped.id, fresh.liveChannels + fresh.vodChannels)
                val liveHashes = CatalogCache(app).readHashes(scoped.id, CatalogDb.LIVE).toMutableMap()
                store(db, scoped.id, CatalogDb.LIVE, fresh.liveChannels, true, db.knownChannelIds(scoped.id, CatalogDb.LIVE), liveHashes)
                CatalogCache(app).writeHashes(scoped.id, CatalogDb.LIVE, liveHashes)
                db.insertMissing(scoped.id, CatalogDb.LIVE, fresh.liveCategories, emptyList())
                reached = true
                liveReady.complete(true)
                if (scoped.includeVod) {
                    val vodHashes = CatalogCache(app).readHashes(scoped.id, CatalogDb.VOD).toMutableMap()
                    store(db, scoped.id, CatalogDb.VOD, fresh.vodChannels, true, db.knownChannelIds(scoped.id, CatalogDb.VOD), vodHashes)
                    CatalogCache(app).writeHashes(scoped.id, CatalogDb.VOD, vodHashes)
                    db.insertMissing(scoped.id, CatalogDb.VOD, fresh.vodCategories, emptyList())
                }
                return
            }
            if (lowRam) {

                val live = pullLive(app, db, scoped)
                reached = live || reached
                if (!liveReady.isCompleted) liveReady.complete(live)
                if (scoped.includeVod) {
                    if (pullShows(app, db, scoped)) reached = true
                    if (pullMovies(app, db, scoped)) reached = true
                } else {
                    progress.shows = 100
                    progress.movies = 100
                }
            } else {
                coroutineScope {
                    val liveJob = async { pullLive(app, db, scoped) }
                    val showJob = if (scoped.includeVod) async { pullShows(app, db, scoped) } else null
                    val movieJob = if (scoped.includeVod) async { pullMovies(app, db, scoped) } else null
                    val live = liveJob.await()
                    reached = live || reached
                    if (!liveReady.isCompleted) liveReady.complete(live)
                    if (showJob != null && showJob.await()) reached = true
                    if (movieJob != null && movieJob.await()) reached = true
                    if (!scoped.includeVod) {
                        progress.shows = 100
                        progress.movies = 100
                    }
                }
            }
        } finally {
            runCatching { db.endBulkSearch() }
            if (!liveReady.isCompleted) liveReady.complete(false)
            if (reached) {
                synchronized(settled) { settled.add(playlist.id) }
                runCatching {
                    val lineup = app.playlistStore.lineup(playlist.id)
                    if (lineup.includeInternational != true && lineup.hiddenCountries.isNullOrEmpty()) {
                        val home = playlist.homeCountry?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "us"
                        val hidden = db.countryChoices(playlist.id)
                            .map { it.id }
                            .filter { it != home && it != CountryIndex.OTHER_ID }
                        if (hidden.isNotEmpty()) {
                            app.playlistStore.saveLineup(
                                playlist.id,
                                lineup.copy(
                                    includeInternational = false,
                                    hiddenCountries = hidden,
                                    revision = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                app.playlistStore.saveLastRefreshAt(playlist.id, System.currentTimeMillis())
            }
        }
    }

    private suspend fun pullCloudSync(app: OlympusIptvApp, playlist: SavedPlaylist, bucket: String): Boolean {
        val email = app.playlistStore.session.first()?.email?.trim().orEmpty()
        if (email.isBlank()) {
            report("Cloud $bucket catalog skipped: no signed-in email")
            return false
        }
        val deviceId = app.playlistStore.deviceId()
        val client = LineupClient(app)
        val fetched = runCatching {
            withContext(Dispatchers.IO) {
                client.fetchCloudCatalog(email, deviceId, bucket)
            }
        }
        val cloud = fetched.getOrElse { error ->
            report("Cloud $bucket catalog request failed: ${error.message ?: error.javaClass.simpleName}", error)
            return false
        }
        if (cloud == null) {
            report("Cloud $bucket catalog request returned nothing")
            return false
        }
        if (cloud.channels.isEmpty() && cloud.categories.isEmpty()) {
            report("Cloud $bucket catalog is empty, so the app is loading channels from the provider instead")
            return false
        }
        val db = app.catalogDb
        val known = db.knownChannelIds(playlist.id, bucket)
        val stampPrefs = app.getSharedPreferences("olympus_cloud_stamps", android.content.Context.MODE_PRIVATE)
        val stampKey = "${playlist.id}:$bucket"
        val lastStamp = stampPrefs.getLong(stampKey, 0L)
        if (cloud.stamp > 0L && cloud.stamp == lastStamp && known.isNotEmpty()) {
            return true
        }
        val cache = CatalogCache(app)
        val hashes = cache.readHashes(playlist.id, bucket).toMutableMap()
        db.insertMissing(playlist.id, bucket, cloud.categories, emptyList())
        store(db, playlist.id, bucket, cloud.channels, bucket != CatalogDb.SERIES, known, hashes)
        cache.writeHashes(playlist.id, bucket, hashes)
        if (cloud.stamp > 0L) {
            stampPrefs.edit().putLong(stampKey, cloud.stamp).apply()
        }
        return true
    }

    private suspend fun pullLive(app: OlympusIptvApp, db: CatalogDb, playlist: SavedPlaylist): Boolean {
        if (pullCloudSync(app, playlist, CatalogDb.LIVE)) {
            progress.live = 100
            return true
        }
        val known = db.knownChannelIds(playlist.id, CatalogDb.LIVE)
        val cache = CatalogCache(app)
        val hashes = cache.readHashes(playlist.id, CatalogDb.LIVE).toMutableMap()
        val loaded = runCatching {
            pipeBatches(
                write = { batch -> store(db, playlist.id, CatalogDb.LIVE, batch, true, known, hashes) }
            ) { send ->
                loader.loadLive(playlist, onPercent = { progress.live = it.coerceIn(0, 99) }) { batch -> send(batch) }
            }
        }.getOrElse { error ->
            if (hashes.isNotEmpty()) runCatching { cache.writeHashes(playlist.id, CatalogDb.LIVE, hashes) }
            report("Live channels did not load from the provider: ${error.message ?: error.javaClass.simpleName}", error)
            return false
        }
        if (loaded.liveChannels.isEmpty()) {
            report("Provider returned 0 live channels for ${playlist.xtreamHost ?: playlist.m3uUrl ?: playlist.name}")
        }
        progress.live = 100
        db.insertMissing(playlist.id, CatalogDb.LIVE, loaded.liveCategories, emptyList())
        store(db, playlist.id, CatalogDb.LIVE, loaded.liveChannels, true, known, hashes)
        cache.writeHashes(playlist.id, CatalogDb.LIVE, hashes)
        return true
    }

    private suspend fun pullShows(app: OlympusIptvApp, db: CatalogDb, playlist: SavedPlaylist): Boolean {
        if (pullCloudSync(app, playlist, CatalogDb.SERIES)) {
            progress.shows = 100
            return true
        }
        val known = db.knownChannelIds(playlist.id, CatalogDb.SERIES)
        val cache = CatalogCache(app)
        val hashes = cache.readHashes(playlist.id, CatalogDb.SERIES).toMutableMap()
        val loaded = runCatching {
            pipeBatches(
                write = { batch -> store(db, playlist.id, CatalogDb.SERIES, batch, false, known, hashes) }
            ) { send ->
                loader.loadShows(playlist, onPercent = { progress.shows = it.coerceIn(0, 99) }) { batch -> send(batch) }
            }
        }.getOrElse { error ->
            if (hashes.isNotEmpty()) runCatching { cache.writeHashes(playlist.id, CatalogDb.SERIES, hashes) }
            report("Shows did not load: ${error.message ?: error.javaClass.simpleName}", error)
            return false
        }
        progress.shows = 100
        if (loaded.seriesLoadFailed) {
            report("Shows did not load: ${loaded.seriesLoadError ?: "provider request failed"}")
            return false
        }
        db.insertMissing(playlist.id, CatalogDb.SERIES, loaded.seriesCategories.orEmpty(), emptyList())
        store(db, playlist.id, CatalogDb.SERIES, loaded.seriesChannels.orEmpty(), false, known, hashes)
        cache.writeHashes(playlist.id, CatalogDb.SERIES, hashes)
        return true
    }

    private suspend fun pullMovies(app: OlympusIptvApp, db: CatalogDb, playlist: SavedPlaylist): Boolean {
        if (pullCloudSync(app, playlist, CatalogDb.VOD)) {
            progress.movies = 100
            return true
        }
        val known = db.knownChannelIds(playlist.id, CatalogDb.VOD)
        val cache = CatalogCache(app)
        val hashes = cache.readHashes(playlist.id, CatalogDb.VOD).toMutableMap()
        var loaded = runCatching {
            pipeBatches(
                write = { batch -> store(db, playlist.id, CatalogDb.VOD, batch, true, known, hashes) }
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
                    write = { batch -> store(db, playlist.id, CatalogDb.VOD, batch, true, known, hashes) }
                ) { send ->
                    CountryIndex.annotate(
                        loader.loadMovies(playlist, onPercent = { progress.movies = it.coerceIn(0, 99) }) { batch ->
                            send(batch)
                        }
                    )
                }
            }.getOrNull()
        }
        if (loaded == null) {
            if (hashes.isNotEmpty()) runCatching { cache.writeHashes(playlist.id, CatalogDb.VOD, hashes) }
            report("Movies did not load from the provider")
            return false
        }
        if (loaded.vodLoadFailed) {
            report("Movies did not load: ${loaded.vodLoadError ?: "provider request failed"}")
            return false
        }
        progress.movies = 100
        db.insertMissing(playlist.id, CatalogDb.VOD, loaded.vodCategories, emptyList())
        store(db, playlist.id, CatalogDb.VOD, loaded.vodChannels, true, known, hashes)
        cache.writeHashes(playlist.id, CatalogDb.VOD, hashes)
        return true
    }

    private fun store(
        db: CatalogDb,
        playlistId: String,
        bucket: String,
        channels: List<Channel>,
        tag: Boolean,
        known: MutableSet<String>,
        hashes: MutableMap<String, Int>
    ) {
        if (channels.isEmpty()) return
        val changed = CatalogDelta.writes(hashes, channels, known)
        if (changed.isNotEmpty()) {
            val rows = if (tag) CountryIndex.tagChannels(changed) else changed
            db.insertMissing(playlistId, bucket, emptyList(), rows)
            for (channel in changed) known.add(channel.id)
        }
        for (channel in channels) hashes[channel.id] = CatalogDelta.rowHash(channel)
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
