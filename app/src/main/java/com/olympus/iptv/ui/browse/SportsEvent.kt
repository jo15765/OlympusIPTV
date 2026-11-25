package com.olympus.iptv.ui.browse

import java.util.Locale

object SportsEvent {
    fun isMatchup(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        if (LEAGUE_WORDS.containsMatchIn(trimmed)) return true
        return VERSUS.containsMatchIn(trimmed)
    }

    fun minutesLeft(now: Long, start: Long, end: Long): Int? {
        if (now < start || now >= end || end <= start) return null
        val left = ((end - now) + 59_999L) / 60_000L
        return left.toInt().coerceAtLeast(1)
    }

    fun badge(title: String, now: Long, start: Long, end: Long): String? {
        if (!isMatchup(title)) return null
        val left = minutesLeft(now, start, end) ?: return "LIVE EVENT"
        return "LIVE EVENT · ${left}m left"
    }

    private val VERSUS = Regex("""(?i)\bvs\.?\b|@""")
    private val LEAGUE_WORDS = Regex(
        """(?i)\b(?:nfl|nba|mlb|nhl|ufc|mma|dazn|premier\s+league|champions\s+league|europa\s+league|bundesliga|la\s+liga|serie\s+a|ligue\s+1|mls|ncaa[bfw]?|f1|formula\s+1|nascar|pga|atp|wta|espn\+|espn\s+plus)\b"""
    )
}
