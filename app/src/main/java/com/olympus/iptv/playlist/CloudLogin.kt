package com.olympus.iptv.playlist

import com.google.gson.JsonObject
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Applies a playlist login that was saved on the cloud dashboard. */
object CloudLogin {
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    data class Remote(
        val revision: Long,
        val email: String,
        val mode: String,
        val host: String,
        val url: String,
        val username: String,
        val password: String,
        val zip: String,
        val includeVod: Boolean,
        val includeInternational: Boolean
    )

    fun parse(json: JsonObject): Remote? {
        val email = json.get("email")?.asString?.trim().orEmpty()
        val mode = json.get("mode")?.asString?.trim().orEmpty().ifBlank { "xtream" }
        val host = json.get("host")?.asString?.trim().orEmpty()
        val url = json.get("url")?.asString?.trim().orEmpty()
        val username = json.get("username")?.asString?.trim().orEmpty()
        val password = json.get("password")?.asString.orEmpty()
        val zip = json.get("zip")?.asString?.filter { it.isDigit() }?.take(5).orEmpty()
        if (!email.contains("@")) return null
        if (mode == "m3u" && !url.startsWith("http")) return null
        if (mode != "m3u" && (!host.startsWith("http") || username.isBlank() || password.isBlank())) return null
        return Remote(
            revision = json.get("revision")?.asLong ?: 0L,
            email = email,
            mode = mode,
            host = host,
            url = url,
            username = username,
            password = password,
            zip = zip,
            includeVod = json.get("includeVod")?.asBoolean != false,
            includeInternational = json.get("includeInternational")?.asBoolean == true
        )
    }

    fun same(left: SavedPlaylist?, remote: Remote): Boolean {
        if (left == null) return false
        val host = if (remote.mode == "m3u") left.m3uUrl.orEmpty() else left.xtreamHost.orEmpty()
        val wantedHost = if (remote.mode == "m3u") remote.url else XtreamClient.normalizeHost(remote.host)
        return left.email.equals(remote.email, ignoreCase = true) &&
            host == wantedHost &&
            left.username == remote.username &&
            left.password == remote.password &&
            left.homeZip == remote.zip &&
            left.includeVod == remote.includeVod &&
            (left.includeInternational == true) == remote.includeInternational
    }

    suspend fun apply(app: OlympusIptvApp, remote: Remote): SavedPlaylist = withContext(Dispatchers.IO) {
        val existing = runCatching { app.playlistStore.session.first() }.getOrNull()
        val home = lookupZip(remote.zip)
        val playlist = SavedPlaylist(
            id = existing?.id ?: PlaylistStore.newId(),
            name = remote.username.ifBlank { remote.email.substringBefore("@") },
            type = if (remote.mode == "m3u") PlaylistType.M3U else PlaylistType.XTREAM,
            m3uUrl = if (remote.mode == "m3u") remote.url else null,
            xtreamHost = if (remote.mode == "m3u") null else XtreamClient.normalizeHost(remote.host),
            xtreamUsername = if (remote.mode == "m3u") null else remote.username,
            xtreamPassword = if (remote.mode == "m3u") null else remote.password,
            username = remote.username,
            password = remote.password,
            includeVod = remote.includeVod,
            includeInternational = remote.includeInternational,
            email = remote.email,
            homeZip = remote.zip,
            homeCountry = home?.country ?: "us",
            homeLat = home?.lat ?: 0.0,
            homeLon = home?.lon ?: 0.0
        )
        app.playlistStore.saveSession(playlist)
        val lineup = app.playlistStore.lineup(playlist.id)
        app.playlistStore.saveLineup(
            playlist.id,
            lineup.copy(
                includeInternational = remote.includeInternational,
                revision = remote.revision.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        )
        playlist
    }

    private data class HomeZip(val lat: Double, val lon: Double, val country: String)

    private fun lookupZip(zip: String): HomeZip? {
        if (zip.length != 5) return null
        val request = Request.Builder().url("https://api.zippopotam.us/us/$zip").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                val places = json.optJSONArray("places") ?: return null
                if (places.length() == 0) return null
                val place = places.getJSONObject(0)
                HomeZip(
                    place.optString("latitude").toDoubleOrNull() ?: return null,
                    place.optString("longitude").toDoubleOrNull() ?: return null,
                    "us"
                )
            }
        }.getOrNull()
    }
}
