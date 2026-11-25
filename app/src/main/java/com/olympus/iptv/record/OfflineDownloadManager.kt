package com.olympus.iptv.record

import android.content.Context
import com.olympus.iptv.data.OfflineDownload
import com.olympus.iptv.data.ProFeatureStore
import com.olympus.iptv.playlist.HttpClients
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import okhttp3.Request

/**
 * Lightweight offline VOD/episode downloader for mobile & tablets.
 * Uses 256 KB buffered writes, 250 MB free-space guard, and single-worker queue to keep UI silky-smooth.
 */
object OfflineDownloadManager {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "olympus-offline-dl").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun directory(context: Context): File {
        val app = context.applicationContext
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        val dir = File(base, "downloads")
        dir.mkdirs()
        return dir
    }

    fun file(context: Context, fileName: String): File = File(directory(context), fileName)

    fun usedBytes(context: Context): Long =
        directory(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun enqueue(context: Context, title: String, channelId: String, streamUrl: String): OfflineDownload {
        val app = context.applicationContext
        val id = UUID.randomUUID().toString().take(12)
        val ext = when {
            streamUrl.contains(".mkv", ignoreCase = true) -> ".mkv"
            streamUrl.contains(".mp4", ignoreCase = true) -> ".mp4"
            else -> ".ts"
        }
        val safeTitle = title.replace(Regex("[^A-Za-z0-9._ -]"), "").trim().take(72).ifBlank { "video" }
        val fileName = "$safeTitle-$id$ext"
        val item = OfflineDownload(
            id = id,
            title = title.ifBlank { safeTitle },
            channelId = channelId,
            streamUrl = streamUrl,
            fileName = fileName,
            status = OfflineDownload.STATUS_QUEUED,
            savedAt = System.currentTimeMillis()
        )
        ProFeatureStore.update(app) { st -> st.copy(downloads = listOf(item) + st.downloads) }
        worker.execute { runDownload(app, item) }
        return item
    }

    fun delete(context: Context, id: String) {
        val app = context.applicationContext
        cancelled.add(id)
        val existing = ProFeatureStore.load(app).downloads.firstOrNull { it.id == id }
        if (existing != null && existing.fileName.isNotBlank()) {
            runCatching { file(app, existing.fileName).delete() }
        }
        ProFeatureStore.update(app) { st -> st.copy(downloads = st.downloads.filterNot { it.id == id }) }
    }

    private fun runDownload(app: Context, item: OfflineDownload) {
        if (cancelled.remove(item.id)) return
        val dir = directory(app)
        if (dir.usableSpace in 1L until FileSink.MIN_START_FREE_BYTES) {
            mark(app, item.id) { it.copy(status = OfflineDownload.STATUS_FAILED, error = "Low device storage (< 250 MB free)") }
            return
        }
        mark(app, item.id) { it.copy(status = OfflineDownload.STATUS_DOWNLOADING, error = null) }
        val outFile = file(app, item.fileName)
        runCatching {
            val req = Request.Builder().url(item.streamUrl).build()
            HttpClients.xtream.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("Empty response")
                val total = body.contentLength().coerceAtLeast(0L)
                var written = 0L
                var nextUpdate = 2L * 1024L * 1024L
                BufferedOutputStream(FileOutputStream(outFile, false), 256 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    while (!cancelled.contains(item.id)) {
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read > 0) {
                            out.write(buf, 0, read)
                            written += read
                            if (written >= nextUpdate) {
                                nextUpdate = written + 4L * 1024L * 1024L
                                if (dir.usableSpace in 1L until FileSink.HARD_STOP_FREE_BYTES) {
                                    error("Low device storage")
                                }
                                mark(app, item.id) { it.copy(bytes = written, totalBytes = total) }
                            }
                        }
                    }
                    out.flush()
                }
                if (cancelled.remove(item.id)) {
                    runCatching { outFile.delete() }
                    return
                }
                mark(app, item.id) {
                    it.copy(
                        status = OfflineDownload.STATUS_DONE,
                        bytes = outFile.length(),
                        totalBytes = total.coerceAtLeast(outFile.length()),
                        error = null
                    )
                }
            }
        }.onFailure { err ->
            runCatching { outFile.delete() }
            if (!cancelled.remove(item.id)) {
                mark(app, item.id) {
                    it.copy(
                        status = OfflineDownload.STATUS_FAILED,
                        error = err.message?.take(120) ?: "Download failed"
                    )
                }
            }
        }
    }

    private fun mark(app: Context, id: String, transform: (OfflineDownload) -> OfflineDownload) {
        ProFeatureStore.update(app) { st ->
            st.copy(downloads = st.downloads.map { if (it.id == id) transform(it) else it })
        }
    }
}
