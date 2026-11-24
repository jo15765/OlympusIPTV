package com.olympus.iptv.record

import java.io.Closeable
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request

interface RecordSink : Closeable {
    fun write(bytes: ByteArray, length: Int)
}

class StreamCapture(private val http: OkHttpClient) {
    fun capture(url: String, sink: RecordSink, untilMillis: Long, shouldStop: () -> Boolean) {
        if (url.contains(".m3u8", ignoreCase = true)) {
            captureHls(url, sink, untilMillis, shouldStop)
        } else {
            captureDirect(url, sink, untilMillis, shouldStop)
        }
    }

    private fun captureDirect(url: String, sink: RecordSink, untilMillis: Long, shouldStop: () -> Boolean) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Stream refused")
            val input = response.body?.byteStream() ?: error("Empty stream")
            val buffer = ByteArray(64 * 1024)
            while (System.currentTimeMillis() < untilMillis && !shouldStop()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) sink.write(buffer, read)
            }
        }
    }

    private fun captureHls(url: String, sink: RecordSink, untilMillis: Long, shouldStop: () -> Boolean) {
        var playlist = url
        val seen = LinkedHashSet<String>()
        while (System.currentTimeMillis() < untilMillis && !shouldStop()) {
            val text = httpText(playlist)
            if (text.contains("#EXT-X-STREAM-INF")) {
                playlist = bestVariant(playlist, text)
                continue
            }
            for (segment in segments(playlist, text)) {
                if (!seen.add(segment)) continue
                if (seen.size > 500) seen.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
                append(segment, sink)
                if (System.currentTimeMillis() >= untilMillis || shouldStop()) return
            }
            if (text.contains("#EXT-X-ENDLIST")) return
            Thread.sleep(1500)
        }
    }

    private fun append(url: String, sink: RecordSink) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val input = response.body?.byteStream() ?: return
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) sink.write(buffer, read)
            }
        }
    }

    private fun httpText(url: String): String {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Playlist refused")
            return response.body?.string().orEmpty()
        }
    }

    private fun bestVariant(playlistUrl: String, text: String): String {
        var bestBandwidth = -1
        var best: String? = null
        var pending = -1
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pending = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (pending >= 0 && line.isNotEmpty() && !line.startsWith("#")) {
                if (pending >= bestBandwidth) {
                    bestBandwidth = pending
                    best = resolve(playlistUrl, line)
                }
                pending = -1
            }
        }
        return best ?: playlistUrl
    }

    private fun segments(playlistUrl: String, text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolve(playlistUrl, it) }
            .toList()
    }

    private fun resolve(base: String, ref: String): String {
        return if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else URI(base).resolve(ref).toString()
    }
}
