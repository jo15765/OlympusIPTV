package com.olympus.iptv.playlist

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CatalogCache(context: Context) {
    private val dir = File(context.filesDir, "catalogs").apply { mkdirs() }
    private val gson = Gson()

    fun read(playlistId: String, includeVod: Boolean): LoadedPlaylist? {
        val file = file(playlistId, includeVod)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            GZIPInputStream(file.inputStream(), 65536).use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    gson.fromJson(reader, LoadedPlaylist::class.java)
                }
            }
        }.getOrNull()?.takeIf { it.liveChannels.isNotEmpty() || it.vodChannels.isNotEmpty() }
    }

    fun write(playlistId: String, includeVod: Boolean, loaded: LoadedPlaylist) {
        val file = file(playlistId, includeVod)
        val tmp = File(dir, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream(), 65536).use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                gson.toJson(loaded, writer)
            }
        }
        if (file.exists() && !file.delete()) return
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun readHashes(playlistId: String, bucket: String): Map<String, Int> {
        val file = hashFile(playlistId, bucket)
        if (!file.isFile) return emptyMap()
        val map = HashMap<String, Int>()
        file.forEachLine { line ->
            val split = line.indexOf('\t')
            if (split <= 0) return@forEachLine
            val hash = line.substring(split + 1).toIntOrNull() ?: return@forEachLine
            map[line.substring(0, split)] = hash
        }
        return map
    }

    fun writeHashes(playlistId: String, bucket: String, hashes: Map<String, Int>) {
        val file = hashFile(playlistId, bucket)
        val tmp = File(dir, file.name + ".tmp")
        tmp.bufferedWriter().use { writer ->
            hashes.forEach { (id, hash) ->
                writer.append(id)
                writer.append('\t')
                writer.append(hash.toString())
                writer.append('\n')
            }
        }
        if (file.exists() && !file.delete()) return
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun hashFile(playlistId: String, bucket: String): File {
        val safe = playlistId.filter { it.isLetterOrDigit() || it == '-' }
        val bucketSafe = bucket.filter { it.isLetterOrDigit() }
        return File(dir, "$safe-$bucketSafe.hashes")
    }

    private fun file(playlistId: String, includeVod: Boolean): File {
        val safe = playlistId.filter { it.isLetterOrDigit() || it == '-' }
        return File(dir, "$safe-${if (includeVod) "vod" else "live"}.json.gz")
    }
}
