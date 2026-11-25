package com.olympus.iptv.playlist

import com.google.gson.JsonParser
import com.olympus.iptv.BuildConfig
import okhttp3.Request
import java.net.URLEncoder

object MediaDetailMetadata {
    data class CastMember(
        val id: Int,
        val name: String,
        val role: String,
        val photoUrl: String?
    )

    data class EpisodeInfo(
        val episodeNumber: Int,
        val title: String,
        val airDate: String,
        val runtimeMinutes: Int?,
        val overview: String,
        val stillUrl: String?
    )

    data class MediaDetail(
        val tmdbId: Int,
        val isTv: Boolean,
        val title: String,
        val logoUrl: String?,
        val backdropUrl: String?,
        val posterUrl: String?,
        val rating: String?,
        val years: String?,
        val network: String?,
        val seasonCount: Int?,
        val contentRating: String?,
        val genres: List<String>,
        val overview: String,
        val trailerKey: String?,
        val cast: List<CastMember>
    )

    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
    private const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
    private const val IMG_LOGO = "https://image.tmdb.org/t/p/w500"
    private const val IMG_STILL = "https://image.tmdb.org/t/p/w500"
    private const val IMG_PROFILE = "https://image.tmdb.org/t/p/w300"

    fun loadDetail(rawTitle: String, isTv: Boolean, knownTmdbId: Int = 0): MediaDetail? {
        val key = BuildConfig.TMDB_API_KEY.trim()
        if (key.isBlank()) return null
        val cleaned = com.olympus.iptv.data.Titles.clean(rawTitle)
        val type = if (isTv) "tv" else "movie"
        val id = if (knownTmdbId > 0) knownTmdbId else searchTmdbId(key, cleaned, type) ?: return null

        val append = if (isTv) "credits,videos,images,content_ratings" else "credits,videos,images,release_dates"
        val url = "https://api.themoviedb.org/3/$type/$id?api_key=$key&append_to_response=$append&include_image_language=en,null"
        val body = getJson(url) ?: return null
        val root = JsonParser.parseString(body).asJsonObject

        val title = (if (isTv) root.str("name") else root.str("title")).ifBlank { cleaned }
        val backdrop = root.str("backdrop_path").takeIf { it.isNotBlank() }?.let { "$IMG_BACKDROP$it" }
        val poster = root.str("poster_path").takeIf { it.isNotBlank() }?.let { "$IMG_POSTER$it" }
        val vote = root.get("vote_average")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
        val rating = if (vote > 0.1) String.format("%.1f", vote) else null

        val years = if (isTv) {
            val first = root.str("first_air_date").take(4)
            val last = root.str("last_air_date").take(4)
            when {
                first.isNotBlank() && last.isNotBlank() && first != last -> "$first – $last"
                first.isNotBlank() -> first
                else -> null
            }
        } else {
            root.str("release_date").take(4).takeIf { it.isNotBlank() }
        }

        val network = if (isTv) {
            root.getAsJsonArray("networks")?.firstOrNull()?.asJsonObject?.str("name")?.takeIf { it.isNotBlank() }
        } else {
            root.getAsJsonArray("production_companies")?.firstOrNull()?.asJsonObject?.str("name")?.takeIf { it.isNotBlank() }
        }

        val seasons = if (isTv) root.get("number_of_seasons")?.takeIf { !it.isJsonNull }?.asInt else null
        val genres = ArrayList<String>()
        root.getAsJsonArray("genres")?.forEach { el ->
            val g = el.asJsonObject.str("name")
            if (g.isNotBlank()) genres.add(g)
        }

        val contentRating = extractContentRating(root, isTv)
        val logoUrl = extractLogo(root)
        val trailerKey = extractTrailer(root)
        val cast = extractCast(root)
        val overview = root.str("overview")

        return MediaDetail(
            tmdbId = id,
            isTv = isTv,
            title = title,
            logoUrl = logoUrl,
            backdropUrl = backdrop,
            posterUrl = poster,
            rating = rating,
            years = years,
            network = network,
            seasonCount = seasons,
            contentRating = contentRating,
            genres = genres,
            overview = overview,
            trailerKey = trailerKey,
            cast = cast
        )
    }

