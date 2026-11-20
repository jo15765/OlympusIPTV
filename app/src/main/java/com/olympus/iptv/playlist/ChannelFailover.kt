package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel

/** Picks a sibling feed when the current HD stream has failed repeatedly. */
object ChannelFailover {
    fun stem(name: String): String =
        name.lowercase()
            .replace(Regex("""\[.*?]|[({].*?[)}]"""), " ")
            .replace(Regex("""\b(fhd|uhd|hd|sd|4k|backup|feed)\b"""), " ")
            .replace(Regex("""\d+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun alternate(current: Channel, siblings: List<Channel>): Channel? {
        val key = stem(current.name)
        if (key.length < 3) return null
        return siblings.firstOrNull { other ->
            other.id != current.id &&
                other.streamUrl.isNotBlank() &&
                stem(other.name) == key
        }
    }
}
