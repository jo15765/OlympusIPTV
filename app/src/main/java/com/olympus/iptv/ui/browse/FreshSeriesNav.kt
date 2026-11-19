package com.olympus.iptv.ui.browse

import com.olympus.iptv.data.Channel

object FreshSeriesNav {
    fun seasons(episodes: List<Channel>): List<Int> =
        episodes.map { it.season ?: 1 }.distinct().sorted()

    fun bySeason(episodes: List<Channel>): Map<Int, List<Channel>> {
        if (episodes.isEmpty()) return emptyMap()
        val map = LinkedHashMap<Int, ArrayList<Channel>>()
        for (episode in episodes) {
            val number = episode.season ?: 1
            map.getOrPut(number) { ArrayList() }.add(episode)
        }
        return map
    }

    fun pickSeason(available: List<Int>, current: Int): Int {
        if (available.isEmpty()) return current
        if (current in available) return current
        return available.first()
    }

    fun playQueue(episodes: List<Channel>, season: Int): List<Channel> =
        episodes.filter { (it.season ?: 1) == season }

    fun sameSeasons(left: List<Int>, right: List<Int>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { left[it] == right[it] }
    }
}
