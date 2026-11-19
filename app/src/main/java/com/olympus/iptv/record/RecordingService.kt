package com.olympus.iptv.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.GuideSlot
import com.olympus.iptv.data.Recording
import com.olympus.iptv.data.RecordingBook
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.LineupClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var capture: Job? = null
    private val stopCapture = AtomicBoolean(false)
    @Volatile private var captureId: String? = null
    @Volatile private var lastSyncRevision = 0L
    @Volatile private var lastSyncAt = 0L
    private var wake: PowerManager.WakeLock? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground(getString(R.string.record_notification_idle))
        } catch (error: Throwable) {
            com.olympus.iptv.AppLog.error("Record", "Recording service did not start", error)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { watch() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture.set(true)
        capture?.cancel()
        loop?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private suspend fun watch() {
        while (true) {
            runCatching { tick() }
            delay(10_000)
        }
    }

    private suspend fun tick() {
        val app = application as OlympusIptvApp
        val playlist = app.playlistStore.session.first()
            ?: app.playlistStore.playlists.first().firstOrNull()
            ?: return
        val email = playlist.email?.trim().orEmpty()
        var book = app.playlistStore.recordingBook(playlist.id)
        val now = System.currentTimeMillis()
        val busy = book.itemsOrEmpty().any {
            it.status == Recording.SCHEDULED || it.status == Recording.RECORDING
        } || book.seriesOrEmpty().isNotEmpty() || book.requestsOrEmpty().isNotEmpty() || capture?.isActive == true
        val shouldSync = email.isNotBlank() && (
            busy ||
                book.revisionOrZero() != lastSyncRevision ||
                now - lastSyncAt > 120_000L
            )
        if (shouldSync) {
            val synced = LineupClient(app).syncRecordings(email, app.playlistStore.deviceId(), book)
            lastSyncAt = now
            lastSyncRevision = synced.revisionOrZero()
            if (synced.revisionOrZero() != book.revisionOrZero() || synced.itemsOrEmpty() != book.itemsOrEmpty()) {
                removeFiles(book, synced)
                book = synced
                app.playlistStore.saveRecordingBook(playlist.id, book)
            }
        }
        if (!busy && book.requestsOrEmpty().isEmpty()) return

        val active = book.itemsOrEmpty().firstOrNull { it.id == captureId }
        if (capture?.isActive == true && active?.status != Recording.RECORDING) stopCapture.set(true)

        var changed = false
        val items = book.itemsOrEmpty().map { item ->
            if (item.status == Recording.SCHEDULED && now > item.windowEnd()) {
                changed = true
                item.copy(status = Recording.MISSED)
            } else {
                item
            }
        }
        if (changed) book = book.copy(items = items, revision = now)

        if (book.requestsOrEmpty().isNotEmpty()) {
            book = fulfillGuide(app, playlist.id, book).also { if (it !== book) changed = true }
        }
        if (book.seriesOrEmpty().isNotEmpty()) {
            val picked = RecordPrompt.pickupSeries(app, playlist.id, book)
            if (picked !== book) {
                book = picked
                changed = true
            }
        }
        if (changed) {
            app.playlistStore.saveRecordingBook(playlist.id, book)
            if (email.isNotBlank()) {
                LineupClient(app).pushRecordings(email, app.playlistStore.deviceId(), book)
                lastSyncRevision = book.revisionOrZero()
                lastSyncAt = now
            }
        }

        if (capture?.isActive == true) return
        val due = book.itemsOrEmpty().firstOrNull {
            it.status == Recording.SCHEDULED && now >= it.windowStart() && now < it.windowEnd()
        } ?: return
        startCapture(app, playlist.id, email, due)
    }

    private suspend fun fulfillGuide(app: OlympusIptvApp, playlistId: String, book: RecordingBook): RecordingBook {
        val request = book.requestsOrEmpty().firstOrNull() ?: return book
        val playlist = app.playlistStore.playlists.first().firstOrNull { it.id == playlistId } ?: return book
        val channel = app.catalogDb.readChannel(playlistId, CatalogDb.LIVE, request)
        val slots = if (channel == null) emptyList() else {
            app.epgRepository.upcoming(playlist, channel).map {
                GuideSlot(it.title, it.startMillis, it.endMillis)
            }
        }
        val guide = book.guideOrEmpty().toMutableMap()
        guide[request] = slots
        return book.copy(
            guide = guide,
            guideRequests = book.requestsOrEmpty().filterNot { it == request },
            revision = System.currentTimeMillis()
        )
    }

    private fun startCapture(app: OlympusIptvApp, playlistId: String, email: String, item: Recording) {
        val id = item.id ?: return
        stopCapture.set(false)
        captureId = id
        capture = scope.launch {
            val lock = wakeLock()
            try {
                update(app, playlistId, email, id) { it.copy(status = Recording.RECORDING, error = null) }
                show(item.title ?: getString(R.string.record_notification))
                val channel = app.catalogDb.readChannel(playlistId, CatalogDb.LIVE, item.channelId.orEmpty())
                val url = channel?.streamUrl.orEmpty()
                if (url.isBlank()) error("No stream")
                val name = item.fileName ?: error("No file")
                val book = app.playlistStore.recordingBook(playlistId)
                val sink = if (item.destination == Recording.SMB) {
                    SmbFiles.sink(book.smbOrEmpty(), name)
                } else {
                    FileSink(RecordingFiles.file(this@RecordingService, name))
                }
                try {
                    StreamCapture(http).capture(url, sink, item.windowEnd()) { stopCapture.get() }
                } finally {
                    runCatching { sink.close() }
                    if (item.destination != Recording.SMB) RecordingFiles.invalidateSize()
                }
                val latest = app.playlistStore.recordingBook(playlistId).itemsOrEmpty().firstOrNull { it.id == id }
                if (latest?.status == Recording.STOPPED) return@launch
                val bytes = if (item.destination == Recording.SMB) 0L else RecordingFiles.file(this@RecordingService, name).length()
                var copied = false
                if (item.destination != Recording.SMB && email.isNotBlank() && bytes > 0L) {
                    copied = LineupClient(app).uploadRecording(
                        email,
                        app.playlistStore.deviceId(),
                        id,
                        RecordingFiles.file(this@RecordingService, name)
                    )
                }
                update(app, playlistId, email, id) {
                    it.copy(status = Recording.DONE, bytes = bytes, copied = copied, error = null)
                }
            } catch (error: Exception) {
                update(app, playlistId, email, id) {
                    if (it.status == Recording.STOPPED) it
                    else it.copy(status = Recording.FAILED, error = error.message?.take(120) ?: "Failed")
                }
            } finally {
                captureId = null
                if (lock.isHeld) lock.release()
                show(getString(R.string.record_notification_idle))
            }
        }
    }

    private suspend fun update(
        app: OlympusIptvApp,
        playlistId: String,
        email: String,
        id: String,
        change: (Recording) -> Recording
    ) {
        val book = app.playlistStore.recordingBook(playlistId)
        val next = book.copy(
            items = book.itemsOrEmpty().map { if (it.id == id) change(it) else it },
            revision = System.currentTimeMillis()
        )
        app.playlistStore.saveRecordingBook(playlistId, next)
        if (email.isNotBlank()) LineupClient(app).pushRecordings(email, app.playlistStore.deviceId(), next)
    }

    private fun removeFiles(previous: RecordingBook, next: RecordingBook) {
        val kept = next.itemsOrEmpty().mapNotNull { it.id }.toSet()
        for (item in previous.itemsOrEmpty()) {
            if (item.id in kept) continue
            val name = item.fileName ?: continue
            if (item.destination == Recording.SMB) {
                runCatching { SmbFiles.delete(previous.smbOrEmpty(), name) }
            } else {
                RecordingFiles.file(this, name).delete()
                RecordingFiles.invalidateSize()
            }
        }
    }

    private fun wakeLock(): PowerManager.WakeLock {
        val lock = wake ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Olympus-IPTV:record")
            .also { wake = it }
        if (!lock.isHeld) lock.acquire(6 * 60 * 60 * 1000L)
        return lock
    }

    private fun startInForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTICE, notification)
        }
    }

    private fun show(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTICE, notification(text))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.record), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.record))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "olympus-iptv-record"
        private const val NOTICE = 41

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }
    }
}
