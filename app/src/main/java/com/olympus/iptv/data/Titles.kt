package com.olympus.iptv.data

object Titles {
    private val fourKdv = Regex("^(?i)4KDV:\\s*")
    private val fourK = Regex("^(?i)4K:\\s*")

    fun clean(raw: String): String {
        var name = raw.trim()
        while (true) {
            val next = name.replace(fourKdv, "").replace(fourK, "").trim()
            if (next == name) return name
            name = next
        }
    }
}
