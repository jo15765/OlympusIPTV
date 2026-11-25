package com.olympus.iptv.data

object StreamQuality {
    fun badge(name: String): String? {
        val hay = name.lowercase()
        val picture = when {
            "4k" in hay || "uhd" in hay -> "4K"
            "fhd" in hay || "1080" in hay -> "FHD"
            Regex("""\bhd\b""").containsMatchIn(hay) -> "HD"
            else -> null
        }
        val rate = if (Regex("""\b60\s*fps\b""").containsMatchIn(hay)) "60fps" else null
        return listOfNotNull(picture, rate).joinToString(" ").ifBlank { null }
    }
}
