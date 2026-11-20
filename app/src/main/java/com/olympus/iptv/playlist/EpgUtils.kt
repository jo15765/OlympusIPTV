package com.olympus.iptv.playlist

import android.util.Base64
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.data.EpgProgram
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EpgUtils {
    private val base64Pattern = Regex("^[A-Za-z0-9+/]+=*$")
    private val xmltvPattern = Regex("""^(\d{14})\s*([+-]\d{4})?$""")
    private val xmltvFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
    private val sqlFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    fun now(): Long = System.currentTimeMillis()

    fun formatClock(millis: Long): String = formatLocal(millis, "h:mm a")

    fun formatWhen(millis: Long): String = formatLocal(millis, "EEE, d MMM yyyy | h:mm a")

    private val localFormats = object : ThreadLocal<HashMap<String, SimpleDateFormat>>() {
        override fun initialValue(): HashMap<String, SimpleDateFormat> = HashMap()
    }

    private fun formatLocal(millis: Long, pattern: String): String {
        val map = localFormats.get() ?: HashMap()
        val format = map.getOrPut(pattern) {
            SimpleDateFormat(pattern, Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }
        }
        return format.format(Date(millis))
    }

    fun decodeTitle(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return ""
        if (!base64Pattern.matches(t)) return t
        return try {
            String(Base64.decode(t, Base64.DEFAULT), StandardCharsets.UTF_8).ifBlank { t }
        } catch (_: Exception) {
            t
        }
    }

    fun parseTimeToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return try {
            if (trimmed.all { it.isDigit() }) {
                trimmed.toLong().let { if (it < 1_000_000_000_000L) it * 1000 else it }
            } else {
                val xmltv = xmltvPattern.matchEntire(trimmed)
                if (xmltv != null) {
                    val zone = xmltv.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "+0000"
                    xmltvFormat.get()!!.parse("${xmltv.groupValues[1]} $zone")?.time
                } else {
                    sqlFormat.get()!!.parse(trimmed)?.time
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun nowNext(programs: List<EpgProgram>, now: Long = System.currentTimeMillis()): EpgNowNext {
        val sorted = programs.sortedBy { it.startMillis }
        val current = sorted.firstOrNull { it.contains(now) }
        val upcoming = sorted.firstOrNull { it.startMillis > now }
        return EpgNowNext(now = current, next = upcoming)
    }
}
