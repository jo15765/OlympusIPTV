package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.ScrobblerIds
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Lightweight Simkl / Trakt scrobble. Only one provider can be active (mutex in PlaylistStore).
 */
object Scrobbler {
    const val NONE = ScrobblerIds.NONE
    const val SIMKL = ScrobblerIds.SIMKL
    const val TRAKT = ScrobblerIds.TRAKT

    private val http get() = HttpClients.scrobble
    private val json = "application/json; charset=utf-8".toMediaType()

    fun start(
        provider: String,
        token: String,
        clientId: String,
        channel: Channel
    ) {
        if (provider == NONE || token.isBlank()) return
        val title = channel.name.trim()
        if (title.isBlank()) return
        when (provider) {
            SIMKL -> simkl(token, clientId, title, channel, stop = false)
            TRAKT -> trakt(token, clientId, title, channel, stop = false)
        }
    }

    fun stop(
        provider: String,
        token: String,
        clientId: String,
        channel: Channel
    ) {
        if (provider == NONE || token.isBlank()) return
        val title = channel.name.trim()
        if (title.isBlank()) return
        when (provider) {
            SIMKL -> simkl(token, clientId, title, channel, stop = true)
            TRAKT -> trakt(token, clientId, title, channel, stop = true)
        }
    }

    private fun simkl(token: String, clientId: String, title: String, channel: Channel, stop: Boolean) {
        val movie = channel.kind == ContentKind.VOD && channel.seriesId.isNullOrBlank()
        val payload = JSONObject()
        val item = JSONObject().put("title", title)
        if (movie) payload.put("movie", item) else payload.put("show", item)
        val path = if (stop) "https://api.simkl.com/sync/history/remove" else "https://api.simkl.com/sync/history"
        post(path, payload.toString(), mapOf(
            "Authorization" to "Bearer $token",
            "simkl-api-key" to clientId.ifBlank { token }
        ))
    }

    private fun trakt(token: String, clientId: String, title: String, channel: Channel, stop: Boolean) {
        val movie = channel.kind == ContentKind.VOD && channel.seriesId.isNullOrBlank()
        val payload = JSONObject()
        val item = JSONObject().put("title", title)
        val list = org.json.JSONArray().put(JSONObject().put(if (movie) "movie" else "show", item))
        payload.put(if (movie) "movies" else "shows", list)
        val path = if (stop) {
            "https://api.trakt.tv/scrobble/stop"
        } else {
            "https://api.trakt.tv/scrobble/start"
        }
        post(path, payload.toString(), mapOf(
            "Authorization" to "Bearer $token",
            "trakt-api-version" to "2",
            "trakt-api-key" to clientId.ifBlank { token }
        ))
    }

    private fun post(url: String, body: String, headers: Map<String, String>) {
        val builder = Request.Builder().url(url).post(body.toRequestBody(json))
        headers.forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }
        runCatching { http.newCall(builder.build()).execute().close() }
    }
}
