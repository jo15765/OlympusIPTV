package com.olympus.iptv.playlist

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.olympus.iptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SourceRating(
    val source: String,
    val scoreLabel: String,
    val detail: String = ""
)

data class ReviewSnippet(
    val author: String,
    val content: String,
    val source: String
)

data class TitleRatingsBundle(
    val title: String,
    val overview: String,
    val ratings: List<SourceRating>,
    val reviews: List<ReviewSnippet>,
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val hasReviews: Boolean = false
) {
    fun scoreLine(max: Int = 3): String =
        ratings.take(max).joinToString(" · ") { "${it.source} ${it.scoreLabel}" }
}

object TitleRatings {
    private val gson get() = com.olympus.iptv.data.AppJson.gson
    private val http get() = HttpClients.shared
    private const val CACHE_CAP = 80
    private val TTL_MS = TimeUnit.HOURS.toMillis(24)
    private val PERSIST_DEBOUNCE_MS = TimeUnit.SECONDS.toMillis(45)
    private val lock = Any()
    private val scoreCache = object : LinkedHashMap<String, CacheEntry>(CACHE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > CACHE_CAP
    }
    private val fullCache = object : LinkedHashMap<String, CacheEntry>(CACHE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > CACHE_CAP
    }
    @Volatile private var diskFile: File? = null
    @Volatile private var diskDirty = false
    @Volatile private var lastPersistAt = 0L
    @Volatile private var catalogDb: CatalogDb? = null

    private data class CacheEntry(
        val savedAt: Long,
        val bundle: TitleRatingsBundle
    )

    private data class DiskBlob(
        val scores: Map<String, DiskRow> = emptyMap(),
        val full: Map<String, DiskRow> = emptyMap()
    )

    private data class DiskRow(
        val savedAt: Long,
        val bundle: TitleRatingsBundle
    )

    fun init(context: Context, db: CatalogDb? = null) {
        catalogDb = db
        val file = File(context.cacheDir, "title_ratings_cache.json")
        diskFile = file
        if (!file.exists()) return
        runCatching {
            val blob = gson.fromJson(file.readText(), DiskBlob::class.java) ?: return
            val now = System.currentTimeMillis()
            synchronized(lock) {
                blob.scores.forEach { (k, row) ->
                    if (now - row.savedAt <= TTL_MS) scoreCache[k] = CacheEntry(row.savedAt, row.bundle)
                }
                blob.full.forEach { (k, row) ->
                    if (now - row.savedAt <= TTL_MS) fullCache[k] = CacheEntry(row.savedAt, row.bundle)
                }
            }
        }
    }

    private fun emptyBundle(name: String) =
        TitleRatingsBundle(title = name, overview = "", ratings = emptyList(), reviews = emptyList())

    private fun isNegativeMiss(key: String): Boolean =
        catalogDb?.isMetaMiss(key) == true

    private fun rememberNegativeMiss(key: String, bundle: TitleRatingsBundle) {
        if (bundle.tmdbId != null || bundle.ratings.isNotEmpty()) {
            catalogDb?.clearMetaMiss(key)
            return
        }
        catalogDb?.putMetaMiss(key)
    }

    private val bracketNoise = Regex("""\[[^\]]*]|\{[^}]*\}""")
    private val qualityParens = Regex(
        """\((?:[^)]*(?:4K|UHD|1080|720|480|HEVC|H\.?26[45]|HDR|DV|WEB-?DL|BluRay)[^)]*)\)""",
        RegexOption.IGNORE_CASE
    )
    private val qualityTokens = Regex(
        """\b(4K|UHD|1080p?|720p?|480p?|HEVC|H\.?265|H\.?264|HDR10?\+?|DV|WEB-?DL|Blu-?Ray|x264|x265|AAC|DTS)\b""",
        RegexOption.IGNORE_CASE
    )
    private val pipes = Regex("""\|+""")
    private val multiSpace = Regex("""\s{2,}""")

    fun cleanTitle(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return runCatching {
            var s = trimmed
            s = s.replace(bracketNoise, " ")
            s = s.replace(qualityParens, " ")
            s = s.replace(qualityTokens, " ")
            s = s.replace(pipes, " ")
            s = s.replace(multiSpace, " ").trim()
            s.ifBlank { trimmed }
        }.getOrDefault(trimmed)
    }

    fun cacheKey(name: String, preferTv: Boolean): String =
        "${if (preferTv) "tv" else "movie"}:${cleanTitle(name).lowercase(Locale.US)}"

