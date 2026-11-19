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
            GZIPInputStream(file.inputStream()).use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    gson.fromJson(reader, LoadedPlaylist::class.java)
                }
            }
        }.getOrNull()?.takeIf { it.liveChannels.isNotEmpty() || it.vodChannels.isNotEmpty() }
    }

    fun write(playlistId: String, includeVod: Boolean, loaded: LoadedPlaylist) {
        val file = file(playlistId, includeVod)
        val tmp = File(dir, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).use { output ->
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

    private fun file(playlistId: String, includeVod: Boolean): File {
        val safe = playlistId.filter { it.isLetterOrDigit() || it == '-' }
        return File(dir, "$safe-${if (includeVod) "vod" else "live"}.json.gz")
    }
}
