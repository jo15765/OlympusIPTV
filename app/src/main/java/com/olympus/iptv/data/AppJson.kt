package com.olympus.iptv.data

import com.google.gson.Gson

/**
 * Single shared Gson instance across the entire app so TypeAdapter reflection caches
 * are built once instead of duplicated across 10+ stores (saves ~3-5 MB heap on Fire TV Sticks).
 */
object AppJson {
    val gson: Gson = Gson()
}