    fun peek(name: String, preferTv: Boolean): TitleRatingsBundle? {
        val key = cacheKey(name, preferTv)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            fullCache[key]?.takeIf { now - it.savedAt <= TTL_MS }?.let { return it.bundle }
            scoreCache[key]?.takeIf { now - it.savedAt <= TTL_MS }?.let { return it.bundle }
        }
        return null
    }

    fun loadScores(name: String, preferTv: Boolean): TitleRatingsBundle {
        val clean = cleanTitle(name)
        if (clean.isBlank()) return emptyBundle(name)
        peek(clean, preferTv)?.let { return it.copy(reviews = emptyList()) }

        val key = cacheKey(clean, preferTv)
        if (isNegativeMiss(key)) return emptyBundle(clean)

        return MetaLookupGate.withPermit {
            peek(clean, preferTv)?.let { return@withPermit it.copy(reviews = emptyList()) }
            if (isNegativeMiss(key)) return@withPermit emptyBundle(clean)
            val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
            val tmdb = if (tmdbKey.isNotBlank()) {
                runCatching {
                    TmdbClient(tmdbKey, http).searchTitle(clean, preferTv, limit = 1).firstOrNull()
                }.getOrNull()
            } else null
            val bundle = assemble(clean, tmdb, watch = null, reviews = emptyList(), hasReviews = false)
            putScore(key, bundle)
            rememberNegativeMiss(key, bundle)
            bundle
        }
    }

    suspend fun loadFull(name: String, preferTv: Boolean, tmdbLimit: Int = 10): TitleRatingsBundle {
        val clean = cleanTitle(name)
        if (clean.isBlank()) return emptyBundle(name)
        val key = cacheKey(clean, preferTv)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            fullCache[key]?.takeIf { now - it.savedAt <= TTL_MS && (it.bundle.hasReviews || it.bundle.tmdbId == null) }
                ?.let { return it.bundle }
        }
        if (isNegativeMiss(key)) return emptyBundle(clean)

        return MetaLookupGate.withPermitAsync {
            synchronized(lock) {
                fullCache[key]?.takeIf {
                    System.currentTimeMillis() - it.savedAt <= TTL_MS &&
                        (it.bundle.hasReviews || it.bundle.tmdbId == null)
                }?.let { return@withPermitAsync it.bundle }
            }
            if (isNegativeMiss(key)) return@withPermitAsync emptyBundle(clean)
            coroutineScope {
                val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
                val watchKey = BuildConfig.WATCHMODE_API_KEY.trim()
                val client = if (tmdbKey.isNotBlank()) TmdbClient(tmdbKey, http) else null
                val tmdbDef = async(Dispatchers.IO) {
                    if (client == null) null
                    else runCatching {
                        client.searchTitle(clean, preferTv, tmdbLimit.coerceIn(1, 50)).firstOrNull()
                    }.getOrNull()
                }
                val watchDef = async(Dispatchers.IO) {
                    if (watchKey.isBlank()) null
                    else runCatching { watchmodeMeta(watchKey, clean, preferTv) }.getOrNull()
                }
                val tmdb = tmdbDef.await()
                val watch = watchDef.await()
                val reviews = if (tmdb != null && client != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { tmdbReviews(tmdbKey, tmdb.tmdbId, tmdb.mediaType) }.getOrDefault(emptyList())
                    }
                } else emptyList()
                val bundle = assemble(clean, tmdb, watch, reviews, hasReviews = true)
                putFull(key, bundle)
                putScore(key, bundle.copy(reviews = emptyList(), hasReviews = false))
                rememberNegativeMiss(key, bundle)
                bundle
            }
        }
    }

    suspend fun load(name: String, preferTv: Boolean, tmdbLimit: Int = 10): TitleRatingsBundle =
        loadFull(name, preferTv, tmdbLimit)

    fun trailerFor(name: String, preferTv: Boolean): TmdbTrailer? {
        val key = BuildConfig.TMDB_API_KEY.trim()
        if (key.isBlank()) return null
        val client = TmdbClient(key, http)
        val cached = peek(name, preferTv)
        val tmdbId = cached?.tmdbId
        val mediaType = cached?.mediaType
        if (tmdbId != null && !mediaType.isNullOrBlank()) {
            return runCatching { client.trailer(tmdbId, mediaType) }.getOrNull()
        }
        val hit = runCatching { client.ratingsFor(cleanTitle(name), preferTv) }.getOrNull() ?: return null
        putScore(
            cacheKey(cleanTitle(name), preferTv),
            assemble(cleanTitle(name), hit, watch = null, reviews = emptyList(), hasReviews = false)
        )
        return runCatching { client.trailer(hit.tmdbId, hit.mediaType) }.getOrNull()
    }

    private fun putScore(key: String, bundle: TitleRatingsBundle) {
        synchronized(lock) {
            scoreCache[key] = CacheEntry(System.currentTimeMillis(), bundle)
            diskDirty = true
        }
        maybePersist()
    }

    private fun putFull(key: String, bundle: TitleRatingsBundle) {
        synchronized(lock) {
            fullCache[key] = CacheEntry(System.currentTimeMillis(), bundle)
            diskDirty = true
        }
        maybePersist()
    }

    private fun maybePersist() {
        val file = diskFile ?: return
        val now = System.currentTimeMillis()

        if (!diskDirty || now - lastPersistAt < PERSIST_DEBOUNCE_MS) return
        val blob = synchronized(lock) {
            if (!diskDirty) return
            diskDirty = false
            lastPersistAt = now
            DiskBlob(
                scores = scoreCache.mapValues { DiskRow(it.value.savedAt, it.value.bundle) },
                full = fullCache.mapValues { DiskRow(it.value.savedAt, it.value.bundle) }
            )
        }
        runCatching { file.writeText(gson.toJson(blob)) }
    }

    private fun assemble(
        clean: String,
        tmdb: TmdbRatings?,
        watch: WatchMeta?,
        reviews: List<ReviewSnippet>,
        hasReviews: Boolean
    ): TitleRatingsBundle {
        val ratings = ArrayList<SourceRating>(4)
        if (tmdb != null && tmdb.voteAverage > 0.0) {
            ratings += SourceRating(
                source = "TMDB",
                scoreLabel = String.format(Locale.US, "%.1f/10", tmdb.voteAverage),
                detail = "${tmdb.voteCount} votes"
            )
        }
        if (watch?.userRating != null && watch.userRating > 0.0) {
            ratings += SourceRating(
                source = "Watchmode",
                scoreLabel = String.format(Locale.US, "%.1f/10", watch.userRating),
                detail = "user rating"
            )
        }
        if (watch?.criticScore != null && watch.criticScore > 0) {
            ratings += SourceRating(
                source = "Critics",
                scoreLabel = "${watch.criticScore}%",
                detail = "Watchmode critic score"
            )
        }
        if (watch?.imdbRating != null && watch.imdbRating > 0.0) {
            ratings += SourceRating(
                source = "IMDb",
                scoreLabel = String.format(Locale.US, "%.1f/10", watch.imdbRating),
                detail = watch.imdbId?.let { "tt$it" }.orEmpty()
            )
        }
        val overview = tmdb?.overview?.takeIf { it.isNotBlank() } ?: watch?.plot.orEmpty()
        return TitleRatingsBundle(
            title = tmdb?.title ?: watch?.title ?: clean,
            overview = overview,
            ratings = ratings,
            reviews = reviews,
            tmdbId = tmdb?.tmdbId,
            mediaType = tmdb?.mediaType,
            hasReviews = hasReviews
        )
    }

    private data class WatchMeta(
        val id: Int,
        val title: String,
        val plot: String?,
        val userRating: Double?,
        val criticScore: Int?,
        val imdbRating: Double?,
        val imdbId: String?
    )

    private fun watchmodeMeta(key: String, name: String, preferTv: Boolean): WatchMeta? {
        val type = if (preferTv) "tv_series" else "movie"
        val searchUrl = "https://api.watchmode.com/v1/search/".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("search_field", "name")
            ?.addQueryParameter("search_value", name)
            ?.addQueryParameter("types", type)
            ?.build()
            ?: return null
        val searchBody = get(searchUrl.toString(), key) ?: return null
        val root = runCatching { JsonParser.parseString(searchBody).asJsonObject }.getOrNull() ?: return null
        val titles = root.getAsJsonArray("title_results") ?: return null
        val first = titles.firstOrNull()?.asJsonObject ?: return null
        val id = first.get("id")?.asInt ?: return null
        val title = first.get("name")?.asString ?: name
        val detailsUrl = "https://api.watchmode.com/v1/title/$id/details/"
        val detailsBody = get(detailsUrl, key) ?: return WatchMeta(id, title, null, null, null, null, null)
        val row = runCatching { JsonParser.parseString(detailsBody).asJsonObject }.getOrNull()
            ?: return WatchMeta(id, title, null, null, null, null, null)
        return WatchMeta(
            id = id,
            title = row.get("title")?.asString ?: title,
            plot = row.get("plot_overview")?.asString,
            userRating = row.get("user_rating")?.asDouble,
            criticScore = row.get("critic_score")?.asInt,
            imdbRating = null,
            imdbId = row.get("imdb_id")?.asString?.removePrefix("tt")
        )
    }

    private fun tmdbReviews(apiKey: String, tmdbId: Int, mediaType: String): List<ReviewSnippet> {
        val kind = if (mediaType == "tv") "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$kind/$tmdbId/reviews".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("api_key", apiKey)
            ?.addQueryParameter("language", "en-US")
            ?.addQueryParameter("page", "1")
            ?.build()
            ?: return emptyList()
        val body = get(url.toString()) ?: return emptyList()
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val results = root.getAsJsonArray("results") ?: return emptyList()
        val out = ArrayList<ReviewSnippet>(3)
        for (element in results) {
            if (out.size >= 3) break
            val obj = element.asJsonObject
            val author = obj.get("author")?.asString?.takeIf { it.isNotBlank() } ?: "Reviewer"
            val content = obj.get("content")?.asString?.trim().orEmpty()
            if (content.isBlank()) continue
            out += ReviewSnippet(
                author = author,
                content = content.take(420).let { if (content.length > 420) "$it…" else it },
                source = "TMDB"
            )
        }
        return out
    }

    private fun get(url: String, watchmodeKey: String? = null): String? {
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        if (!watchmodeKey.isNullOrBlank()) builder.header("X-API-Key", watchmodeKey)
        return runCatching {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }
}
