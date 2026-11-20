package com.olympus.iptv.ui.browse

/** Picks the two programs to show under a group title. Holds no views. */
internal object GroupNow {
    fun line(ids: List<String>, titleOf: (String) -> String?): String {
        val titles = ArrayList<String>(2)
        for (id in ids) {
            val title = titleOf(id)?.takeIf { it.isNotBlank() } ?: continue
            if (title in titles) continue
            titles.add(title)
            if (titles.size == 2) break
        }
        if (titles.isEmpty()) return ""
        return "Now: " + titles.joinToString(" • ")
    }
}
