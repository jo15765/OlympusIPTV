package com.olympus.iptv.data

object Favorites {
    const val LIVE = "live"
    const val VOD = "vod"
    const val SERIES = "series"

    fun key(channel: Channel): String {
        val kind = when {
            !channel.seriesId.isNullOrBlank() -> SERIES
            channel.kind == ContentKind.LIVE -> LIVE
            else -> VOD
        }
        return "$kind:${channel.id}"
    }

    fun keyFor(channel: Channel, section: String): String = "$section:${channel.id}"

    fun inSection(stored: String, section: String): Boolean {
        val colon = stored.indexOf(':')
        if (colon < 0) return section == LIVE
        return stored.startsWith("$section:")
    }

    fun matches(channel: Channel, ids: Set<String>): Boolean {
        if (key(channel) in ids) return true
        if (!channel.seriesId.isNullOrBlank() && keyFor(channel, SERIES) in ids) return true
        if (channel.seriesId.isNullOrBlank() && channel.kind != ContentKind.LIVE && keyFor(channel, VOD) in ids) return true
        return channel.kind == ContentKind.LIVE &&
            channel.seriesId.isNullOrBlank() &&
            (keyFor(channel, LIVE) in ids || channel.id in ids)
    }

    fun storedKey(channel: Channel, order: List<String>): String {
        val namespaced = key(channel)
        if (namespaced in order) return namespaced
        if (channel.id in order) return channel.id
        return namespaced
    }

    fun ordered(order: List<String>, byId: Map<String, Channel>, ids: Set<String>): List<Channel> {
        if (order.isEmpty() || byId.isEmpty() || ids.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Channel>(order.size)
        for (key in order) {
            val channel = byId[key.substringAfter(':')] ?: continue
            if (!seen.add(channel.id)) continue
            if (matches(channel, ids)) out.add(channel)
        }
        return out
    }
}
