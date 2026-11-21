package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.ui.browse.EpgTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

class EpgRepository(
    private val xtreamClient: XtreamClient = XtreamClient(XtreamClient.sharedHttp),
    xmltvByChannel: Map<String, List<EpgProgram>> = emptyMap()
) {
    private var xmltv = xmltvByChannel
    private var xmltvFolded = foldXmltv(xmltvByChannel)
    private val cache = ConcurrentHashMap<String, List<EpgProgram>>()
    private val windows = LruCache<String, List<EpgProgram>>(512)
    private val parsedNames = ConcurrentHashMap<String, List<EpgProgram>>()
    private val inflight = ConcurrentHashMap<String, Mutex>()
    private var disk: CatalogDb? = null
    private var refreshScope: kotlinx.coroutines.CoroutineScope? = null
    @Volatile private var nameEpg = false

    fun attach(db: CatalogDb, scope: kotlinx.coroutines.CoroutineScope) {
        disk = db
        refreshScope = scope
    }

    fun useXmltv(map: Map<String, List<EpgProgram>>) {
        xmltv = map
        xmltvFolded = foldXmltv(map)
    }

    /** Parsed names are used only after the guide scan is turned on. Returns true when the choice changes. */
    fun useNameEpg(enabled: Boolean): Boolean {
        if (nameEpg == enabled) return false
        nameEpg = enabled
        if (enabled) {
            cache.entries.removeIf { it.value.isEmpty() }
        } else {
            parsedNames.clear()
            cache.clear()
            windows.evictAll()
        }
        return true
    }

    fun installNameEpg(playlistId: String, byChannel: Map<String, List<EpgProgram>>) {
        val prefix = "$playlistId::"
        parsedNames.keys.removeAll { it.startsWith(prefix) }
        var kept = 0
        for ((id, programs) in byChannel) {
            if (kept >= PARSED_NAMES_CAP) break
            parsedNames["$playlistId::$id"] = programs
            kept += 1
        }
        nameEpg = true
        cache.entries.removeIf { it.key.startsWith(prefix) && it.value.isEmpty() }
        trimCache()
        trimParsedNames()
    }

    suspend fun nowNext(
        playlist: SavedPlaylist?,
        channel: Channel
    ): EpgNowNext = withContext(Dispatchers.IO) {
        val programs = loadPrograms(playlist, channel)
        EpgUtils.nowNext(programs)
    }

    suspend fun upcoming(playlist: SavedPlaylist?, channel: Channel): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val loaded = loadPrograms(playlist, channel)
            loaded.filter { it.endMillis > now }.sortedBy { it.startMillis }.take(96)
        }

    private fun rememberWindows(channelId: String, programs: List<EpgProgram>, now: Long) {
        val start = now / EpgWindow.BUCKET_MS * EpgWindow.BUCKET_MS
        for (step in 0L..2L) {
            val at = start + step * EpgWindow.BUCKET_MS
            windows.put(EpgWindow.key(channelId, at), EpgWindow.slice(programs, at))
        }
    }

    suspend fun sameShow(playlist: SavedPlaylist?, channel: Channel, title: String): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            loadPrograms(playlist, channel)
                .filter { it.endMillis > now && com.olympus.iptv.data.ShowNames.same(it.title, title) }
                .sortedBy { it.startMillis }
                .take(24)
        }

    private suspend fun loadPrograms(
        playlist: SavedPlaylist?,
        channel: Channel
    ): List<EpgProgram> {
        val key = cacheKey(playlist, channel)
        cache[key]?.let { return it }
        val playlistId = playlist?.id
        val stored = if (playlistId.isNullOrBlank()) null else disk?.readEpg(playlistId, channel.epgChannelId ?: channel.id)
        if (stored != null) {
            putCache(key, stored.second)
            val freshEnough = System.currentTimeMillis() - stored.first < STALE_MS
            if (!freshEnough) refreshScope?.launch { refresh(playlist, channel, key) }
            return stored.second
        }

        val mutex = inflight.getOrPut(key) { Mutex() }
        return mutex.withLock {
            cache[key]?.let { return it }
            val programs = fetchPrograms(playlist, channel)
            putCache(key, programs)
            if (!playlistId.isNullOrBlank()) disk?.writeEpg(playlistId, channel.epgChannelId ?: channel.id, programs)
            programs
        }
    }

    private suspend fun refresh(playlist: SavedPlaylist?, channel: Channel, key: String) {
        val programs = runCatching { fetchPrograms(playlist, channel) }.getOrNull() ?: return
        putCache(key, programs)
        val playlistId = playlist?.id ?: return
        disk?.writeEpg(playlistId, channel.epgChannelId ?: channel.id, programs)
    }

    private suspend fun fetchPrograms(playlist: SavedPlaylist?, channel: Channel): List<EpgProgram> {
        val loaded = when (playlist?.type) {
            PlaylistType.XTREAM -> xtreamClient.getShortEpg(playlist, channel.id)
            PlaylistType.M3U, null -> {
                val epgId = channel.epgChannelId ?: channel.id
                xmltv[epgId] ?: xmltvFolded[epgId.lowercase()] ?: emptyList()
            }
        }
        return when {
            loaded.isNotEmpty() -> loaded
            nameEpg && channel.kind == ContentKind.LIVE ->
                parsedNames[cacheKey(playlist, channel)] ?: DummyEpg.programs(channel.name)
            else -> loaded
        }
    }

    private fun putCache(key: String, programs: List<EpgProgram>) {
        val now = System.currentTimeMillis()
        cache[key] = programs.map { EpgTiming.dress(it, now) }
        trimCache()
    }

    /** Drop the in-memory guide when the process is asked to free RAM. Disk EPG stays. */
    fun trimForPressure() {
        windows.evictAll()
        cache.clear()
        parsedNames.clear()
    }

    private fun trimCache() {
        val over = cache.size - CACHE_CAP
        if (over <= 0) return
        val drop = over + CACHE_CAP / 4
        val keys = cache.keys.take(drop)
        for (key in keys) cache.remove(key)
    }

    private fun trimParsedNames() {
        val over = parsedNames.size - PARSED_NAMES_CAP
        if (over <= 0) return
        val drop = over + PARSED_NAMES_CAP / 5
        val keys = parsedNames.keys.take(drop)
        for (key in keys) parsedNames.remove(key)
    }

    private fun foldXmltv(map: Map<String, List<EpgProgram>>): Map<String, List<EpgProgram>> {
        if (map.isEmpty()) return emptyMap()
        val folded = HashMap<String, List<EpgProgram>>(map.size)
        for ((key, programs) in map) folded.putIfAbsent(key.lowercase(), programs)
        return folded
    }

    private fun cacheKey(playlist: SavedPlaylist?, channel: Channel): String {
        val epgId = channel.epgChannelId ?: channel.id
        return "${playlist?.id.orEmpty()}::$epgId"
    }

    fun evictExpired(now: Long = System.currentTimeMillis()) {
        windows.evictAll()
        cache.entries.removeIf { (_, list) ->
            list.isEmpty() || list.any { it.endMillis <= now }
        }
    }

    private companion object {
        private const val STALE_MS = 5L * 60L * 1000L
        private const val CACHE_CAP = 256
        private const val PARSED_NAMES_CAP = 2_000
    }
}
