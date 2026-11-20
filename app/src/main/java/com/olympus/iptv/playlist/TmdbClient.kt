package com.olympus.iptv.playlist

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class TmdbRatings(
    val title: String,
    val voteAverage: Double,
    val voteCount: Int,
    val overview: String,
    val tmdbId: Int,
    val mediaType: String
)

data class TmdbTrailer(
    val key: String,
    val name: String
)

class TmdbClient(
    private val apiKey: String,
    private val http: OkHttpClient = HttpClients.shared
) {
    fun configured(): Boolean = apiKey.isNotBlank()

    fun searchTitle(name: String, preferTv: Boolean, limit: Int = 10): List<TmdbRatings> {
        if (!configured() || name.isBlank()) return emptyList()
        val type = if (preferTv) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/search/$type".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("api_key", apiKey)
            ?.addQueryParameter("query", name)
            ?.addQueryParameter("include_adult", "false")
            ?.build()
            ?: return emptyList()
        val body = get(url.toString()) ?: return emptyList()
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val results = root.getAsJsonArray("results") ?: return emptyList()
        val out = ArrayList<TmdbRatings>(limit)
        for (element in results) {
            if (out.size >= limit.coerceIn(1, 50)) break
            val obj = element.asJsonObject
            val id = obj.get("id")?.asInt ?: continue
            val title = obj.get("title")?.asString
                ?: obj.get("name")?.asString
                ?: continue
            out.add(
                TmdbRatings(
                    title = title,
                    voteAverage = obj.get("vote_average")?.asDouble ?: 0.0,
                    voteCount = obj.get("vote_count")?.asInt ?: 0,
                    overview = obj.get("overview")?.asString.orEmpty(),
                    tmdbId = id,
                    mediaType = type
                )
            )
        }
        return out
    }

    fun ratingsFor(name: String, preferTv: Boolean): TmdbRatings? =
        searchTitle(name, preferTv, limit = 1).firstOrNull()

    fun trailer(tmdbId: Int, mediaType: String): TmdbTrailer? {
        if (!configured() || tmdbId <= 0) return null
        val kind = if (mediaType == "tv") "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$kind/$tmdbId/videos".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("api_key", apiKey)
            ?.build()
            ?: return null
        val body = get(url.toString()) ?: return null
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val results = root.getAsJsonArray("results") ?: return null
        var fallback: TmdbTrailer? = null
        for (element in results) {
            val obj = element.asJsonObject
            if (obj.get("site")?.asString != "YouTube") continue
            val key = obj.get("key")?.asString?.takeIf { it.isNotBlank() } ?: continue
            val name = obj.get("name")?.asString.orEmpty()
            val type = obj.get("type")?.asString.orEmpty()
            val hit = TmdbTrailer(key, name.ifBlank { "Trailer" })
            if (type.equals("Trailer", ignoreCase = true)) return hit
            if (fallback == null) fallback = hit
        }
        return fallback
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }
}
