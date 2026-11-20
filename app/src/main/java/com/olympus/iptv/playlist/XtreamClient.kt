package com.olympus.iptv.playlist

import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.SavedPlaylist
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class XtreamClient(
    private val http: OkHttpClient = sharedHttp
) {
    data class Catalog(
        val liveCategories: List<Category>,
        val liveChannels: List<Channel>,
        val vodCategories: List<Category>,
        val vodChannels: List<Channel>,
        val seriesCategories: List<Category> = emptyList(),
        val seriesChannels: List<Channel> = emptyList(),
        val vodFailed: Boolean = false,
        val seriesFailed: Boolean = false
    )

    data class Library(
        val vodCategories: List<Category>,
        val vodChannels: List<Channel>,
        val seriesCategories: List<Category>,
        val seriesChannels: List<Channel>,
        val vodFailed: Boolean,
        val seriesFailed: Boolean,
        val vodError: String? = null,
        val seriesError: String? = null
    )

    suspend fun authenticate(playlist: SavedPlaylist): Boolean = withContext(Dispatchers.IO) {
        val root = getJson(playlist) as? JsonObject ?: return@withContext false
        val userInfo = root.getAsJsonObject("user_info") ?: return@withContext false
        val auth = userInfo.get("auth")?.asString ?: userInfo.get("auth")?.asInt?.toString()
        auth == "1" || userInfo.get("status")?.asString.equals("Active", ignoreCase = true)
    }

    suspend fun loadCatalog(playlist: SavedPlaylist): Catalog = coroutineScope {
        val host = normalizeHost(playlist.xtreamHost.orEmpty())
        val user = playlist.xtreamUsername ?: playlist.username.orEmpty()
        val pass = playlist.xtreamPassword ?: playlist.password.orEmpty()
        val live = async(Dispatchers.IO) { liveCatalog(playlist, host, user, pass) }
        val library = async(Dispatchers.IO) { fetchLibrary(playlist, host, user, pass) }
        val ready = live.await()
        val extra = library.await()
        ready.copy(
            vodCategories = extra.vodCategories,
            vodChannels = extra.vodChannels,
            seriesCategories = extra.seriesCategories,
            seriesChannels = extra.seriesChannels,
            vodFailed = extra.vodFailed,
            seriesFailed = extra.seriesFailed
        )
    }

    suspend fun loadLive(
        playlist: SavedPlaylist,
        onBatch: ((List<Channel>) -> Unit)? = null,
        onPercent: (Int) -> Unit = {}
    ): Catalog = withContext(Dispatchers.IO) {
        val host = normalizeHost(playlist.xtreamHost.orEmpty())
        val user = playlist.xtreamUsername ?: playlist.username.orEmpty()
        val pass = playlist.xtreamPassword ?: playlist.password.orEmpty()
        liveCatalog(playlist, host, user, pass, onBatch, onPercent)
    }

    suspend fun loadMovies(
        playlist: SavedPlaylist,
        onCount: (Int) -> Unit = {},
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null
    ): Library = withContext(Dispatchers.IO) {
        if (!playlist.includeVod) return@withContext emptyLibrary()
        val host = normalizeHost(playlist.xtreamHost.orEmpty())
        val user = playlist.xtreamUsername ?: playlist.username.orEmpty()
        val pass = playlist.xtreamPassword ?: playlist.password.orEmpty()
        val result = runCatching { loadVod(playlist, host, user, pass, onCount, onPercent, onBatch) }
        Library(
            vodCategories = result.getOrNull()?.first.orEmpty(),
            vodChannels = result.getOrNull()?.second.orEmpty(),
            seriesCategories = emptyList(),
            seriesChannels = emptyList(),
            vodFailed = result.isFailure,
            seriesFailed = false,
            vodError = result.exceptionOrNull()?.message
        )
    }

    suspend fun loadShows(
        playlist: SavedPlaylist,
        onCount: (Int) -> Unit = {},
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null
    ): Library = withContext(Dispatchers.IO) {
        if (!playlist.includeVod) return@withContext emptyLibrary()
        val result = runCatching { loadSeries(playlist, onCount, onPercent, onBatch) }
        Library(
            vodCategories = emptyList(),
            vodChannels = emptyList(),
            seriesCategories = result.getOrNull()?.first.orEmpty(),
            seriesChannels = result.getOrNull()?.second.orEmpty(),
            vodFailed = false,
            seriesFailed = result.isFailure,
            seriesError = result.exceptionOrNull()?.message
        )
    }

    suspend fun loadLibrary(playlist: SavedPlaylist): Library = withContext(Dispatchers.IO) {
        val host = normalizeHost(playlist.xtreamHost.orEmpty())
        val user = playlist.xtreamUsername ?: playlist.username.orEmpty()
        val pass = playlist.xtreamPassword ?: playlist.password.orEmpty()
        fetchLibrary(playlist, host, user, pass)
    }

    private suspend fun liveCatalog(
        playlist: SavedPlaylist,
        host: String,
        user: String,
        pass: String,
        onBatch: ((List<Channel>) -> Unit)? = null,
        onPercent: (Int) -> Unit = {}
    ): Catalog {
        val liveCats = parseCategories(getJson(playlist, "get_live_categories"), ContentKind.LIVE)
        val names = liveCats.associate { it.id to it.name }
        val mapLive: (Map<String, String>) -> Channel? = mapLive@{ fields ->
            val streamId = fields["stream_id"] ?: return@mapLive null
            val archive = fields["tv_archive"]?.let { it == "1" || it.equals("true", true) }
            named(
                Channel(
                    id = streamId,
                    name = fields["name"] ?: "Channel $streamId",
                    streamUrl = "$host/live/$user/$pass/$streamId.m3u8",
                    logoUrl = fields["stream_icon"],
                    groupTitle = fields["category_name"],
                    categoryId = fields["category_id"],
                    kind = ContentKind.LIVE,
                    number = fields["num"]?.toIntOrNull(),
                    tvArchive = archive,
                    tvArchiveDuration = fields["tv_archive_duration"]?.toIntOrNull()
                ),
                names
            )
        }
        val shown = localCategories(playlist, liveCats)
        val liveStreams = readLocalOrAll(
            playlist, "get_live_streams", "stream_id", "Channels", liveCats,
            onCount = {}, onPercent = onPercent, onBatch = onBatch,
            map = { fields -> keepLocal(playlist, mapLive(fields), names) }
        )
        return Catalog(
            liveCategories = shown,
            liveChannels = liveStreams,
            vodCategories = emptyList(),
            vodChannels = emptyList()
        )
    }

    private suspend fun fetchLibrary(playlist: SavedPlaylist, host: String, user: String, pass: String): Library {
        if (!playlist.includeVod) return emptyLibrary()
        return coroutineScope {
            val vod = async(Dispatchers.IO) { runCatching { loadVod(playlist, host, user, pass) } }
            val series = async(Dispatchers.IO) { runCatching { loadSeries(playlist) } }
            val vodResult = vod.await()
            val seriesResult = series.await()
            Library(
                vodCategories = vodResult.getOrNull()?.first.orEmpty(),
                vodChannels = vodResult.getOrNull()?.second.orEmpty(),
                seriesCategories = seriesResult.getOrNull()?.first.orEmpty(),
                seriesChannels = seriesResult.getOrNull()?.second.orEmpty(),
                vodFailed = vodResult.isFailure,
                seriesFailed = seriesResult.isFailure,
                vodError = vodResult.exceptionOrNull()?.message,
                seriesError = seriesResult.exceptionOrNull()?.message
            )
        }
    }

    private fun emptyLibrary() = Library(
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        vodFailed = false,
        seriesFailed = false
    )

    private suspend fun loadVod(
        playlist: SavedPlaylist,
        host: String,
        user: String,
        pass: String,
        onCount: (Int) -> Unit = {},
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null
    ): Pair<List<Category>, List<Channel>> {
        val categories = runCatching {
            parseCategories(getJson(playlist, "get_vod_categories"), ContentKind.VOD, required = true)
        }.getOrDefault(emptyList())
        val names = categories.associate { it.id to it.name }
        val mapMovie: (Map<String, String>) -> Channel? = mapMovie@{ fields ->
            val streamId = fields["stream_id"] ?: return@mapMovie null
            val ext = fields["container_extension"] ?: "mp4"
            named(
                Channel(
                    id = streamId,
                    name = fields["name"] ?: "Movie $streamId",
                    streamUrl = "$host/movie/$user/$pass/$streamId.$ext",
                    logoUrl = fields["stream_icon"],
                    groupTitle = fields["category_name"],
                    categoryId = fields["category_id"],
                    kind = ContentKind.VOD,
                    containerExtension = ext,
                    number = fields["num"]?.toIntOrNull()
                ),
                names
            )
        }
        var parsed = 0
        val shown = localCategories(playlist, categories)
        val streams = readLocalOrAll(
            playlist,
            "get_vod_streams",
            "stream_id",
            "Movies",
            categories,
            { count -> parsed = count; onCount(count) },
            onPercent,
            onBatch,
            map = { fields -> keepLocal(playlist, mapMovie(fields), names) }
        )
        if (parsed == 0 && categories.isEmpty()) throw IllegalStateException("Movies did not load")
        return shown to streams
    }

    private suspend fun loadSeries(
        playlist: SavedPlaylist,
        onCount: (Int) -> Unit = {},
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null
    ): Pair<List<Category>, List<Channel>> {
        val categories = runCatching {
            parseCategories(getJson(playlist, "get_series_categories"), ContentKind.VOD, required = true)
        }.getOrDefault(emptyList())
        val names = categories.associate { it.id to it.name }
        val mapShow: (Map<String, String>) -> Channel? = mapShow@{ fields ->
            val seriesId = fields["series_id"] ?: return@mapShow null
            named(
                Channel(
                    id = seriesId,
                    name = fields["name"] ?: "Series $seriesId",
                    streamUrl = "",
                    logoUrl = fields["cover"] ?: fields["stream_icon"],
                    groupTitle = fields["category_name"],
                    categoryId = fields["category_id"],
                    kind = ContentKind.VOD,
                    number = fields["num"]?.toIntOrNull(),
                    seriesId = seriesId
                ),
                names
            )
        }
        var parsed = 0
        val shown = localCategories(playlist, categories)
        val streams = readLocalOrAll(
            playlist,
            "get_series",
            "series_id",
            "Shows",
            categories,
            { count -> parsed = count; onCount(count) },
            onPercent,
            onBatch,
            map = { fields -> keepLocal(playlist, mapShow(fields), names) }
        )
        if (parsed == 0 && categories.isEmpty()) throw IllegalStateException("Shows did not load")
        return shown to streams
    }

    private fun localOnly(playlist: SavedPlaylist) = playlist.includeInternational != true

    private fun homeCountry(playlist: SavedPlaylist) =
        playlist.homeCountry?.takeIf { it.isNotBlank() } ?: "us"

    private fun localCategories(playlist: SavedPlaylist, categories: List<Category>): List<Category> {
        if (!localOnly(playlist)) return categories
        val home = homeCountry(playlist)
        val kept = categories.filter { !CountryIndex.isForeign(it.name, home) }
        return kept.ifEmpty { categories }
    }

    private fun keepLocal(playlist: SavedPlaylist, channel: Channel?, names: Map<String, String>): Channel? {
        if (channel == null || !localOnly(playlist)) return channel
        val group = channel.groupTitle?.takeIf { it.isNotBlank() } ?: names[channel.categoryId]
        return if (CountryIndex.isForeign(group, homeCountry(playlist))) null else channel
    }

    private suspend fun readLocalOrAll(
        playlist: SavedPlaylist,
        action: String,
        idField: String,
        label: String,
        categories: List<Category>,
        onCount: (Int) -> Unit,
        onPercent: (Int) -> Unit,
        onBatch: ((List<Channel>) -> Unit)?,
        map: (Map<String, String>) -> Channel?
    ): List<Channel> {
        val kept = localCategories(playlist, categories)
        val narrowed = localOnly(playlist) && kept.isNotEmpty() && kept.size < categories.size
        if (narrowed && categoryFilterWorks(playlist, action, kept, idField)) {
            return readSelectedCategories(playlist, action, label, kept, onCount, onPercent, onBatch, map)
        }
        return readChannels(playlist, action, label, onCount, onPercent, onBatch, map = map)
    }

    private fun categoryFilterWorks(
        playlist: SavedPlaylist,
        action: String,
        categories: List<Category>,
        idField: String
    ): Boolean {
        if (categories.size < 2) return categories.isNotEmpty()
        val first = firstStreamId(playlist, action, categories[0].id, idField)
        val second = firstStreamId(playlist, action, categories[1].id, idField)
        return first != null && second != null && first != second
    }

    private fun firstStreamId(
        playlist: SavedPlaylist,
        action: String,
        categoryId: String,
        idField: String
    ): String? {
        val call = http.newCall(
            Request.Builder()
                .url(buildApiUrl(playlist, action, mapOf("category_id" to categoryId)))
                .get()
                .build()
        )
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                val reader = JsonReader(InputStreamReader(response.body?.byteStream() ?: return null, Charsets.UTF_8))
                reader.beginArray()
                if (!reader.hasNext()) return null
                reader.beginObject()
                var id: String? = null
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key == idField && (reader.peek() == JsonToken.STRING || reader.peek() == JsonToken.NUMBER)) {
                        id = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                id
            }
        } catch (_: Exception) {
            null
        } finally {
            call.cancel()
        }
    }

    private suspend fun readSelectedCategories(
        playlist: SavedPlaylist,
        action: String,
        label: String,
        categories: List<Category>,
        onCount: (Int) -> Unit,
        onPercent: (Int) -> Unit,
        onBatch: ((List<Channel>) -> Unit)?,
        map: (Map<String, String>) -> Channel?
    ): List<Channel> = coroutineScope {
        val gate = Semaphore(4)
        val done = AtomicInteger()
        val total = categories.size.coerceAtLeast(1)
        val pieces = categories.map { category ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val rows = readChannels(
                        playlist,
                        action,
                        label,
                        onCount,
                        onPercent = {},
                        onBatch,
                        extra = mapOf("category_id" to category.id),
                        map = map
                    )
                    onPercent((((done.incrementAndGet() * 95) / total) / 5) * 5)
                    rows
                }
            }
        }.awaitAll()
        pieces.flatten()
    }

    private fun named(channel: Channel, names: Map<String, String>): Channel {
        val title = channel.groupTitle?.takeIf { it.isNotBlank() } ?: names[channel.categoryId]
        return if (title.isNullOrBlank() || title == channel.groupTitle) channel else channel.copy(groupTitle = title)
    }

    suspend fun seriesEpisodes(playlist: SavedPlaylist, seriesId: String): List<Channel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildApiUrl(playlist, "get_series_info", mapOf("series_id" to seriesId)))
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body ?: return@withContext emptyList()
            val host = normalizeHost(playlist.xtreamHost.orEmpty())
            val user = playlist.xtreamUsername ?: playlist.username.orEmpty()
            val pass = playlist.xtreamPassword ?: playlist.password.orEmpty()
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return@withContext emptyList()
                reader.beginObject()
                var episodes = emptyList<Channel>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "episodes") {
                        episodes = readSeasonEpisodes(reader, host, user, pass)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                episodes
            }
        }
    }

    private fun readSeasonEpisodes(
        reader: JsonReader,
        host: String,
        user: String,
        pass: String
    ): List<Channel> {
        val seasons = ArrayList<Pair<Int, List<Channel>>>()
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val season = reader.nextName().toIntOrNull() ?: Int.MAX_VALUE
                    seasons.add(season to readEpisodeArray(reader, host, user, pass, season))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> seasons.add(1 to readEpisodeArray(reader, host, user, pass, 1))
            else -> reader.skipValue()
        }
        return seasons.sortedBy { it.first }.flatMap { it.second }
    }

    private fun readEpisodeArray(
        reader: JsonReader,
        host: String,
        user: String,
        pass: String,
        season: Int
    ): List<Channel> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val episodes = ArrayList<Channel>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                readEpisode(reader, host, user, pass, season)?.let { episodes.add(it) }
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
        return episodes
    }

    private fun readEpisode(reader: JsonReader, host: String, user: String, pass: String, season: Int): Channel? {
        var id: String? = null
        var title: String? = null
        var ext = "mp4"
        var number: Int? = null
        var info = EpisodeInfo()
        var still: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (val key = reader.nextName()) {
                "id" -> id = reader.nextText()
                "title" -> title = reader.nextText()
                "container_extension" -> ext = reader.nextText()?.ifBlank { "mp4" } ?: "mp4"
                "episode_num" -> number = reader.nextText()?.toIntOrNull()
                "num" -> if (number == null) number = reader.nextText()?.toIntOrNull() else reader.skipValue()
                "info" -> info = readEpisodeInfo(reader)
                "movie_image", "cover", "cover_big" -> if (still.isNullOrBlank()) still = httpPicture(reader.nextText()) else reader.skipValue()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val episodeId = id ?: return null
        return Channel(
            id = episodeId,
            name = title ?: "Episode ${number ?: episodeId}",
            streamUrl = "$host/series/$user/$pass/$episodeId.$ext",
            logoUrl = still ?: info.still,
            kind = ContentKind.VOD,
            number = number,
            season = season,
            seriesId = null,
            blurb = info.plot,
            aired = info.aired,
            runtime = info.runtime
        )
    }

    private data class EpisodeInfo(
        val plot: String? = null,
        val still: String? = null,
        val aired: String? = null,
        val runtime: String? = null
    )

    private fun readEpisodeInfo(reader: JsonReader): EpisodeInfo {
        if (reader.peek() == JsonToken.STRING) {
            val text = reader.nextString().trim()
            return EpisodeInfo(plot = text.takeIf { it.isNotBlank() })
        }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return EpisodeInfo()
        }
        var plot: String? = null
        var still: String? = null
        var aired: String? = null
        var runtime: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "plot", "description" -> plot = reader.nextText()
                "movie_image", "cover", "cover_big" -> if (still.isNullOrBlank()) still = httpPicture(reader.nextText()) else reader.skipValue()
                "releasedate", "releaseDate", "release_date", "air_date" -> if (aired.isNullOrBlank()) aired = reader.nextText() else reader.skipValue()
                "duration" -> if (runtime.isNullOrBlank()) runtime = reader.nextText() else reader.skipValue()
                "duration_secs" -> {
                    val seconds = reader.nextText()?.toIntOrNull()
                    if (runtime.isNullOrBlank() && seconds != null && seconds > 0) runtime = clockFromSeconds(seconds)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return EpisodeInfo(
            plot = plot?.trim()?.takeIf { it.isNotBlank() },
            still = still,
            aired = prettyDate(aired),
            runtime = prettyRuntime(runtime)
        )
    }

    private fun httpPicture(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        return text.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun prettyDate(raw: String?): String? {
        val day = raw?.trim()?.take(10).orEmpty()
        if (!day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || day.startsWith("0000")) return null
        val date = runCatching { java.time.LocalDate.parse(day) }.getOrNull() ?: return null
        return date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US))
    }

    private fun clockFromSeconds(seconds: Int): String? {
        val minutes = (seconds + 30) / 60
        if (minutes <= 0) return null
        val hours = minutes / 60
        val remain = minutes % 60
        return if (hours > 0 && remain > 0) "${hours}h ${remain}m" else if (hours > 0) "${hours}h" else "${minutes}m"
    }

    private fun prettyRuntime(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank() || text == "0" || text == "00:00:00") return null
        val clock = Regex("(\\d+):(\\d+):(\\d+)").find(text)
        if (clock != null) {
            val hours = clock.groupValues[1].toInt()
            val minutes = clock.groupValues[2].toInt()
            val seconds = clock.groupValues[3].toInt()
            return clockFromSeconds(hours * 3600 + minutes * 60 + seconds)
        }
        val minutes = text.toIntOrNull() ?: return text
        if (minutes <= 0) return null
        val hours = minutes / 60
        val remain = minutes % 60
        return if (hours > 0 && remain > 0) "${hours}h ${remain}m" else if (hours > 0) "${hours}h" else "${minutes}m"
    }

    suspend fun titleLine(playlist: SavedPlaylist, series: Boolean, id: String): String = withContext(Dispatchers.IO) {
        val cacheKey = "${playlist.id}:${if (series) "s" else "v"}:$id"
        titleLineCache[cacheKey]?.let { (savedAt, line) ->
            if (System.currentTimeMillis() - savedAt < TITLE_LINE_TTL_MS) return@withContext line
        }
        val action = if (series) "get_series_info" else "get_vod_info"
        val key = if (series) "series_id" else "vod_id"
        val request = Request.Builder()
            .url(buildApiUrl(playlist, action, mapOf(key to id)))
            .get()
            .build()
        val line = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext ""
            val body = response.body ?: return@withContext ""
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return@withContext ""
                reader.beginObject()
                var parsed = ""
                while (reader.hasNext()) {
                    if (reader.nextName() == "info") parsed = readTitleLine(reader) else reader.skipValue()
                }
                reader.endObject()
                parsed
            }
        }
        if (line.isNotBlank()) {
            if (titleLineCache.size >= TITLE_LINE_CACHE_CAP) {
                titleLineCache.keys.take(40).forEach { titleLineCache.remove(it) }
            }
            titleLineCache[cacheKey] = System.currentTimeMillis() to line
        }
        line
    }

    private fun readTitleLine(reader: JsonReader): String {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return ""
        }
        var plot: String? = null
        var year: String? = null
        var length: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "plot", "description" -> plot = reader.nextText()
                "releasedate", "releaseDate", "release_date" -> year = reader.nextText()?.take(4)?.takeIf { it.all(Char::isDigit) }
                "duration" -> length = reader.nextText()
                "duration_secs" -> {
                    val seconds = reader.nextText()?.toIntOrNull()
                    if (length.isNullOrBlank() && seconds != null && seconds > 0) {
                        val hours = seconds / 3600
                        val minutes = (seconds % 3600) / 60
                        length = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                    }
                }
                "episode_run_time" -> if (length.isNullOrBlank()) length = reader.nextText()?.let { "$it min" } else reader.skipValue()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return listOfNotNull(
            year?.takeIf { it.isNotBlank() },
            length?.takeIf { it.isNotBlank() },
            plot?.trim()?.takeIf { it.isNotBlank() }?.take(160)
        ).joinToString(" · ")
    }

    private fun JsonReader.nextText(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> if (nextBoolean()) "1" else "0"
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun nameGroups(channels: List<Channel>, categories: List<Category>): List<Channel> {
        val names = categories.associate { it.id to it.name }
        return channels.map { channel ->
            val named = channel.groupTitle?.takeIf { it.isNotBlank() }
                ?: names[channel.categoryId]
            if (named.isNullOrBlank() || named == channel.groupTitle) channel
            else channel.copy(groupTitle = named)
        }
    }

    suspend fun getShortEpg(
        playlist: SavedPlaylist,
        streamId: String,
        limit: Int = 48
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(
                buildApiUrl(
                    playlist,
                    action = "get_short_epg",
                    extra = mapOf("stream_id" to streamId, "limit" to limit.toString())
                )
            )
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body ?: return@withContext emptyList()
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                val programs = ArrayList<EpgProgram>()
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> readEpgArray(reader, programs)
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if ((name == "epg_listings" || name == "listing") && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                readEpgArray(reader, programs)
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
                programs.sortedBy { it.startMillis }
            }
        }
    }

    private fun readEpgArray(reader: JsonReader, out: MutableList<EpgProgram>) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                readEpgProgram(reader)?.let { out.add(it) }
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
    }

    private fun readEpgProgram(reader: JsonReader): EpgProgram? {
        var title: String? = null
        var description: String? = null
        var startStamp: String? = null
        var startText: String? = null
        var stopStamp: String? = null
        var endText: String? = null
        var stopText: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> title = reader.nextText()
                "description" -> description = reader.nextText()
                "start_timestamp" -> startStamp = reader.nextText()
                "start" -> startText = reader.nextText()
                "stop_timestamp" -> stopStamp = reader.nextText()
                "end" -> endText = reader.nextText()
                "stop" -> stopText = reader.nextText()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val decoded = EpgUtils.decodeTitle(title).ifBlank { return null }
        val start = EpgUtils.parseTimeToMillis(startStamp) ?: EpgUtils.parseTimeToMillis(startText) ?: return null
        val end = EpgUtils.parseTimeToMillis(stopStamp)
            ?: EpgUtils.parseTimeToMillis(endText)
            ?: EpgUtils.parseTimeToMillis(stopText)
            ?: return null
        return EpgProgram(
            title = decoded,
            startMillis = start,
            endMillis = end,
            description = EpgUtils.decodeTitle(description).ifBlank { null }
        )
    }

    private fun readChannels(
        playlist: SavedPlaylist,
        action: String,
        label: String,
        onCount: (Int) -> Unit = {},
        onPercent: (Int) -> Unit = {},
        onBatch: ((List<Channel>) -> Unit)? = null,
        extra: Map<String, String> = emptyMap(),
        map: (Map<String, String>) -> Channel?
    ): List<Channel> {
        val request = Request.Builder().url(buildApiUrl(playlist, action, extra)).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("$label did not load (HTTP ${response.code})")
            val body = response.body ?: return emptyList()
            val total = body.contentLength()
            val counted = ProgressStream(body.byteStream(), total, onPercent)
            val stream = BufferedInputStream(counted, 256 * 1024)
            val kind = peekJsonKind(stream)
            if (kind != '['.code) {
                val sink = ChannelSink(onCount, onBatch)
                val fields = HashMap<String, String>(8)
                try {
                    JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        when (reader.peek()) {
                            JsonToken.BEGIN_ARRAY -> readArray(reader, sink, fields, map)
                            JsonToken.BEGIN_OBJECT -> readObject(reader, sink, fields, map)
                            else -> throw IllegalStateException("$label did not load")
                        }
                    }
                } catch (error: Exception) {
                    if (sink.seen == 0) throw error
                }
                sink.finish()
                return sink.channels
            }
            return parseArrayInChunks(stream, label, onCount, onBatch, map)
        }
    }

    private fun parseArrayInChunks(
        stream: InputStream,
        label: String,
        onCount: (Int) -> Unit,
        onBatch: ((List<Channel>) -> Unit)?,
        map: (Map<String, String>) -> Channel?
    ): List<Channel> {
        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        val pool = Executors.newFixedThreadPool(workers) { runnable ->
            Thread(runnable, "catalog-parse").apply { isDaemon = true }
        }
        val slots = java.util.concurrent.Semaphore(workers)
        val pending = ArrayList<java.util.concurrent.Future<List<Channel>>>()
        val splitter = JsonArraySplitter(stream)
        val chunk = ByteArrayOutputStream(256 * 1024)
        var count = 0
        fun submit() {
            val payload = wrapJsonArray(chunk.toByteArray())
            chunk.reset()
            count = 0
            slots.acquire()
            pending.add(pool.submit(java.util.concurrent.Callable {
                try {
                    parseChunk(payload, onCount, onBatch, map)
                } finally {
                    slots.release()
                }
            }))
        }
        try {
            while (splitter.writeNextObject(chunk, count > 0)) {
                count++
                if (count >= 500) submit()
            }
            if (count > 0) submit()
            val combined = ArrayList<Channel>()
            for (future in pending) {
                try {
                    val rows = future.get()
                    if (onBatch == null && rows.isNotEmpty()) combined.addAll(rows)
                } catch (error: ExecutionException) {
                    pending.forEach { it.cancel(true) }
                    throw error.cause ?: error
                }
            }
            return combined
        } catch (error: InterruptedException) {
            pending.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            pending.forEach { it.cancel(true) }
            if (error is IllegalStateException) throw error
            throw IllegalStateException("$label did not load", error)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun parseChunk(
        payload: ByteArray,
        onCount: (Int) -> Unit,
        onBatch: ((List<Channel>) -> Unit)?,
        map: (Map<String, String>) -> Channel?
    ): List<Channel> {
        val sink = ChannelSink(onCount, onBatch)
        val fields = HashMap<String, String>(8)
        JsonReader(InputStreamReader(ByteArrayInputStream(payload), Charsets.UTF_8)).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_ARRAY) throw IllegalStateException("Catalog chunk did not parse")
            readArray(reader, sink, fields, map)
        }
        sink.finish()
        return sink.channels
    }

    private fun wrapJsonArray(body: ByteArray): ByteArray {
        val wrapped = ByteArray(body.size + 2)
        wrapped[0] = '['.code.toByte()
        System.arraycopy(body, 0, wrapped, 1, body.size)
        wrapped[wrapped.lastIndex] = ']'.code.toByte()
        return wrapped
    }

    private fun peekJsonKind(stream: BufferedInputStream): Int {
        stream.mark(4096)
        var kind = -1
        val first = stream.read()
        if (first == 0xEF) {
            val second = stream.read()
            val third = stream.read()
            if (second != 0xBB || third != 0xBF) kind = first
        } else if (first >= 0 && !first.toChar().isWhitespace()) {
            kind = first
        }
        if (kind < 0) {
            while (true) {
                val next = stream.read()
                if (next < 0) break
                if (!next.toChar().isWhitespace()) {
                    kind = next
                    break
                }
            }
        }
        stream.reset()
        return kind
    }

    private class JsonArraySplitter(private val input: InputStream) {
        private val buffer = ByteArray(256 * 1024)
        private var position = 0
        private var limit = 0
        private var opened = false
        private var finished = false

        fun writeNextObject(out: ByteArrayOutputStream, comma: Boolean): Boolean {
            if (finished) return false
            while (true) {
                val value = nextByte()
                if (value < 0) {
                    finished = true
                    return false
                }
                when (value) {
                    ' '.code, '\n'.code, '\r'.code, '\t'.code, ','.code -> continue
                    0xEF -> {
                        if (opened || nextByte() != 0xBB || nextByte() != 0xBF) {
                            throw IllegalStateException("Catalog was not a list")
                        }
                    }
                    '['.code -> if (!opened) {
                        opened = true
                        continue
                    } else {
                        throw IllegalStateException("Catalog was not a list")
                    }
                    ']'.code -> {
                        finished = true
                        return false
                    }
                    '{'.code -> {
                        if (comma) out.write(','.code)
                        writeObject(out)
                        return true
                    }
                    else -> throw IllegalStateException("Catalog was not a list")
                }
            }
        }

        private fun writeObject(out: ByteArrayOutputStream) {
            out.write('{'.code)
            var depth = 1
            var inString = false
            var escape = false
            while (depth > 0) {
                val value = nextByte()
                if (value < 0) throw IllegalStateException("Catalog ended early")
                out.write(value)
                if (inString) {
                    if (escape) {
                        escape = false
                    } else if (value == '\\'.code) {
                        escape = true
                    } else if (value == '"'.code) {
                        inString = false
                    }
                } else when (value) {
                    '"'.code -> inString = true
                    '{'.code -> depth++
                    '}'.code -> depth--
                }
            }
        }

        private fun nextByte(): Int {
            if (position >= limit) {
                limit = input.read(buffer)
                position = 0
                if (limit <= 0) return -1
            }
            return buffer[position++].toInt() and 0xFF
        }
    }

    private class ChannelSink(
        private val onCount: (Int) -> Unit,
        private val onBatch: ((List<Channel>) -> Unit)?
    ) {
        val channels = ArrayList<Channel>()
        var seen = 0

        fun add(channel: Channel) {
            channels.add(channel)
            seen++
            if (seen % 200 == 0) onCount(seen)
            if (onBatch != null && channels.size >= 500) flush()
        }

        fun finish() {
            if (onBatch != null && channels.isNotEmpty()) flush()
            onCount(seen)
        }

        private fun flush() {
            onBatch?.invoke(ArrayList(channels))
            channels.clear()
        }
    }

    private fun readArray(
        reader: JsonReader,
        sink: ChannelSink,
        fields: HashMap<String, String>,
        map: (Map<String, String>) -> Channel?
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                readItem(reader, sink, fields, map)
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
    }

    private fun readObject(
        reader: JsonReader,
        sink: ChannelSink,
        fields: HashMap<String, String>,
        map: (Map<String, String>) -> Channel?
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName()
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> readItem(reader, sink, fields, map)
                JsonToken.BEGIN_ARRAY -> readArray(reader, sink, fields, map)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readItem(
        reader: JsonReader,
        sink: ChannelSink,
        fields: HashMap<String, String>,
        map: (Map<String, String>) -> Channel?
    ) {
        fields.clear()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key !in keptFields) {
                reader.skipValue()
                continue
            }
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> fields[key] = reader.nextString()
                JsonToken.BOOLEAN -> fields[key] = if (reader.nextBoolean()) "1" else "0"
                JsonToken.NULL -> reader.nextNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        map(fields)?.let { sink.add(it) }
    }

    private class ProgressStream(
        private val source: InputStream,
        private val total: Long,
        private val onPercent: (Int) -> Unit
    ) : InputStream() {
        private var readBytes = 0L
        private var lastPercent = -1

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) advance(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = source.read(buffer, offset, length)
            if (count > 0) advance(count.toLong())
            return count
        }

        override fun close() = source.close()

        private fun advance(count: Long) {
            readBytes += count
            val raw = if (total > 0L) {
                ((readBytes * 95) / total).toInt()
            } else {
                ((readBytes / (3L * 1024 * 1024)).toInt() * 5).coerceAtMost(90)
            }
            val stepped = (raw / 5) * 5
            if (stepped > lastPercent) {
                lastPercent = stepped
                onPercent(stepped.coerceIn(0, 95))
            }
        }
    }

    private fun buildApiUrl(
        playlist: SavedPlaylist,
        action: String? = null,
        extra: Map<String, String> = emptyMap()
    ): okhttp3.HttpUrl {
        val host = normalizeHost(playlist.xtreamHost.orEmpty())
        val base = "$host/player_api.php".toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid Xtream host")
        base.addQueryParameter("username", playlist.xtreamUsername ?: playlist.username.orEmpty())
        base.addQueryParameter("password", playlist.xtreamPassword ?: playlist.password.orEmpty())
        if (!action.isNullOrBlank()) base.addQueryParameter("action", action)
        extra.forEach { (key, value) -> base.addQueryParameter(key, value) }
        return base.build()
    }

    private fun getJson(
        playlist: SavedPlaylist,
        action: String? = null,
        extra: Map<String, String> = emptyMap()
    ): JsonElement? {
        val request = Request.Builder().url(buildApiUrl(playlist, action, extra)).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Xtream HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return JsonParser.parseString(body)
        }
    }

    private fun parseCategories(
        element: JsonElement?,
        kind: ContentKind,
        required: Boolean = false
    ): List<Category> {
        if (element == null || !element.isJsonArray) {
            if (required) throw IllegalStateException("Categories did not load")
            return emptyList()
        }
        val array = element.asJsonArray
        return array.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.string("category_id") ?: return@mapNotNull null
            val name = obj.string("category_name") ?: id
            Category(id = id, name = name, kind = kind)
        }
    }

    private fun parseLiveStreams(
        element: JsonElement?,
        host: String,
        user: String,
        pass: String
    ): List<Channel> {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val streamId = obj.string("stream_id") ?: return@mapNotNull null
            val name = obj.string("name") ?: "Channel $streamId"
            val archive = obj.string("tv_archive")?.let { it == "1" || it.equals("true", true) }
                ?: (obj.int("tv_archive")?.let { it == 1 })
            Channel(
                id = streamId,
                name = name,
                streamUrl = "$host/live/$user/$pass/$streamId.m3u8",
                logoUrl = obj.string("stream_icon"),
                groupTitle = obj.string("category_name"),
                categoryId = obj.string("category_id"),
                kind = ContentKind.LIVE,
                number = obj.int("num"),
                tvArchive = archive,
                tvArchiveDuration = obj.int("tv_archive_duration")
            )
        }
    }

    private fun parseVodStreams(
        element: JsonElement?,
        host: String,
        user: String,
        pass: String
    ): List<Channel> {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val streamId = obj.string("stream_id") ?: return@mapNotNull null
            val ext = obj.string("container_extension") ?: "mp4"
            val name = obj.string("name") ?: "Movie $streamId"
            Channel(
                id = streamId,
                name = name,
                streamUrl = "$host/movie/$user/$pass/$streamId.$ext",
                logoUrl = obj.string("stream_icon"),
                groupTitle = obj.string("category_name"),
                categoryId = obj.string("category_id"),
                kind = ContentKind.VOD,
                containerExtension = ext,
                number = obj.int("num")
            )
        }
    }

    private fun parseSeries(element: JsonElement?): List<Channel> {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val seriesId = obj.string("series_id") ?: return@mapNotNull null
            Channel(
                id = seriesId,
                name = obj.string("name") ?: "Series $seriesId",
                streamUrl = "",
                logoUrl = obj.string("cover") ?: obj.string("stream_icon"),
                groupTitle = obj.string("category_name"),
                categoryId = obj.string("category_id"),
                kind = ContentKind.VOD,
                number = obj.int("num"),
                seriesId = seriesId
            )
        }
    }

    private fun JsonObject.int(key: String): Int? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive || el.isJsonNull) return null
        return runCatching { el.asInt }.getOrNull() ?: el.asString.toIntOrNull()
    }

    private fun JsonObject.string(key: String): String? {
        val el = get(key) ?: return null
        return when {
            el.isJsonNull -> null
            el.isJsonPrimitive -> el.asString
            else -> el.toString()
        }?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val keptFields = setOf(
            "stream_id",
            "series_id",
            "name",
            "stream_icon",
            "cover",
            "category_name",
            "category_id",
            "container_extension",
            "num"
        )

        fun normalizeHost(raw: String): String {
            var host = raw.trim().trimEnd('/')
            if (!host.startsWith("http://") && !host.startsWith("https://")) {
                host = "http://$host"
            }
            return host
        }

        val sharedHttp: OkHttpClient get() = HttpClients.xtream

        private val titleLineCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
        private const val TITLE_LINE_CACHE_CAP = 200
        private val TITLE_LINE_TTL_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(6)
    }
}
