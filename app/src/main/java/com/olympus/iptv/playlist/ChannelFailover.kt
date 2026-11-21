package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.NameStrip

/** Picks a sibling feed when the current HD stream fails or stalls, including cross-provider event feeds. */
object ChannelFailover {
    fun stem(name: String): String =
        name.lowercase()
            .replace(Regex("""\[.*?]|[({].*?[)}]"""), " ")
            .replace(Regex("""\b(fhd|uhd|hd|sd|4k|backup|feed|east|west)\b"""), " ")
            .replace(Regex("""\d+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun alternate(
        current: Channel,
        siblings: List<Channel>,
        skip: Set<String> = emptySet()
    ): Channel? {
        val key = stem(current.name)
        if (key.length >= 3) {
            siblings.firstOrNull { other ->
                other.id != current.id &&
                    other.id !in skip &&
                    other.streamUrl.isNotBlank() &&
                    other.streamUrl != current.streamUrl &&
                    stem(other.name) == key
            }?.let { return it }
        }
        val status = NameStrip.inspect(current.name, current.groupTitle, current.epgChannelId)
        val eventKey = SimilarChannels.key(status.airingEvent)
        if (eventKey.length >= 6) {
            return siblings.firstOrNull { other ->
                if (other.id == current.id || other.id in skip || other.streamUrl.isBlank()) return@firstOrNull false
                if (other.streamUrl == current.streamUrl) return@firstOrNull false
                val otherStatus = NameStrip.inspect(other.name, other.groupTitle, other.epgChannelId)
                SimilarChannels.key(otherStatus.airingEvent) == eventKey
            }
        }
        return null
    }
}
