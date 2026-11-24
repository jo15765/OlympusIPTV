package com.olympus.iptv.data

import com.olympus.iptv.playlist.DummyEpg

/**
 * High-speed O(1) indexOf-based stream & EPG cleaner + event window evaluator.
 * Eliminates regex backtracking across 40,000+ channels on Android TV.
 */
object NameStrip {
    data class StreamStatus(
        val cleanChannel: String,
        val airingEvent: String?,
        val isEventSlot: Boolean,
        val eventStartMs: Long,
        val eventEndMs: Long
    ) {
        fun isVisibleAt(now: Long): Boolean {
            if (!isEventSlot) return true
            if (eventStartMs == 0L) return false
            if (eventStartMs < 0L) return true
            return now >= (eventStartMs - PRE_EVENT_WINDOW_MS) && now < eventEndMs
        }
    }

    const val PRE_EVENT_WINDOW_MS = 30L * 60L * 1000L
    private const val DEFAULT_EVENT_DURATION_MS = 3L * 60L * 60L * 1000L

    val sanitizeStreams = listOf(
        "USA", "US:", "HD", "GA | Atlanta |", "Big10+", "BLKET:", "DAZN US", "Dirtvision",
        "Disney+ Events", "ESPN+", "Fanatiz", "Flo Football", "Flo Racing", "Flo Sports",
        "Fox One", "MAX USA", "MLS", "MLS NEXT PRO", "The MW +", "The MW+", "NBA",
        "NCAA Baseball", "NCAAB", "NCAAW B", "NCAAF", "NFHS Network", "NFL Teams:",
        "NHL", "NHL:", "NJCAA Women's Basketball", "NWSL", "Paramount+", "Peacock",
        "Plex:", "SEC+ / Acc extra", "USA Soccer", "Stirr:", "Tubi:", "VIX PREMIUM DEPORTES",
        "WNBA", "Xumo:"
    )

    fun merge(current: List<String>?, extra: List<String>): List<String> {
        val out = ArrayList(current.orEmpty())
        for (token in extra) {
            if (out.none { it.equals(token, ignoreCase = true) }) out.add(token)
        }
        return out
    }

    private const val MARKS = ":|-–—[]()"
    private val spaces = Regex("\\s{2,}")
    private val shown = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val statusCache = java.util.concurrent.ConcurrentHashMap<String, StreamStatus>()
    private var cachedPlan: Plan? = null

    fun clearCaches() {
        shown.clear()
        statusCache.clear()
    }

    private data class Plan(
        val prefixes: List<String>,
        val suffixes: List<String>,
        val marks: Set<Char>,
        val phrases: List<String>,
        val prefixWords: List<String>,
        val suffixWords: List<String>
    )

    fun prepare(names: Collection<String>) {
        for (name in names) shown(name)
    }

    /** Single-pass character loop stripping Unicode superscripts (ᴴᴰ, ᶠᴴᴰ, ᴸᶦᵛᵉ, ᴺᵉʷ, ⁴ᴷ) and leaked M3U attributes. */
    fun sanitizeRaw(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (s.contains("group-title=") || s.contains("tvg-")) {
            val comma = s.lastIndexOf(',')
            if (comma >= 0 && comma + 1 < s.length) s = s.substring(comma + 1).trim()
        }
        var hasSup = false
        for (i in s.indices) {
            val code = s[i].code
            if (code in 0x02B0..0x02FF || code in 0x1D2C..0x1DBF || code in 0x2070..0x209F) {
                hasSup = true
                break
            }
        }
        if (!hasSup) return s
        val out = StringBuilder(s.length)
        for (i in s.indices) {
            val ch = s[i]
            val code = ch.code
            if (code !in 0x02B0..0x02FF && code !in 0x1D2C..0x1DBF && code !in 0x2070..0x209F) {
                out.append(ch)
            }
        }
        return out.toString().trim()
    }

    fun isDummyEpgId(epgChannelId: String?): Boolean {
        if (epgChannelId.isNullOrBlank()) return false
        return epgChannelId.contains("bossdummy", ignoreCase = true) ||
            epgChannelId.contains("noevent", ignoreCase = true) ||
            epgChannelId.contains("GlobalNewsDummy", ignoreCase = true)
    }

