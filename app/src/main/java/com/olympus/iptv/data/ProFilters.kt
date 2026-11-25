package com.olympus.iptv.data

import android.content.Context

object ProFilters {
    private val scorePattern = Regex("""\b\d{1,3}\s*[-:]\s*\d{1,3}\b""")
    private val adultMarkers = listOf("adult", "xxx", "18+", "21+", "nsfw", "erotic", "porn", "mature")
    private val qualitySuffix = Regex("""\b(uhd|4k|fhd|hd|sd|hevc|h265|60fps|raw|vip|backup|bak|alt|usa|us|uk|ca)\b""", RegexOption.IGNORE_CASE)
    private val nonAlnum = Regex("""[^a-z0-9]+""")

    fun maskSportsSpoilers(text: String, enabled: Boolean): String {
        if (!enabled || text.isEmpty()) return text
        if (!scorePattern.containsMatchIn(text)) return text
        return scorePattern.replace(text, "[Score Hidden]")
    }

    fun isHiddenForActiveProfile(context: Context, groupName: String?): Boolean {
        val g = groupName?.trim().orEmpty()
        if (g.isEmpty()) return false
        val state = ProFeatureStore.load(context)
        val profile = state.profiles.firstOrNull { it.id == state.activeProfileId } ?: return false
        if (profile.hiddenGroups.any { it.equals(g, ignoreCase = true) }) return true
        if (profile.kidsMode) {
            val lower = g.lowercase()
            if (adultMarkers.any { lower.contains(it) }) return true
        }
        return false
    }

    fun normalizeChannelKey(name: String): String {
        val cleaned = qualitySuffix.replace(name.lowercase(), " ")
        return nonAlnum.replace(cleaned, "").trim()
    }
}
