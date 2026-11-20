package com.olympus.iptv.ui.browse

import com.olympus.iptv.data.Channel

object FreshSeriesNav {
    fun playable(episodes: List<Channel>): List<Channel> =
        episodes.filter { it.streamUrl.isNotBlank() }

    fun seasons(episodes: List<Channel>): List<Int> =
        playable(episodes).map { it.season ?: 1 }.distinct().sorted()

    fun bySeason(episodes: List<Channel>): Map<Int, List<Channel>> {
        val playable = playable(episodes)
        if (playable.isEmpty()) return emptyMap()
        val map = LinkedHashMap<Int, ArrayList<Channel>>()
        for (episode in playable) {
            val number = episode.season ?: 1
            map.getOrPut(number) { ArrayList() }.add(episode)
        }
        return map
    }

    fun pickSeason(available: List<Int>, current: Int, prefer: Int? = null): Int {
        if (available.isEmpty()) return current
        val wanted = prefer?.takeIf { it > 0 }
        if (wanted != null && wanted in available) return wanted
        if (current in available) return current
        return available.first()
    }

    fun playQueue(episodes: List<Channel>, season: Int): List<Channel> =
        playable(episodes).filter { (it.season ?: 1) == season }

    fun sameSeasons(left: List<Int>, right: List<Int>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { left[it] == right[it] }
    }
}