    fun isPlaceholderEvent(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val t = text.trim()
        if (t.equals("NO EVENT", ignoreCase = true) ||
            t.equals("No EVENT Today", ignoreCase = true) ||
            t.equals("No Game Today", ignoreCase = true) ||
            t.equals("Signing Off", ignoreCase = true) ||
            t.equals("Channel Off Air", ignoreCase = true) ||
            t.equals("Ended", ignoreCase = true) ||
            t.equals("Streaming on this service", ignoreCase = true) ||
            t.equals("Check Fixtures", ignoreCase = true) ||
            t.equals("Off Air", ignoreCase = true) ||
            t.equals("TBA", ignoreCase = true) ||
            t.equals("TBD", ignoreCase = true) ||
            t.equals("No live event right now", ignoreCase = true) ||
            t.equals("No Broadcasting", ignoreCase = true)
        ) return true
        if (t.endsWith("No Broadcasting", ignoreCase = true)) return true
        if (t.startsWith("Sendepause", ignoreCase = true)) return true
        if (t.startsWith("Live in ", ignoreCase = true)) return true
        if (t.startsWith("Upcoming (", ignoreCase = true)) return true
        if (t.startsWith("Match ", ignoreCase = true) && t.length <= 10) return true
        if (t.startsWith("PSC Match ", ignoreCase = true) && t.length <= 14) return true
        val strippedSlot = t.trim(' ', '-', ':', '|', '–', '—')
        if (strippedSlot.isEmpty() || strippedSlot.all { it.isDigit() }) return true
        return false
    }

    /**
     * Fast indexOf-first stream inspector.
     * Separates channel identity from inline event title & schedule window in O(1).
     */
    fun inspect(
        rawChannel: String,
        groupTitle: String? = null,
        epgChannelId: String? = null,
        nowTitle: String? = null,
        nowStartMs: Long = 0L,
        nowEndMs: Long = 0L
    ): StreamStatus {
        val ch = sanitizeRaw(rawChannel)
        val cleanEpg = if (isDummyEpgId(epgChannelId)) "" else sanitizeRaw(nowTitle.orEmpty())

        // Fast-path 1: Local US affiliate (`NE | Lincoln | FOX KFXL`)
        val firstPipe = ch.indexOf('|')
        if (firstPipe in 1..4) {
            val secondPipe = ch.indexOf('|', firstPipe + 1)
            if (secondPipe > firstPipe + 1) {
                val state = ch.substring(0, firstPipe).trim()
                val city = ch.substring(firstPipe + 1, secondPipe).trim()
                val station = ch.substring(secondPipe + 1).trim()
                if (state.length == 2 && state[0].isUpperCase() && state[1].isUpperCase() && station.isNotEmpty()) {
                    val cleanCh = "$station ($city, $state)"
                    val airing = cleanEpg.takeIf { !isPlaceholderEvent(it) }?.let { stripChannelPrefixFromEpg(it, station) }
                    return StreamStatus(cleanCh, airing, isEventSlot = false, eventStartMs = 0L, eventEndMs = 0L)
                }
            }
        }

        // Fast-path 2: Single colon or pipe (`left : right` or `left | right`)
        val colonIdx = ch.indexOf(':')
        val sepIdx = when {
            colonIdx > 0 && (firstPipe < 0 || colonIdx < firstPipe) -> colonIdx
            firstPipe > 0 -> firstPipe
            else -> -1
        }
        if (sepIdx > 0) {
            val left = ch.substring(0, sepIdx).trim()
            val rightRaw = ch.substring(sepIdx + 1).trim()
            val endsWithDigits = left.isNotEmpty() && left.last().isDigit() && left.any { it.isLetter() }
            if (endsWithDigits) {
                // Numbered event slot: `FOX ONE 01: College Basketball ... @ 16 Nov 05:00 AM ET` or `MAX NL 03 :`
                val atIdx = rightRaw.lastIndexOf('@')
                val inlineEvent = (if (atIdx > 0) rightRaw.substring(0, atIdx) else rightRaw)
                    .trim(' ', '-', ':', '|', '–', '—')
                val parsedStart = DummyEpg.parseEventStartMillis(rightRaw) ?: nowStartMs
                val parsedEnd = when {
                    nowEndMs > parsedStart -> nowEndMs
                    parsedStart > 0L -> parsedStart + DEFAULT_EVENT_DURATION_MS
                    else -> 0L
                }
                if (!isPlaceholderEvent(cleanEpg)) {
                    val event = stripChannelPrefixFromEpg(cleanEpg, left)
                    val start = if (parsedStart > 0L) parsedStart else -1L
                    return StreamStatus(left, event, isEventSlot = true, eventStartMs = start, eventEndMs = parsedEnd)
                }
                if (isPlaceholderEvent(inlineEvent)) {
                    return StreamStatus(left, null, isEventSlot = true, eventStartMs = 0L, eventEndMs = 0L)
                }
                val cleanEvent = stripChannelPrefixFromEpg(inlineEvent, left)
                val start = if (parsedStart > 0L) parsedStart else -1L
                return StreamStatus(left, cleanEvent, isEventSlot = true, eventStartMs = start, eventEndMs = parsedEnd)
            } else if (isPlaceholderEvent(rightRaw)) {
                // Blank or slot-number-only tail (`NFL  | 01 -`, `PRIME-CA | No Event`, `DK | No Event`)
                return StreamStatus(left, null, isEventSlot = true, eventStartMs = 0L, eventEndMs = 0L)
            } else {
                // Country/Platform/Team prefix (`US: CNN`, `Amazon US: Wipeout`, `NHL: Anaheim Ducks`, `24/7: Bar Rescue`)
                val cleanCh = rightRaw
                val explicitOffAir = cleanEpg.isNotEmpty() && isPlaceholderEvent(cleanEpg)
                val airing = if (isPlaceholderEvent(cleanEpg)) {
                    null
                } else {
                    stripChannelPrefixFromEpg(cleanEpg, cleanCh)
                }
                return StreamStatus(
                    cleanChannel = cleanCh,
                    airingEvent = airing,
                    isEventSlot = explicitOffAir,
                    eventStartMs = if (explicitOffAir) 0L else nowStartMs,
                    eventEndMs = if (explicitOffAir) 0L else nowEndMs
                )
            }
        }

        // Fast-path 3: Standard channel (`4UV`, `HBO Boxing`, `NFL ARIZONA CARDINALS`)
        val explicitOffAir = cleanEpg.isNotEmpty() && isPlaceholderEvent(cleanEpg)
        val airing = if (isPlaceholderEvent(cleanEpg)) null else stripChannelPrefixFromEpg(cleanEpg, ch)
        return StreamStatus(
            cleanChannel = ch,
            airingEvent = airing,
            isEventSlot = explicitOffAir,
            eventStartMs = if (explicitOffAir) 0L else nowStartMs,
            eventEndMs = if (explicitOffAir) 0L else nowEndMs
        )
    }

