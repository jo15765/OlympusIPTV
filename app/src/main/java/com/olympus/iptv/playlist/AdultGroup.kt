package com.olympus.iptv.playlist

object AdultGroup {
    const val GROUP_ID = "__adult__"
    const val GROUP_NAME = "Nutflix"

    private val adultRegex = Regex(
        """(?i)(?:^|[\s|:\-\[\(])(?:18\+|21\+|xxx|porn|erotic|nutflix|for\s+adults?|adult\s+time)(?:$|[\s|:\-\]\)])|🔞"""
    )

    const val SQL_MATCH =
        "(UPPER(IFNULL(group_title, '')) LIKE '%18+%' OR UPPER(IFNULL(group_title, '')) LIKE '%21+%' " +
            "OR UPPER(IFNULL(group_title, '')) LIKE '%XXX%' OR UPPER(IFNULL(group_title, '')) LIKE '%NUTFLIX%' " +
            "OR UPPER(IFNULL(group_title, '')) LIKE '%ADULT TIME%' OR UPPER(IFNULL(group_title, '')) LIKE '%FOR ADULTS%' " +
            "OR UPPER(IFNULL(group_title, '')) LIKE '%PORN%' OR UPPER(IFNULL(group_title, '')) LIKE '%EROTIC%' " +
            "OR IFNULL(group_title, '') LIKE '%🔞%')"

    fun isAdultGroup(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (text.contains("adult swim", ignoreCase = true)) return false
        return adultRegex.containsMatchIn(text)
    }
}