    fun loadSeasonEpisodes(tmdbId: Int, seasonNumber: Int): Map<Int, EpisodeInfo> {
        val key = BuildConfig.TMDB_API_KEY.trim()
        if (key.isBlank() || tmdbId <= 0) return emptyMap()
        val url = "https://api.themoviedb.org/3/tv/$tmdbId/season/$seasonNumber?api_key=$key"
        val body = getJson(url) ?: return emptyMap()
        val root = JsonParser.parseString(body).asJsonObject
        val out = LinkedHashMap<Int, EpisodeInfo>()
        root.getAsJsonArray("episodes")?.forEach { el ->
            val obj = el.asJsonObject
            val num = obj.get("episode_number")?.asInt ?: return@forEach
            val name = obj.str("name")
            val air = formatAirDate(obj.str("air_date"))
            val runtime = obj.get("runtime")?.takeIf { !it.isJsonNull }?.asInt
            val overview = obj.str("overview")
            val still = obj.str("still_path").takeIf { it.isNotBlank() }?.let { "$IMG_STILL$it" }
            out[num] = EpisodeInfo(num, name, air, runtime, overview, still)
        }
        return out
    }

    private fun searchTmdbId(key: String, title: String, type: String): Int? {
        val q = URLEncoder.encode(title, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/$type?api_key=$key&query=$q"
        val body = getJson(url) ?: return null
        val results = JsonParser.parseString(body).asJsonObject.getAsJsonArray("results") ?: return null
        return results.firstOrNull()?.asJsonObject?.get("id")?.asInt
    }

    private fun extractLogo(root: com.google.gson.JsonObject): String? {
        val logos = root.getAsJsonObject("images")?.getAsJsonArray("logos") ?: return null
        val en = logos.firstOrNull { it.asJsonObject.str("iso_639_1") == "en" }?.asJsonObject
            ?: logos.firstOrNull()?.asJsonObject
        val path = en?.str("file_path").orEmpty()
        return if (path.isNotBlank()) "$IMG_LOGO$path" else null
    }

    private fun extractContentRating(root: com.google.gson.JsonObject, isTv: Boolean): String? {
        if (isTv) {
            val arr = root.getAsJsonObject("content_ratings")?.getAsJsonArray("results") ?: return null
            val us = arr.firstOrNull { it.asJsonObject.str("iso_3166_1") == "US" }?.asJsonObject
                ?: arr.firstOrNull()?.asJsonObject
            return us?.str("rating")?.takeIf { it.isNotBlank() }
        }
        val arr = root.getAsJsonObject("release_dates")?.getAsJsonArray("results") ?: return null
        val us = arr.firstOrNull { it.asJsonObject.str("iso_3166_1") == "US" }?.asJsonObject ?: return null
        val dates = us.getAsJsonArray("release_dates") ?: return null
        return dates.firstOrNull { it.asJsonObject.str("certification").isNotBlank() }
            ?.asJsonObject?.str("certification")
    }

    private fun extractTrailer(root: com.google.gson.JsonObject): String? {
        val vids = root.getAsJsonObject("videos")?.getAsJsonArray("results") ?: return null
        val yt = vids.firstOrNull {
            val o = it.asJsonObject
            o.str("site").equals("YouTube", true) && o.str("type").equals("Trailer", true)
        } ?: vids.firstOrNull { it.asJsonObject.str("site").equals("YouTube", true) }
        return yt?.asJsonObject?.str("key")?.takeIf { it.isNotBlank() }
    }

    private fun extractCast(root: com.google.gson.JsonObject): List<CastMember> {
        val arr = root.getAsJsonObject("credits")?.getAsJsonArray("cast") ?: return emptyList()
        val out = ArrayList<CastMember>(16)
        for (el in arr) {
            if (out.size >= 16) break
            val o = el.asJsonObject
            val id = o.get("id")?.asInt ?: continue
            val name = o.str("name")
            if (name.isBlank()) continue
            val role = o.str("character")
            val photo = o.str("profile_path").takeIf { it.isNotBlank() }?.let { "$IMG_PROFILE$it" }
            out.add(CastMember(id, name, role, photo))
        }
        return out
    }

    private fun formatAirDate(iso: String): String {
        if (iso.length < 10) return iso
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val y = iso.substring(0, 4)
        val m = iso.substring(5, 7).toIntOrNull()?.minus(1)?.let { months.getOrNull(it) } ?: return iso
        val d = iso.substring(8, 10).toIntOrNull() ?: return iso
        return "$m $d, $y"
    }

    private fun getJson(url: String): String? = runCatching {
        val req = Request.Builder().url(url).get().build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private fun com.google.gson.JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
}
