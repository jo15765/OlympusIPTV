package com.olympus.iptv.data

/** In-memory quiet-hours snapshot so alarm ticks avoid DataStore when already known. */
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