    /** Strips duplicate channel name prefix from EPG (`World Poker Tour: Alpha8...` -> `Alpha8...`). */
    fun stripChannelPrefixFromEpg(epgTitle: String, cleanChannelName: String): String {
        val epg = epgTitle.trim()
        val ch = cleanChannelName.trim()
        if (epg.isEmpty() || ch.length < 3) return epg
        if (epg.length > ch.length + 2 && epg.regionMatches(0, ch, 0, ch.length, ignoreCase = true)) {
            val nextChar = epg[ch.length]
            if (!nextChar.isLetterOrDigit()) {
                val rest = epg.substring(ch.length).trimStart(' ', ':', '|', '-', '–', '—')
                if (rest.isNotBlank() && !rest.equals("Programming", ignoreCase = true) && !rest.equals("24/7", ignoreCase = true)) {
                    return rest
                }
            }
        }
        return epg
    }

    fun shown(name: String, groupTitle: String? = null): String {
        if (name.isBlank()) return name
        val cacheKey = if (groupTitle.isNullOrBlank()) name else "$groupTitle\u0000$name"
        shown[cacheKey]?.let { return it }
        val status = inspect(name, groupTitle)
        val base = if (status.isEventSlot && !status.airingEvent.isNullOrBlank()) {
            "${status.cleanChannel}: ${status.airingEvent}"
        } else {
            status.cleanChannel
        }
        val clean = apply(base, sanitizeStreams, emptyList())
            .trim(' ', ':', '|', '-', '–', '—')
            .ifBlank { base.trim(' ', ':', '|', '-', '–', '—') }
            .ifBlank { name.trim(' ', ':', '|', '-', '–', '—') }
        if (shown.size > 8_000) shown.clear()
        shown[cacheKey] = clean
        return clean
    }

    fun stripGroupAndNumber(name: String, groupTitle: String?): String {
        return shown(name, groupTitle)
    }

    fun tokens(raw: String): List<String> {
        val pieces = raw.split(',')
        return tokens(pieces.mapIndexed { index, part ->
            if (index > 0 && part.startsWith(" ")) part.drop(1) else part
        })
    }

