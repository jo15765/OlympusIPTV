package com.olympus.iptv.playlist

/** Same programme on another channel, ignoring punctuation and letter case. */
object SimilarChannels {
    private val noise = Regex("[^\\p{L}\\p{N}]+")

    fun key(title: String?): String = title.orEmpty().lowercase().replace(noise, "")

    fun same(left: String?, right: String?): Boolean {
        val a = key(left)
        val b = key(right)
        return a.length >= 4 && a == b
    }
}
