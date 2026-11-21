package com.olympus.iptv.playlist

import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class LoadedPlaylist(
    val liveCategories: List<Category>,
    val liveChannels: List<Channel>,
    val vodCategories: List<Category> = emptyList(),
    val vodChannels: List<Channel> = emptyList(),
    val seriesCategories: List<Category>? = null,
    val seriesChannels: List<Channel>? = null,
    val xmltvByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    val vodLoadFailed: Boolean = false,
    val seriesLoadFailed: Boolean = false,
    val vodLoadError: String? = null,
    val seriesLoadError: String? = null
)

class PlaylistLoader(
    private val http: OkHttpClient = XtreamClient.sharedHttp,
    private val xtreamClient: XtreamClient = XtreamClient(http)
) {
    suspend fun load(playlist: SavedPlaylist): LoadedPlaylist = withContext(Dispatchers.IO) {
        when (playlist.type) {
            PlaylistType.M3U -> loadM3u(playlist)
            PlaylistType.XTREAM -> {
                val catalog = xtreamClient.loadCatalog(playlist)
                LoadedPlaylist(
                    liveCategories = catalog.liveCategories,
                    liveChannels = catalog.liveChannels,
                    vodCategories = catalog.vodCategories,
                    vodChannels = catalog.vodChannels,
                    seriesCategories = catalog.seriesCategories,
                    seriesChannels = catalog.seriesChannels,
                    vodLoadFailed = catalog.vodFailed,
                    seriesLoadFailed = catalog.seriesFailed
                )
            }
        }
    }

    suspend fun loadLive(
        playlist: SavedPlaylist,
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null
    ): LoadedPlaylist = withContext(Dispatchers.IO) {
        when (playlist.type) {
            PlaylistType.M3U -> {
                val parsed = loadM3u(playlist).copy(
                    vodCategories = emptyList(),
                    vodChannels = emptyList(),
                    seriesCategories = emptyList(),
                    seriesChannels = emptyList()
                )
                if (onBatch != null) {
                    onBatch(parsed.liveChannels)
                    parsed.copy(liveChannels = emptyList())
                } else {
                    parsed
                }
            }
            PlaylistType.XTREAM -> {
                val catalog = xtreamClient.loadLive(playlist, onBatch, onPercent)
                LoadedPlaylist(
                    liveCategories = catalog.liveCategories,
                    liveChannels = catalog.liveChannels
                )
            }
        }
    }

    suspend fun loadMovies(
        playlist: SavedPlaylist,
        onCount: (Int) -> Unit = {},
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null
    ): LoadedPlaylist = withContext(Dispatchers.IO) {
        when (playlist.type) {
            PlaylistType.M3U -> {
                val parsed = loadM3u(playlist)
                onBatch?.invoke(parsed.vodChannels)
                LoadedPlaylist(
                    liveCategories = emptyList(),
                    liveChannels = emptyList(),
                    vodCategories = parsed.vodCategories,
                    vodChannels = if (onBatch == null) parsed.vodChannels else emptyList()
                )
            }
            PlaylistType.XTREAM -> {
                val library = xtreamClient.loadMovies(playlist, onCount, onPercent, onBatch)
                LoadedPlaylist(
                    liveCategories = emptyList(),
                    liveChannels = emptyList(),
                    vodCategories = library.vodCategories,
                    vodChannels = library.vodChannels,
                    vodLoadFailed = library.vodFailed,
                    vodLoadError = library.vodError
                )
            }
        }
    }

    suspend fun loadShows(
        playlist: SavedPlaylist,
        onCount: (Int) -> Unit = {},
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null
    ): LoadedPlaylist = withContext(Dispatchers.IO) {
        when (playlist.type) {
            PlaylistType.M3U -> LoadedPlaylist(liveCategories = emptyList(), liveChannels = emptyList())
            PlaylistType.XTREAM -> {
                val library = xtreamClient.loadShows(playlist, onCount, onPercent, onBatch)
                LoadedPlaylist(
                    liveCategories = emptyList(),
                    liveChannels = emptyList(),
                    seriesCategories = library.seriesCategories,
                    seriesChannels = library.seriesChannels,
                    seriesLoadFailed = library.seriesFailed,
                    seriesLoadError = library.seriesError
                )
            }
        }
    }

    suspend fun loadLibrary(playlist: SavedPlaylist): LoadedPlaylist = withContext(Dispatchers.IO) {
        val library = xtreamClient.loadLibrary(playlist)
        LoadedPlaylist(
            liveCategories = emptyList(),
            liveChannels = emptyList(),
            vodCategories = library.vodCategories,
            vodChannels = library.vodChannels,
            seriesCategories = library.seriesCategories,
            seriesChannels = library.seriesChannels,
            vodLoadFailed = library.vodFailed,
            seriesLoadFailed = library.seriesFailed
        )
    }

    suspend fun validate(playlist: SavedPlaylist): Boolean = withContext(Dispatchers.IO) {
        when (playlist.type) {
            PlaylistType.M3U -> {
                val url = playlist.m3uUrl.orEmpty()
                require(url.startsWith("http")) { "M3U URL required" }
                val body = fetchText(resolveM3uUrl(playlist), playlist.username, playlist.password)
                body.contains("#EXTM3U", ignoreCase = true) || body.contains("#EXTINF", ignoreCase = true)
            }
            PlaylistType.XTREAM -> {
                require(!playlist.xtreamHost.isNullOrBlank()) { "Host required" }
                require(!playlist.xtreamUsername.isNullOrBlank()) { "Username required" }
                require(!playlist.xtreamPassword.isNullOrBlank()) { "Password required" }
                xtreamClient.authenticate(playlist)
            }
        }
    }

    private fun loadM3u(playlist: SavedPlaylist): LoadedPlaylist {
        val parsed = M3uParser.parse(
            fetchText(resolveM3uUrl(playlist), playlist.username, playlist.password)
        )
        val allCategory = Category(id = ALL_ID, name = "All channels", kind = ContentKind.LIVE)
        val home = playlist.homeCountry?.takeIf { it.isNotBlank() } ?: "us"
        val localOnly = playlist.includeInternational != true
        val liveChannels = parsed.channels.filter { !isVodGroup(it.groupTitle) }.filter { channel ->
            !localOnly || !CountryIndex.isForeign(channel.groupTitle, home)
        }
        val vodChannels = if (playlist.includeVod) {
            parsed.channels
                .filter { isVodGroup(it.groupTitle) }
                .map { it.copy(kind = ContentKind.VOD) }
        } else {
            emptyList()
        }
        val liveCategories = listOf(allCategory) + parsed.categories.filter { !isVodGroup(it.name) }.filter { category ->
            !localOnly || !CountryIndex.isForeign(category.name, home)
        }
        val vodCategories = if (playlist.includeVod) {
            parsed.categories.filter { isVodGroup(it.name) }.map { it.copy(kind = ContentKind.VOD) }
        } else {
            emptyList()
        }
        val xmltv = parsed.epgUrl
            ?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("http") }
            ?.let { epgUrl ->
                runCatching {
                    http.newCall(Request.Builder().url(epgUrl).get().build()).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyMap()
                        val body = response.body ?: return@use emptyMap()
                        body.byteStream().use { XmltvParser.parse(it) }
                    }
                }.getOrDefault(emptyMap())
            }
            ?: emptyMap()

        return LoadedPlaylist(
            liveCategories = liveCategories,
            liveChannels = liveChannels,
            vodCategories = vodCategories,
            vodChannels = vodChannels,
            xmltvByChannel = xmltv
        )
    }

    private fun resolveM3uUrl(playlist: SavedPlaylist): String {
        val raw = playlist.m3uUrl.orEmpty()
        val user = playlist.username.orEmpty()
        val pass = playlist.password.orEmpty()
        val httpUrl = raw.toHttpUrlOrNull() ?: return raw
        if (user.isBlank() || pass.isBlank() || httpUrl.queryParameter("username") != null) return raw
        val path = httpUrl.encodedPath
        if (path.endsWith(".m3u", true) || path.endsWith(".m3u8", true)) return raw
        val builder = if (path.isBlank() || path == "/") {
            httpUrl.newBuilder().encodedPath("/get.php")
        } else {
            httpUrl.newBuilder()
        }
        return builder
            .setQueryParameter("username", user)
            .setQueryParameter("password", pass)
            .setQueryParameter("type", "m3u_plus")
            .setQueryParameter("output", "ts")
            .build()
            .toString()
    }

    private fun isVodGroup(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.lowercase()
        return normalized.contains("vod") ||
            normalized.contains("movie") ||
            normalized.contains("series") ||
            normalized.contains("film")
    }

    private fun fetchText(url: String, username: String? = null, password: String? = null): String {
        val builder = Request.Builder().url(url).get()
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        val request = builder.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        const val ALL_ID = "__all__"
        const val FAVORITES_ID = "__favorites__"
        const val RECENT_ID = "__recent__"
        const val CONTINUE_ID = "__continue__"
        const val TOP_WATCHED_ID = "__top_watched__"
        const val FOR_YOU_ID = "__for_you__"
        const val LIVE_EVENTS_ID = "__live_events__"

        fun isSeriesName(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            val normalized = name.lowercase()
            return normalized.contains("series") || normalized.contains("tv show")
        }
    }
}
