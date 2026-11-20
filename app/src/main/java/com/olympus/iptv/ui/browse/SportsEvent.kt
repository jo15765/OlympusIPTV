package com.olympus.iptv.ui.browse

import java.util.Locale

/** Live sports matchups in an EPG title, plus how long is left. */
object SportsEvent {
    fun isMatchup(title: String): Boolean {
        val hay = title.lowercase(Locale.US)
        if (MARKERS.any { it in hay }) return true
        return VERSUS.containsMatchIn(title)
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
    private val MARKERS = listOf(
        "nfl", "nba", "mlb", "nhl", "ufc", "mma", "premier league",
        "espn+", "espn plus", "dazn", "ufc "
    )
}
