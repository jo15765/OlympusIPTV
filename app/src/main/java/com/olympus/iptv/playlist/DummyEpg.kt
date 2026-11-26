package com.olympus.iptv.playlist

import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.Titles
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object DummyEpg {
    private const val EVENT_MS = 165L * 60 * 1000
    private const val UPCOMING_MS = 36L * 60 * 60 * 1000
    private const val ENDED_MS = 6L * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val isoDateTime = Regex(
        """\b(\d{4})-(\d{2})-(\d{2})[T\s]+(\d{1,2}):(\d{2})(?::\d{2})?\s*(AM|PM)?\s*([A-Za-z]{2,10})?""",
        RegexOption.IGNORE_CASE
    )
    private val atTime = Regex(
        """@\s*(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?""",
        RegexOption.IGNORE_CASE
    )
    private val atMonthDay = Regex(
        """@\s*([A-Za-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?""",
        RegexOption.IGNORE_CASE
    )
    private val atNumericDate = Regex(
        """@\s*(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?""",
        RegexOption.IGNORE_CASE
    )
    private val parenDot = Regex(
        """\(\s*(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val parenMonth = Regex(
        """\(\s*([A-Za-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val bareMonth = Regex(
        """\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?""",
        RegexOption.IGNORE_CASE
    )
    private val bareDayMonth = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)(?:,?\s+(\d{4}))?[\s,\-|]+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*([A-Za-z]{2,10})?""",
        RegexOption.IGNORE_CASE
    )
    private val bareNumeric = Regex(
        """\b(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?[\s,\-|]+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Za-z]{2,10})?""",
        RegexOption.IGNORE_CASE
    )
    private val timeOnly = Regex(
        """(?:@\s*|\b)(\d{1,2}):(\d{2})\s*(AM|PM)\s*(ET|EST|EDT|CT|CST|CDT|MT|MST|MDT|PT|PST|PDT|GMT|UTC|UK|BST|CET|CEST|LONDON)?\b""",
        RegexOption.IGNORE_CASE
    )
    private val indexPrefix = Regex("""^[A-Za-z][A-Za-z0-9 &'+\-]{0,24}\s+\d{1,4}\s*[:|\-–—]\s*""")
    private val leadingChannelNum = Regex("""^(?:(?:ch(?:annel)?\.?\s*)|#)?\d{1,4}\s*[:|\-–—.]\s*""", RegexOption.IGNORE_CASE)
    private val leadingHashNum = Regex("""^(?:(?:ch(?:annel)?\.?\s*)|#)\d{1,4}\s+""", RegexOption.IGNORE_CASE)
    private val trailingChannelNum = Regex("""\s*(?:\[(?:ch(?:annel)?\.?\s*)?#?\d{1,4}\]|\((?:ch(?:annel)?\.?\s*)?#?\d{1,4}\)|[|:\-–—]\s*(?:ch(?:annel)?\.?\s*)?#?\d{1,4}|\s+#\d{1,4})\s*$""", RegexOption.IGNORE_CASE)
    private val countryPrefix = Regex("""^[A-Z]{2}:\s+""")
    private val quality = Regex("""\s*\((?:4KDV|4K|FHD|HD|SD)\)\s*$""", RegexOption.IGNORE_CASE)
    private val shortParen = Regex("""\s+\([A-Z0-9][A-Z0-9 .]{0,12}\)\s*$""")
    private val spaces = Regex("""\s+""")

    private val months = mapOf(
        "jan" to Calendar.JANUARY, "january" to Calendar.JANUARY,
        "feb" to Calendar.FEBRUARY, "february" to Calendar.FEBRUARY,
        "mar" to Calendar.MARCH, "march" to Calendar.MARCH,
        "apr" to Calendar.APRIL, "april" to Calendar.APRIL,
        "may" to Calendar.MAY,
        "jun" to Calendar.JUNE, "june" to Calendar.JUNE,
        "jul" to Calendar.JULY, "july" to Calendar.JULY,
        "aug" to Calendar.AUGUST, "august" to Calendar.AUGUST,
        "sep" to Calendar.SEPTEMBER, "sept" to Calendar.SEPTEMBER, "september" to Calendar.SEPTEMBER,
        "oct" to Calendar.OCTOBER, "october" to Calendar.OCTOBER,
        "nov" to Calendar.NOVEMBER, "november" to Calendar.NOVEMBER,
        "dec" to Calendar.DECEMBER, "december" to Calendar.DECEMBER
    )

    fun programs(channelName: String, now: Long = System.currentTimeMillis()): List<EpgProgram> =
        fromParsed(parse(channelName, now), channelName, now)

    fun titled(channelName: String, now: Long = System.currentTimeMillis()): Pair<String, List<EpgProgram>> {
        val parsed = parse(channelName, now)
        return parsed.title to fromParsed(parsed, channelName, now)
    }

    private fun fromParsed(parsed: Parsed, rawChannel: String, now: Long): List<EpgProgram> {
        val title = parsed.title
        val start = parsed.startMillis
        if (start == null) {
            val day = startOfLocalDay(now)
            return listOf(
                EpgProgram(title, day, day + DAY_MS),
                EpgProgram(title, day + DAY_MS, day + 2 * DAY_MS)
            )
        }
        val durationMs = com.olympus.iptv.data.NameStrip.eventDurationMs(title, rawChannel)
        val end = start + durationMs
        val hardEnd = end + com.olympus.iptv.data.NameStrip.POST_EVENT_GRACE_MS
        val preWindow = com.olympus.iptv.data.NameStrip.PRE_EVENT_WINDOW_MS
        if (now in start until end) {
            return listOf(EpgProgram(title, start, end))
        }
        if (now in end until hardEnd) {
            return listOf(EpgProgram(title, start, hardEnd))
        }
        if (start > now && start - now <= preWindow) {
            return listOf(
                EpgProgram("Starting soon: $title", now, start),
                EpgProgram(title, start, end)
            )
        }
        val day = startOfLocalDay(now)
        return listOf(EpgProgram("No live event right now", day, day + DAY_MS))
    }

    private data class Parsed(val title: String, val startMillis: Long?)

    fun parseEventStartMillis(channelName: String, now: Long = System.currentTimeMillis()): Long? {
        val raw = channelName.trim()
        if (raw.isEmpty()) return null
        val lastAt = raw.lastIndexOf('@')
        val slice = if (lastAt >= 0) raw.substring(lastAt) else raw
        val timed = findTimed(slice) ?: findTimed(raw) ?: return null
        return matchStart(timed, now)
    }

    private fun findTimed(text: String): MatchResult? =
        isoDateTime.find(text)
            ?: atTime.find(text)
            ?: atMonthDay.find(text)
            ?: atNumericDate.find(text)
            ?: parenMonth.find(text)
            ?: parenDot.find(text)
            ?: bareMonth.find(text)
            ?: bareDayMonth.find(text)
            ?: bareNumeric.find(text)
            ?: timeOnly.find(text)

    private fun parse(channelName: String, now: Long): Parsed {
        val raw = channelName.trim()
        if (raw.isEmpty()) return Parsed("No event", null)
        val timed = findTimed(raw)
        val withoutTime = if (timed == null) raw else raw.removeRange(timed.range).trim()
        val title = cleanTitle(withoutTime).ifBlank { cleanTitle(raw) }.ifBlank { "No event" }
        val start = timed?.let { matchStart(it, now) }
        return Parsed(title, start)
    }

    fun stripChannelNumber(value: String): String {
        var name = value.trim()
        name = indexPrefix.replace(name, "")
        name = leadingChannelNum.replace(name, "")
        name = leadingHashNum.replace(name, "")
        name = trailingChannelNum.replace(name, "")
        return name.replace(spaces, " ").trim(' ', '-', ':', '|', '–', '—')
    }

    private fun cleanTitle(value: String): String {
        var name = Titles.clean(value)
        name = com.olympus.iptv.data.NameStrip.stripGroupAndNumber(name, null)
        name = stripChannelNumber(name)
        name = countryPrefix.replace(name, "")
        name = quality.replace(name, "")
        name = shortParen.replace(name, "")
        name = name.replace(spaces, " ").trim(' ', '-', ':', '|')
        if (name.equals("NO EVENT", ignoreCase = true)) return "No event"
        return name
    }

    private fun matchStart(match: MatchResult, now: Long): Long? {
        val groups = match.groupValues
        if (groups.size == 5) {
            val zoneId = zoneId(groups[4])
            val clock = Calendar.getInstance(zoneId).apply { timeInMillis = now }
            val hourNum = groups[1].toIntOrNull() ?: return null
            val minNum = groups[2].toIntOrNull() ?: return null
            clock.set(Calendar.HOUR_OF_DAY, to24(hourNum, groups[3]))
            clock.set(Calendar.MINUTE, minNum)
            clock.set(Calendar.SECOND, 0)
            clock.set(Calendar.MILLISECOND, 0)
            return clock.timeInMillis
        }
        val g1Num = groups[1].toIntOrNull()
        val g2Num = groups[2].toIntOrNull()
        val g3Num = groups[3].toIntOrNull()
        val min = groups[5].ifBlank { "00" }
        return when {
            g1Num != null && g1Num >= 2000 && g2Num != null && g3Num != null ->
                stamp(day = groups[3], monthNumber = groups[2], year = groups[1], hour = groups[4], minute = min, ampm = groups[6], zone = groups[7].ifBlank { "CET" }, now = now)
            g1Num != null && g2Num != null ->
                stamp(monthNumber = groups[1], day = groups[2], year = groups[3], hour = groups[4], minute = min, ampm = groups[6], zone = groups[7], now = now)
            g1Num != null ->
                stamp(day = groups[1], monthName = groups[2], year = groups[3], hour = groups[4], minute = min, ampm = groups[6], zone = groups[7], now = now)
            else ->
                stamp(day = groups[2], monthName = groups[1], year = groups[3], hour = groups[4], minute = min, ampm = groups[6], zone = groups[7], now = now)
        }
    }

    private fun stamp(
        day: String,
        monthName: String? = null,
        monthNumber: String? = null,
        year: String?,
        hour: String,
        minute: String,
        ampm: String?,
        zone: String?,
        now: Long
    ): Long? {
        val dayNum = day.toIntOrNull() ?: return null
        val month = monthName?.let { months[it.lowercase(Locale.US)] }
            ?: monthNumber?.toIntOrNull()?.minus(1)?.takeIf { it in Calendar.JANUARY..Calendar.DECEMBER }
            ?: return null
        val hourNum = hour.toIntOrNull() ?: return null
        val minuteNum = minute.toIntOrNull() ?: return null
        val zoneId = zoneId(zone)
        val clock = Calendar.getInstance(zoneId)
        val parsedYear = year?.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            ?: clock.get(Calendar.YEAR)
        clock.set(Calendar.SECOND, 0)
        clock.set(Calendar.MILLISECOND, 0)
        clock.set(parsedYear, month, dayNum, to24(hourNum, ampm), minuteNum)
        if (year.isNullOrBlank() && clock.timeInMillis < now - 200L * DAY_MS) {
            clock.add(Calendar.YEAR, 1)
        }
        return clock.timeInMillis
    }

    private fun to24(hour: Int, ampm: String?): Int {
        if (ampm.isNullOrBlank()) return hour.coerceIn(0, 23)
        val base = hour % 12
        return if (ampm.equals("PM", ignoreCase = true)) base + 12 else base
    }

    private fun zoneId(token: String?): TimeZone {
        val key = token?.trim()?.uppercase(Locale.US).orEmpty()
        val id = when (key) {
            "ET", "EST", "EDT", "TORONTO" -> "America/New_York"
            "CT", "CST", "CDT" -> "America/Chicago"
            "MT", "MST", "MDT" -> "America/Denver"
            "PT", "PST", "PDT" -> "America/Los_Angeles"
            "AST", "ADT" -> "America/Halifax"
            "UK", "BST", "WET", "WEST", "LONDON", "DUBLIN", "LISBON" -> "Europe/London"
            "CET", "CEST", "PARIS", "BERLIN", "MADRID", "ROME" -> "Europe/Berlin"
            "SYDNEY" -> "Australia/Sydney"
            "GMT", "UTC" -> "UTC"
            else -> return TimeZone.getDefault()
        }
        return TimeZone.getTimeZone(id)
    }

    private fun startOfLocalDay(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
