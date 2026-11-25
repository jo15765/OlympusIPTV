package com.olympus.iptv.record

import android.content.Context
import android.net.Uri
import com.olympus.iptv.data.Recording
import com.olympus.iptv.data.SmbSettings
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object RecordingFiles {
    @Volatile
    private var usedBytesCache: Long? = null

    fun directory(context: Context): File {
        val app = context.applicationContext
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        val dir = File(base, "recordings")
        dir.mkdirs()
        return dir
    }

    fun file(context: Context, name: String): File = File(directory(context), name)

    fun usedBytes(context: Context, force: Boolean = false): Long {
        if (!force) usedBytesCache?.let { return it }
        val sum = directory(context).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        usedBytesCache = sum
        return sum
    }

    fun invalidateSize() {
        usedBytesCache = null
    }

    fun safeName(channel: String, title: String, start: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(start))
        val clean = "$channel $title"
            .replace(Regex("[^A-Za-z0-9._ -]"), "")
            .trim()
            .take(80)
            .ifBlank { "recording" }
        return "$stamp $clean.ts"
    }
}

class FileSink(private val file: File) : RecordSink {
    private val stream = java.io.BufferedOutputStream(FileOutputStream(file, false), 256 * 1024)
    private var nextSpaceCheck = SPACE_CHECK_INTERVAL_BYTES
    override var bytesWritten: Long = 0L
        private set

    override fun write(bytes: ByteArray, length: Int) {
        stream.write(bytes, 0, length)
        bytesWritten += length
        if (bytesWritten >= nextSpaceCheck) {
            nextSpaceCheck = bytesWritten + SPACE_CHECK_INTERVAL_BYTES
            val free = file.parentFile?.usableSpace ?: file.usableSpace
            if (free in 1L until HARD_STOP_FREE_BYTES) {
                throw java.io.IOException("Low device storage")
            }
        }
    }

    override fun close() {
        runCatching { stream.flush() }
        stream.close()
    }

    companion object {
        const val MIN_START_FREE_BYTES = 250L * 1024L * 1024L
        const val HARD_STOP_FREE_BYTES = 100L * 1024L * 1024L
        private const val SPACE_CHECK_INTERVAL_BYTES = 16L * 1024L * 1024L
    }
}

object SmbPlayback {
    private val open = AtomicBoolean(false)
    @Volatile private var socket: ServerSocket? = null

    fun start(settings: SmbSettings, fileName: String): String {
        stop()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        socket = server
        open.set(true)
        thread(name = "olympus-iptv-smb-play", isDaemon = true) {
            while (open.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { serve(client, settings, fileName) }
                    runCatching { client.close() }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/recording.ts"
    }

    fun stop() {
        open.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(client: java.net.Socket, settings: SmbSettings, fileName: String) {
        client.soTimeout = 8000
        val reader = client.getInputStream().bufferedReader()
        reader.readLine()
        var range = 0L
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                range = Regex("bytes=(\\d+)-").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            }
        }
        SmbFiles.withFile(settings, fileName, read = true) { file, size ->
            val start = range.coerceIn(0L, size)
            val length = size - start
            val status = if (start > 0) "206 Partial Content" else "200 OK"
            val head = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: video/mp2t\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                if (start > 0) append("Content-Range: bytes ").append(start).append("-").append(size - 1).append("/").append(size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val output = client.getOutputStream()
            output.write(head.toByteArray())
            var offset = start
            val buffer = ByteArray(64 * 1024)
            while (offset < size) {
                val want = minOf(buffer.size.toLong(), size - offset).toInt()
                val read = file.read(buffer, offset, 0, want)
                if (read <= 0) break
                output.write(buffer, 0, read)
                offset += read
            }
            output.flush()
        }
    }
}

object RecordingPlayback {
    fun uri(context: Context, recording: Recording, settings: SmbSettings): Uri? {
        val name = recording.fileName ?: return null
        return if (recording.destination == Recording.SMB) {
            Uri.parse(SmbPlayback.start(settings, name))
        } else {
            val file = RecordingFiles.file(context, name)
            if (!file.exists() || file.length() <= 0L) null else Uri.fromFile(file)
        }
    }
}
