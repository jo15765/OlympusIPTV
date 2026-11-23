package com.olympus.iptv.playlist

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.olympus.iptv.data.Channel
import org.json.JSONObject
import java.io.File

/** User-agent and referer parsed from M3U #EXTVLCOPT lines. Kept beside the catalog, not in it. */
object StreamHeaders {
    data class Pair(val userAgent: String?, val referer: String?)

    private const val FILE = "stream-headers.json"
    private val memory = HashMap<String, Pair>()

    fun remember(context: Context, playlistId: String, channels: List<Channel>) {
        val useful = channels.filter { !it.userAgent.isNullOrBlank() || !it.httpReferer.isNullOrBlank() }
        if (useful.isEmpty()) return
        synchronized(memory) {
            useful.forEach { channel ->
                memory[key(playlistId, channel.id)] = Pair(channel.userAgent, channel.httpReferer)
            }
        }
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE)
            val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val bucket = root.optJSONObject(playlistId) ?: JSONObject()
            useful.forEach { channel ->
                val row = JSONObject()
                channel.userAgent?.let { row.put("ua", it) }
                channel.httpReferer?.let { row.put("ref", it) }
                bucket.put(channel.id, row)
            }
            root.put(playlistId, bucket)
            file.writeText(root.toString())
        }
    }

    fun lookup(context: Context, playlistId: String?, channel: Channel?): Pair {
        if (channel == null) return Pair(null, null)
        if (!channel.userAgent.isNullOrBlank() || !channel.httpReferer.isNullOrBlank()) {
            return Pair(channel.userAgent, channel.httpReferer)
        }
        if (playlistId.isNullOrBlank()) return Pair(null, null)
        val cached = synchronized(memory) { memory[key(playlistId, channel.id)] }
        if (cached != null) return cached
        val loaded = runCatching {
            val file = File(context.applicationContext.filesDir, FILE)
            if (!file.exists()) return@runCatching Pair(null, null)
            val row = JSONObject(file.readText()).optJSONObject(playlistId)?.optJSONObject(channel.id)
                ?: return@runCatching Pair(null, null)
            Pair(row.optString("ua").ifBlank { null }, row.optString("ref").ifBlank { null })
        }.getOrNull() ?: Pair(null, null)
        synchronized(memory) { memory[key(playlistId, channel.id)] = loaded }
        return loaded
    }

    fun httpFactory(
        context: Context,
        playlistId: () -> String?,
        channel: () -> Channel?
    ): DataSource.Factory {
        return DataSource.Factory {
            val pair = lookup(context, playlistId(), channel())
            val factory = DefaultHttpDataSource.Factory()
                .setUserAgent(pair.userAgent?.ifBlank { null } ?: "Olympus-IPTV")
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
            val referer = pair.referer
            if (!referer.isNullOrBlank()) {
                factory.setDefaultRequestProperties(mapOf("Referer" to referer))
            }
            factory.createDataSource()
        }
    }

    private fun key(playlistId: String, channelId: String) = "$playlistId\n$channelId"
}
