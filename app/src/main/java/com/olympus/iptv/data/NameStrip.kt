package com.olympus.iptv.data

/**
 * Removes comma-separated prefixes and suffixes from a channel name.
 * Each entry is matched as typed, including spaces. HD still does not eat FHD.
 */
object NameStrip {
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
    private var cachedPlan: Plan? = null

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

    fun shown(name: String): String {
        if (name.isBlank()) return name
        shown[name]?.let { return it }
        val clean = apply(name, sanitizeStreams, emptyList())
        if (shown.size > 8_000) shown.clear()
        shown[name] = clean
        return clean
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
            if (next.isBlank() || next == current) return current
            current = next
        }
        return current
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
        val base = when {
            !override.isNullOrBlank() -> override
            channel.kind == ContentKind.VOD || !channel.seriesId.isNullOrBlank() -> Titles.clean(channel.name)
            else -> channel.name
        }
        return NameStrip.apply(base, config.namePrefixes.orEmpty(), config.nameSuffixes.orEmpty())
    }
}
