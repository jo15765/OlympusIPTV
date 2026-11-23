package com.olympus.iptv

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private const val PENDING_CRASH = "pending-crash.json"
    private val archivedName = Regex("olympus-iptv-log-[0-9A-Za-z-]+\\.txt")
    private val lock = Any()
    private var file: File? = null
    private var appContext: Context? = null
    private var previous: Thread.UncaughtExceptionHandler? = null

    data class Entry(val name: String, val bytes: Long, val current: Boolean)

    fun install(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, ACTIVE)
        flushPendingCrash(context.applicationContext)
        if (previous == null) {
            previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { recordCrashSync("Uncaught on ${thread.name}", error) }
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
                    if (level == "ERROR") output.fd.sync()
                }
            }
            if (level == "ERROR" && appContext != null) {
                uploadCloudError(appContext!!, tag, message, error)
            }
        } catch (_: Throwable) {
        }
    }

    private fun recordCrashSync(message: String, error: Throwable) {
        val ctx = appContext ?: return
        val safeMsg = redact(message)
        val safeStack = redact(
            StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        )
        // Write to local log with fsync, without launching an async upload that would be killed.
        val current = file
        if (current != null) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val body = "$stamp ERROR Crash $safeMsg\n$safeStack\n"
            runCatching {
                synchronized(lock) {
                    rotateIfNeeded(current)
                    FileOutputStream(current, true).use { output ->
                        output.write(body.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                }
            }
        }
        // Persist a pending crash payload so even if the process dies before HTTP completes, next launch uploads it.
        val pending = File(ctx.filesDir, PENDING_CRASH)
        val payload = com.google.gson.JsonObject().apply {
            addProperty("tag", "Crash")
            addProperty("message", safeMsg)
            addProperty("stack", safeStack)
        }
        runCatching {
            FileOutputStream(pending, false).use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }
        // Try a bounded synchronous upload before the process terminates.
        val app = ctx.applicationContext as? OlympusIptvApp ?: return
        val worker = Thread({
            runCatching {
                val prefs = runCatching { kotlinx.coroutines.runBlocking { app.playlistStore.currentPrefs() } }.getOrNull()
                val email = runCatching {
                    kotlinx.coroutines.runBlocking { app.playlistStore.session.first()?.email }
                }.getOrNull()
                val deviceId = runCatching {
                    kotlinx.coroutines.runBlocking { app.playlistStore.deviceId() }
                }.getOrDefault("__shared__")
                if (prefs != null || deviceId.isNotBlank()) {
                    val sent = com.olympus.iptv.playlist.LineupClient(app)
                        .uploadErrorLog(email, deviceId, "Crash", safeMsg, safeStack)
                    if (sent) runCatching { pending.delete() }
                }
            }
        }, "crash-upload")
        worker.isDaemon = true
        worker.start()
        runCatching { worker.join(1_500L) }
    }

    private fun flushPendingCrash(context: Context) {
        val app = context.applicationContext as? OlympusIptvApp ?: return
        val pending = File(app.filesDir, PENDING_CRASH)
        if (!pending.exists()) return
        app.appScope.launch(Dispatchers.IO) {
            val raw = runCatching { pending.readText(Charsets.UTF_8) }.getOrNull() ?: return@launch
            val obj = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: run {
                    runCatching { pending.delete() }
                    return@launch
                }
            val tag = obj.get("tag")?.asString ?: "Crash"
            val msg = obj.get("message")?.asString.orEmpty()
            val stack = obj.get("stack")?.asString
            val email = runCatching { app.playlistStore.session.first()?.email }.getOrNull()
            val deviceId = runCatching { app.playlistStore.deviceId() }.getOrDefault("__shared__")
            val sent = com.olympus.iptv.playlist.LineupClient(app).uploadErrorLog(email, deviceId, tag, msg, stack)
            if (sent) runCatching { pending.delete() }
        }
    }

    private fun uploadCloudError(context: Context, tag: String, message: String, error: Throwable?) {
        val app = context.applicationContext as? OlympusIptvApp ?: return
        val safeMsg = redact(message)
        val safeStack = if (error != null) {
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            redact(writer.toString())
        } else null
        app.appScope.launch(Dispatchers.IO) {
            val email = runCatching { app.playlistStore.session.first()?.email }.getOrNull()
            val deviceId = runCatching { app.playlistStore.deviceId() }.getOrDefault("__shared__")
            com.olympus.iptv.playlist.LineupClient(app).uploadErrorLog(email, deviceId, tag, safeMsg, safeStack)
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
