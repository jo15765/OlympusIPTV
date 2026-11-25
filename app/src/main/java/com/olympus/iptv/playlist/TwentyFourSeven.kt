package com.olympus.iptv.playlist

import com.olympus.iptv.data.NameImages
import java.util.Locale

object TwentyFourSeven {
    const val GROUP_ID = "__247__"
    const val GROUP_NAME = "24/7 Channels"

    private val tokenRegex = Regex("""(?<!\d)24\s*[/\-xX]\s*7(?!\d)""")
    private val bracketedTokenRegex = Regex("""[\[\(\{]\s*24\s*[/\-xX]\s*7\s*[\]\)\}]""")
    private val leadingBracketRegex = Regex("""^(?:\[[^\]]*\]|\([^\)]*\)|\{[^\}]*\})\s*[:|\-–—/•★*·]*\s*""")
    private val leadingCountryOrTagRegex = Regex(
        """^(?:USA|US|U\.S\.A?\.?|UK|U\.K\.?|CA|CAN|AU|AUS|NZ|IE|EN|ENG|VIP|FHD|UHD|HD|SD|4K|RAW|HEVC|H\.?265|60FPS|LIVE)\s*(?:[:|\-–—/•★*·]+\s*|\s+)(?=\S)""",
        RegexOption.IGNORE_CASE
    )
    private val leadingNoiseRegex = Regex("""^[\s:|\-–—/•★*·.,_]+""")
    private val trailingNoiseRegex = Regex("""[\s:|\-–—/•★*·]+$""")
    private val multiSpaceRegex = Regex("""\s{2,}""")

    const val SQL_MATCH =
        "(name LIKE '%24/7%' OR name LIKE '%24-7%' OR name LIKE '%24x7%' OR name LIKE '%24X7%' " +
            "OR IFNULL(group_title, '') LIKE '%24/7%' OR IFNULL(group_title, '') LIKE '%24-7%' " +
            "OR IFNULL(group_title, '') LIKE '%24x7%' OR IFNULL(group_title, '') LIKE '%24X7%')"

    fun is247(text: String?): Boolean = !text.isNullOrBlank() && tokenRegex.containsMatchIn(text)

    fun is247(name: String?, groupTitle: String?): Boolean = is247(name) || is247(groupTitle)

    fun sortKey(rawName: String): String {
        var s = NameImages.strip(rawName).trim()
        if (s.isEmpty()) return rawName.trim().lowercase(Locale.US)

        s = bracketedTokenRegex.replace(s, " ")
        s = tokenRegex.replace(s, " ")

        repeat(4) {
            val before = s
            s = leadingNoiseRegex.replace(s, "")
            s = leadingBracketRegex.replace(s, "")
            s = leadingNoiseRegex.replace(s, "")
            val stripped = leadingCountryOrTagRegex.replace(s, "")
            if (stripped.isNotBlank()) s = stripped
            s = leadingNoiseRegex.replace(s, "")
            if (s == before) return@repeat
        }

        s = trailingNoiseRegex.replace(s, "")
        s = multiSpaceRegex.replace(s, " ").trim()
        return (if (s.isNotBlank()) s else rawName.trim()).lowercase(Locale.US)
    }
}
