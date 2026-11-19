package com.olympus.iptv.data

object ListOrder {
    fun merge(saved: List<String>, source: List<String>): MutableList<String> {
        val sourceSet = source.toSet()
        val kept = saved.filter { it in sourceSet }
        val seen = kept.toSet()
        return (kept + source.filter { it !in seen }).toMutableList()
    }

    fun swap(order: MutableList<String>, id: String, direction: Int): Boolean {
        val from = order.indexOf(id)
        val to = from + direction
        if (from < 0 || to !in order.indices) return false
        val current = order[from]
        order[from] = order[to]
        order[to] = current
        return true
    }

    fun swapVisible(order: MutableList<String>, visible: List<String>, id: String, direction: Int): Boolean {
        val neighbor = visible.getOrNull(visible.indexOf(id) + direction) ?: return false
        val from = order.indexOf(id)
        val to = order.indexOf(neighbor)
        if (from < 0 || to < 0) return false
        val current = order[from]
        order[from] = order[to]
        order[to] = current
        return true
    }
}
