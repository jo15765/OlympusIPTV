package com.olympus.iptv.playlist

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object EpisodeCache {
    private data class EpisodeRow(
        val id: String,
        val name: String,
        val streamUrl: String,
        val logoUrl: String? = null,
        val number: Int? = null,
        val seriesId: String? = null,
        val containerExtension: String? = null,
        val season: Int? = null,
        val blurb: String? = null,
        val aired: String? = null,
        val runtime: String? = null
    ) {
        fun toChannel(): Channel = Channel(
            id = id,
            name = name,
            streamUrl = streamUrl,
            logoUrl = logoUrl,
            kind = ContentKind.VOD,
            containerExtension = containerExtension,
            number = number,
            seriesId = seriesId,
            season = season,
            blurb = blurb,
            aired = aired,
            runtime = runtime
        )

        companion object {
            fun from(channel: Channel): EpisodeRow = EpisodeRow(
                id = channel.id,
                name = channel.name,
                streamUrl = channel.streamUrl,
                logoUrl = channel.logoUrl,
                number = channel.number,
                seriesId = channel.seriesId,
                containerExtension = channel.containerExtension,
                season = channel.season,
                blurb = channel.blurb,
                aired = channel.aired,
                runtime = channel.runtime
            )
        }
    }

    private val gson = Gson()
    private val listType = object : TypeToken<List<EpisodeRow>>() {}.type
    private val lock = ReentrantLock()
    private val memory = object : LinkedHashMap<String, List<Channel>>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Channel>>?) = size > 48
    }

    fun contains(filesDir: File, playlistId: String, seriesId: String): Boolean {
        if (playlistId.isBlank() || seriesId.isBlank()) return false
        val key = fileName(playlistId, seriesId)
        if (lock.withLock { memory.containsKey(key) }) return true
        return File(folder(filesDir), key).exists()
    }

    fun peek(filesDir: File, playlistId: String, seriesId: String): List<Channel>? {
        if (playlistId.isBlank() || seriesId.isBlank()) return null
        val key = fileName(playlistId, seriesId)
        lock.withLock { memory[key] }?.let { return it }
        val file = File(folder(filesDir), key)
        if (!file.exists()) return null
        val rows = runCatching { gson.fromJson<List<EpisodeRow>>(file.readText(), listType) }.getOrNull()
        if (rows.isNullOrEmpty()) return null
        val loaded = rows.map { it.toChannel() }
        lock.withLock { memory[key] = loaded }
        return loaded
    }

    fun store(filesDir: File, playlistId: String, seriesId: String, episodes: List<Channel>) {
        if (playlistId.isBlank() || seriesId.isBlank() || episodes.isEmpty()) return
        val key = fileName(playlistId, seriesId)
        lock.withLock { memory[key] = episodes }
        val dir = folder(filesDir)
        if (!dir.exists()) dir.mkdirs()
        val rows = episodes.map { EpisodeRow.from(it) }
        runCatching { File(dir, key).writeText(gson.toJson(rows)) }
    }

    fun clear(filesDir: File) {
        lock.withLock { memory.clear() }
        runCatching { folder(filesDir).deleteRecursively() }
    }

    private fun folder(filesDir: File) = File(filesDir, "episode-cache")

    private fun fileName(playlistId: String, seriesId: String): String {
        fun safe(value: String): String {
            val cleaned = buildString(value.length.coerceAtMost(48)) {
                for (ch in value) {
                    if (length >= 48) break
                    append(if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_')
                }
            }
            return cleaned.ifBlank { "x" }
        }
        return "${safe(playlistId)}_${safe(seriesId)}.json"
    }
}
