package com.olympus.iptv.playlist

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.olympus.iptv.BuildConfig
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.Titles
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object Subtitles {
    data class Query(val show: String, val season: Int, val episode: Int)

    data class Hit(val source: String, val id: String, val label: String)

    data class Saved(val file: File, val mime: String)

    private const val OPEN = "OpenSubtitles"
    private const val TV = "TVsubtitles"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val showLink = Regex("""href="(?:/)?tvshow-(\d+)-\d+\.html"[^>]*>([^<]+)""", RegexOption.IGNORE_CASE)
    private val episodeLink = Regex("""(\d{1,2})x(\d{1,3})[\s\S]{0,400}?episode-(\d+)\.html""", RegexOption.IGNORE_CASE)
    private val subtitleLink = Regex("""subtitle-(\d+)\.html""", RegexOption.IGNORE_CASE)
    private val flag = Regex("""images/flags/([a-z]{2})\.""", RegexOption.IGNORE_CASE)
    private val fileLink = Regex("""href="([^"]+\.(?:srt|zip|vtt))"""", RegexOption.IGNORE_CASE)
    private val marks = Regex("[^a-z0-9]+")
    @Volatile private var showIds: Map<String, Int>? = null

    fun query(showName: String, channel: Channel): Query? {
        val season = channel.season ?: code(channel.name)?.first ?: return null
        val episode = channel.number ?: code(channel.name)?.second ?: return null
        if (season <= 0 || episode <= 0) return null
        val show = Titles.clean(showName).trim()
        if (show.length < 2) return null
        return Query(show, season, episode)
    }

    fun openSearch(query: Query): List<Hit> {
        val key = BuildConfig.OPENSUBTITLES_API_KEY.trim()
        if (key.isEmpty()) return emptyList()
        return runCatching { openHits(key, query) }.getOrDefault(emptyList())
    }

    fun tvSearch(filesDir: File, query: Query): List<Hit> {
        return runCatching { tvHits(filesDir, query) }.getOrDefault(emptyList())
    }

    fun download(filesDir: File, hit: Hit): Saved? {
        val folder = File(filesDir, "subtitles")
        if (!folder.exists()) folder.mkdirs()
        val ready = arrayOf("srt", "vtt").firstNotNullOfOrNull { ext ->
            File(folder, "${hit.source}-${hit.id}.$ext").takeIf { it.isFile && it.length() > 16 }
        }
        if (ready != null) {
            return Saved(ready, if (ready.extension == "vtt") VTT else SRT)
        }
        val raw = when (hit.source) {
            OPEN -> openFile(hit.id)
            TV -> tvFile(hit.id)
            else -> null
        } ?: return null
        val bytes = textBytes(raw) ?: return null
        val vtt = bytes.copyOfRange(0, minOf(12, bytes.size)).toString(Charsets.US_ASCII).startsWith("WEBVTT")
        val file = File(folder, "${hit.source}-${hit.id}.${if (vtt) "vtt" else "srt"}")
        file.writeBytes(bytes)
        return Saved(file, if (vtt) VTT else SRT)
    }

    private fun openHits(key: String, query: Query): List<Hit> {
        val url = "https://api.opensubtitles.com/api/v1/subtitles?query=${encode(query.show.lowercase())}" +
            "&season_number=${query.season}&episode_number=${query.episode}&languages=en&type=episode"
        val request = Request.Builder()
            .url(url)
            .header("Api-Key", key)
            .header("User-Agent", "Olympus-IPTV v${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body ?: return emptyList()
            return JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                readOpenHits(reader)
            }
        }
    }

    private fun readOpenHits(reader: JsonReader): List<Hit> {
        val hits = ArrayList<Hit>()
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return hits
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "data" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                while (reader.hasNext() && hits.size < 8 && reader.peek() == JsonToken.BEGIN_OBJECT) {
                    readOpenItem(reader)?.let { hits.add(it) }
                }
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return hits
    }

    private fun readOpenItem(reader: JsonReader): Hit? {
        var label = ""
        var fileId = ""
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "attributes" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "release" -> label = jsonText(reader)
                        "files" -> fileId = readFileId(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        if (fileId.isBlank()) return null
        val name = label.ifBlank { "English" }
        return Hit(OPEN, fileId, "$OPEN · $name")
    }

    private fun jsonText(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NULL -> {
            reader.nextNull()
            ""
        }
        else -> {
            reader.skipValue()
            ""
        }
    }

    private fun readFileId(reader: JsonReader): String {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return ""
        }
        var id = ""
        reader.beginArray()
        while (reader.hasNext() && reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "file_id") {
                    id = when (reader.peek()) {
                        JsonToken.NUMBER -> reader.nextDouble().toLong().toString()
                        JsonToken.STRING -> reader.nextString().orEmpty()
                        else -> {
                            reader.skipValue()
                            ""
                        }
                    }
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            if (id.isNotBlank()) break
        }
        while (reader.hasNext()) reader.skipValue()
        reader.endArray()
        return id
    }

    private fun openFile(fileId: String): ByteArray? {
        val key = BuildConfig.OPENSUBTITLES_API_KEY.trim()
        if (key.isEmpty() || fileId.toLongOrNull() == null) return null
        val request = Request.Builder()
            .url("https://api.opensubtitles.com/api/v1/download")
            .header("Api-Key", key)
            .header("User-Agent", "Olympus-IPTV v${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post("""{"file_id":$fileId}""".toRequestBody(JSON))
            .build()
        val link = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            Regex(""""link"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?: return null
        }
        if (!link.startsWith("https://")) return null
        return http.newCall(Request.Builder().url(link).get().build()).execute().use { response ->
            if (!response.isSuccessful) null else limited(response)
        }
    }

    private fun tvHits(filesDir: File, query: Query): List<Hit> {
        val showId = showId(filesDir, query.show) ?: return emptyList()
        val season = getText("https://www.tvsubtitles.net/tvshow-$showId-${query.season}.html") ?: return emptyList()
        val episodeId = episodeLink.findAll(season).firstOrNull { match ->
            match.groupValues[1].toIntOrNull() == query.season &&
                match.groupValues[2].toIntOrNull() == query.episode
        }?.groupValues?.get(3) ?: return emptyList()
        val page = getText("https://www.tvsubtitles.net/episode-$episodeId.html") ?: return emptyList()
        val hits = ArrayList<Hit>()
        for (match in subtitleLink.findAll(page)) {
            if (hits.size == 8) break
            val id = match.groupValues[1]
            val start = (match.range.first - 500).coerceAtLeast(0)
            val window = page.substring(start, match.range.last + 1)
            val language = flag.findAll(window).lastOrNull()?.groupValues?.get(1)?.lowercase().orEmpty()
            if (language != "en") continue
            val release = window.replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(80)
                .ifBlank { "English" }
            hits.add(Hit(TV, id, "$TV · $release"))
        }
        return hits
    }

    private fun showId(filesDir: File, show: String): Int? {
        val wanted = key(show)
        if (wanted.isBlank()) return null
        val map = shows(filesDir)
        map[wanted]?.let { return it }
        var bestName = ""
        var bestId = 0
        for ((name, id) in map) {
            val close = (name.startsWith("$wanted ") && name.length - wanted.length <= 12) ||
                (wanted.startsWith("$name ") && wanted.length - name.length <= 12)
            if (!close) continue
            if (bestName.isEmpty() || kotlin.math.abs(name.length - wanted.length) < kotlin.math.abs(bestName.length - wanted.length)) {
                bestName = name
                bestId = id
            }
        }
        return bestId.takeIf { it > 0 }
    }

    private fun shows(filesDir: File): Map<String, Int> {
        showIds?.let { return it }
        val file = File(filesDir, "subtitle-shows.tsv")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < DAY_MS) {
            val cached = readShows(file.readText())
            if (cached.isNotEmpty()) {
                showIds = cached
                return cached
            }
        }
        val page = getText("https://www.tvsubtitles.net/tvshows.html") ?: return emptyMap()
        val map = LinkedHashMap<String, Int>()
        for (match in showLink.findAll(page)) {
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val name = key(match.groupValues[2])
            if (name.length >= 2) map.putIfAbsent(name, id)
        }
        if (map.isEmpty()) return emptyMap()
        file.writeText(map.entries.joinToString("\n") { "${it.value}\t${it.key}" })
        showIds = map
        return map
    }

    private fun readShows(text: String): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val id = line.substring(0, tab).toIntOrNull() ?: continue
            val name = line.substring(tab + 1)
            if (name.isNotBlank()) map.putIfAbsent(name, id)
        }
        return map
    }

    private fun tvFile(id: String): ByteArray? {
        val bytes = getBytes("https://www.tvsubtitles.net/download-$id.html") ?: return null
        unpack(bytes)?.let { return it }
        val html = bytes.toString(Charsets.ISO_8859_1)
        val href = fileLink.find(html)?.groupValues?.get(1) ?: return null
        val url = when {
            href.startsWith("https://") -> href
            href.startsWith("/") -> "https://www.tvsubtitles.net$href"
            else -> "https://www.tvsubtitles.net/$href"
        }
        return getBytes(url)
    }

    private fun getText(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Olympus-IPTV v${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else limited(response)
        }
    }

    private fun limited(response: okhttp3.Response): ByteArray {
        val body = response.body ?: return ByteArray(0)
        body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total < 3_000_000) {
                val read = input.read(buf)
                if (read < 0) break
                val room = 3_000_000 - total
                val count = minOf(read, room)
                out.write(buf, 0, count)
                total += count
            }
            return out.toByteArray()
        }
    }

    private fun unpack(raw: ByteArray): ByteArray? {
        if (raw.size >= 2 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && (name.endsWith(".srt") || name.endsWith(".vtt"))) {
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (total < 2_000_000) {
                            val read = zip.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            total += read
                        }
                        return textBytes(out.toByteArray())
                    }
                    entry = zip.nextEntry
                }
            }
            return null
        }
        return textBytes(raw)
    }

    private fun textBytes(raw: ByteArray): ByteArray? {
        val sample = raw.copyOfRange(0, minOf(800, raw.size)).toString(Charsets.ISO_8859_1)
        val caption = sample.contains("-->") || sample.startsWith("WEBVTT")
        if (!caption) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
            raw
        } catch (_: CharacterCodingException) {
            String(raw, Charsets.ISO_8859_1).toByteArray(Charsets.UTF_8)
        }
    }

    private fun code(name: String): Pair<Int, Int>? {
        val match = Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b").find(name) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    private fun key(name: String): String = marks.replace(name.lowercase(), " ").trim()

    private fun encode(text: String): String = java.net.URLEncoder.encode(text, Charsets.UTF_8.name())

    private val JSON = "application/json".toMediaType()
    const val SRT = "application/x-subrip"
    const val VTT = "text/vtt"
}
