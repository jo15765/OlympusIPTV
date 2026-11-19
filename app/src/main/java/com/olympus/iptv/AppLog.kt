package com.olympus.iptv

import android.content.Context
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_BYTES = 400_000
    private const val ACTIVE = "olympus-iptv-log.txt"
    private val archivedName = Regex("olympus-iptv-log-[0-9A-Za-z-]+\\.txt")
    private val lock = Any()
    private var file: File? = null
    private var previous: Thread.UncaughtExceptionHandler? = null

    data class Entry(val name: String, val bytes: Long, val current: Boolean)

    fun install(context: Context) {
        file = File(context.filesDir, ACTIVE)
        if (previous == null) {
            previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { error("Crash", "Uncaught on ${thread.name}", error) }
                val oom = error is OutOfMemoryError ||
                    generateSequence(error.cause) { it.cause }.any { it is OutOfMemoryError }
                if (oom) {
                    runCatching {
                        val app = context.applicationContext as? OlympusIptvApp
                        app?.catalogDb?.releaseSearchExport()
                        app?.guideChannels = emptyList()
                        app?.guideSource = null
                        app?.warmPlayer?.release()
                        app?.warmPlayer = null
                        app?.warmChannelId = null
                        com.olympus.iptv.portal.DashboardHost.clearGuideCache()
                    }
                    runCatching { System.gc() }
                    return@setDefaultUncaughtExceptionHandler
                }
                if (thread == Looper.getMainLooper().thread) previous?.uncaughtException(thread, error)
            }
        }
        info("App", "Started")
    }

    fun info(tag: String, message: String) = write("INFO", tag, message, null)

    fun error(tag: String, message: String, error: Throwable? = null) = write("ERROR", tag, message, error)

    fun read(name: String? = null): ByteArray {
        synchronized(lock) {
            val current = named(name) ?: return ByteArray(0)
            if (!current.exists()) return ByteArray(0)
            return current.readBytes()
        }
    }

    fun list(): List<Entry> {
        val current = file ?: return emptyList()
        synchronized(lock) {
            val dir = current.parentFile ?: return emptyList()
            val found = dir.listFiles { candidate ->
                candidate.isFile && (candidate.name == ACTIVE || archivedName.matches(candidate.name))
            }?.toList().orEmpty()
            return found.sortedByDescending { it.lastModified() }.map {
                Entry(it.name, it.length(), it.name == ACTIVE)
            }
        }
    }

    fun downloadName(name: String?): String? {
        if (name.isNullOrBlank()) return ACTIVE
        if (name != ACTIVE && !archivedName.matches(name)) return null
        return name
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val current = file ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            append(stamp).append(' ').append(level).append(' ').append(tag).append(' ')
            append(redact(message))
            if (error != null) {
                append('\n')
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                append(redact(writer.toString()))
            }
            append('\n')
        }
        try {
            synchronized(lock) {
                rotateIfNeeded(current)
                FileOutputStream(current, true).use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun named(name: String?): File? {
        val current = file ?: return null
        val safe = downloadName(name) ?: return null
        return File(current.parentFile, safe)
    }

    private fun rotateIfNeeded(current: File) {
        if (!current.exists() || current.length() < MAX_BYTES) return
        val dir = current.parentFile ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var dest = File(dir, "olympus-iptv-log-$stamp.txt")
        var extra = 2
        while (dest.exists()) {
            dest = File(dir, "olympus-iptv-log-$stamp-$extra.txt")
            extra += 1
        }
        current.renameTo(dest)
    }

    private fun redact(text: String): String {
        return text
            .replace(Regex("(?i)(password|passwd|pwd|token|pass)=[^&\\s]+"), "$1=***")
            .replace(Regex("(?i)\"(password|passwd|xtreamPassword|token)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"***\"")
            .replace(Regex("(https?://[^\\s\"']+/)(live|movie|series)/[^/\\s]+/[^/\\s]+/"), "$1$2/***/***/")
    }
}
