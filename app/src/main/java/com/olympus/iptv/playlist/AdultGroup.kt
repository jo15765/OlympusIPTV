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

    fun isAdultGroupId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return id.equals(GROUP_ID, ignoreCase = true) || isAdultGroup(id)
    }

    fun preserveLocalParentalOverrides(
        local: com.olympus.iptv.data.LineupConfig,
        incoming: com.olympus.iptv.data.LineupConfig
    ): com.olympus.iptv.data.LineupConfig {
        val localAdultHidden = local.hiddenGroups.orEmpty().filter { isAdultGroupId(it) }
        val localPins = local.groupPins.orEmpty().filter { (k, v) -> k.isNotBlank() && v.isNotBlank() }
        if (localAdultHidden.isEmpty() && localPins.isEmpty()) return incoming

        val mergedHidden = LinkedHashSet<String>(incoming.hiddenGroups.orEmpty())
        mergedHidden.addAll(localAdultHidden)

        val mergedPins = LinkedHashMap<String, String>(incoming.groupPins.orEmpty())
        for ((groupKey, pin) in localPins) {
            mergedPins[groupKey] = pin
        }

        return incoming.copy(
            hiddenGroups = mergedHidden.toList(),
            groupPins = if (mergedPins.isEmpty()) incoming.groupPins else mergedPins
        )
    }
}
