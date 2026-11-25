package com.olympus.iptv.data

object QuietHoursBridge {
    @Volatile
    private var cached: Triple<Boolean, Int, Int>? = null

    fun remember(enabled: Boolean, startMinute: Int, endMinute: Int) {
        cached = Triple(enabled, startMinute, endMinute)
    }

    fun snapshot(): Triple<Boolean, Int, Int>? = cached

    fun clear() {
        cached = null
    }
}
