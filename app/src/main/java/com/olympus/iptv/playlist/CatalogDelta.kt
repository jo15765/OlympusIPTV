package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel

/** Decides which catalog rows actually need a SQLite write on refresh. */
object CatalogDelta {
    fun rowHash(channel: Channel): Int {
        var hash = channel.name.hashCode()
        hash = 31 * hash + channel.streamUrl.hashCode()
        hash = 31 * hash + (channel.groupTitle?.hashCode() ?: 0)
        hash = 31 * hash + (channel.logoUrl?.hashCode() ?: 0)
        hash = 31 * hash + (channel.categoryId?.hashCode() ?: 0)
        return hash
    }

    /**
     * First pass (no saved hashes) writes only ids the database does not have yet.
     * Later passes write a row only when its content hash changed.
     */
    fun writes(previous: Map<String, Int>, channels: List<Channel>, known: Set<String>): List<Channel> {
        if (previous.isEmpty()) return channels.filter { it.id !in known }
        return channels.filter { channel ->
            val prior = previous[channel.id]
            prior == null || prior != rowHash(channel)
        }
    }
}