    fun tokens(parts: List<String>?): List<String> {
        if (parts.isNullOrEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (part in parts) {
            if (part.isEmpty()) continue
            if (seen.any { it.equals(part, ignoreCase = true) }) continue
            seen.add(part)
        }
        return seen.toList()
    }

    fun apply(name: String, prefixes: List<String>, suffixes: List<String>): String {
        if (name.isBlank() || (prefixes.isEmpty() && suffixes.isEmpty())) return name
        val plan = plan(prefixes, suffixes)
        val marks = plan.marks
        val phrases = plan.phrases
        val prefixWords = plan.prefixWords
        val suffixWords = plan.suffixWords
        var current = name.trim()
        if (marks.isNotEmpty()) {
            val cleared = buildString(current.length) {
                for (ch in current) if (ch !in marks) append(ch)
            }.replace(spaces, " ").trim()
            if (cleared.isNotBlank()) current = cleared
        }
        if (phrases.isEmpty() && prefixWords.isEmpty() && suffixWords.isEmpty()) return current
        repeat(8) {
            var next = current
            for (token in phrases) next = cutPhrase(next, token)
            for (token in prefixWords) next = cutPrefix(next, token)
            for (token in suffixWords) next = cutSuffix(next, token)
            next = next.trim(' ', ':', '|', '-', '–', '—')
            if (next.isBlank() || next == current) return current.trim(' ', ':', '|', '-', '–', '—').ifBlank { current }
            current = next
        }
        return current.trim(' ', ':', '|', '-', '–', '—').ifBlank { current }
    }

    private fun plan(prefixes: List<String>, suffixes: List<String>): Plan {
        val current = cachedPlan
        if (current != null && current.prefixes == prefixes && current.suffixes == suffixes) return current
        val marks = LinkedHashSet<Char>()
        val phrases = ArrayList<String>()
        val prefixWords = ArrayList<String>()
        val suffixWords = ArrayList<String>()
        for (token in prefixes) sortToken(token, marks, phrases, prefixWords)
        for (token in suffixes) sortToken(token, marks, phrases, suffixWords)
        phrases.sortByDescending { it.length }
        prefixWords.sortByDescending { it.length }
        suffixWords.sortByDescending { it.length }
        return Plan(prefixes, suffixes, marks, phrases, prefixWords, suffixWords).also { cachedPlan = it }
    }

    private fun sortToken(
        token: String,
        marks: MutableSet<Char>,
        phrases: MutableList<String>,
        words: MutableList<String>
    ) {
        when {
            token.all { it in MARKS } -> token.forEach { marks.add(it) }
            token.any { !it.isLetterOrDigit() } -> phrases.add(token)
            else -> words.add(token)
        }
    }

    private fun cutPhrase(name: String, token: String): String {
        if (token.isEmpty() || name.length <= token.length) return name
        val needle = token.lowercase()
        val lower = name.lowercase()
        val startsWord = token.first().isLetterOrDigit()
        val endsWord = token.last().isLetterOrDigit()
        val out = StringBuilder()
        var index = 0
        var changed = false
        while (index < name.length) {
            val at = lower.indexOf(needle, index)
            if (at < 0) {
                out.append(name.substring(index))
                break
            }
            val before = if (at > 0) name[at - 1] else ' '
            val afterIndex = at + token.length
            val after = if (afterIndex < name.length) name[afterIndex] else ' '
            if ((startsWord && before.isLetterOrDigit()) || (endsWord && after.isLetterOrDigit())) {
                out.append(name.substring(index, at + 1))
                index = at + 1
                continue
            }
            out.append(name.substring(index, at))
            index = afterIndex
            changed = true
        }
        val result = out.toString()
        return if (changed && result.isNotBlank()) result else name
    }

    private fun cutPrefix(name: String, token: String): String {
        if (token.isEmpty() || name.length <= token.length) return name
        if (!name.regionMatches(0, token, 0, token.length, ignoreCase = true)) return name
        if (token.last().isLetterOrDigit() && name[token.length].isLetterOrDigit()) return name
        val rest = name.substring(token.length)
        return if (rest.isBlank()) name else rest
    }

    private fun cutSuffix(name: String, token: String): String {
        if (token.isEmpty() || name.length <= token.length) return name
        val start = name.length - token.length
        if (!name.regionMatches(start, token, 0, token.length, ignoreCase = true)) return name
        if (token.first().isLetterOrDigit() && name[start - 1].isLetterOrDigit()) return name
        val head = name.substring(0, start)
        return if (head.isBlank()) name else head
    }
}

object ChannelNames {
    fun present(channel: Channel, config: LineupConfig): String {
        val override = config.channelNames?.get(channel.id)
        if (!override.isNullOrBlank()) {
            return NameStrip.apply(override, config.namePrefixes.orEmpty(), config.nameSuffixes.orEmpty())
        }
        val base = when {
            channel.kind == ContentKind.VOD || !channel.seriesId.isNullOrBlank() -> Titles.clean(channel.name)
            config.autoCleanNames == false -> channel.name
            else -> NameStrip.shown(channel.name, channel.groupTitle)
        }
        return NameStrip.apply(base, config.namePrefixes.orEmpty(), config.nameSuffixes.orEmpty())
    }
}
