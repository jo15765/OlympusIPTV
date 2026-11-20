package com.olympus.iptv.playlist

import com.olympus.iptv.data.EpgProgram

/** Four-hour slices of a channel's programs, so guide scroll can reuse RAM. */
object EpgWindow {
    const val BUCKET_MS = 4L * 60L * 60L * 1000L

    fun key(channelId: String, atMillis: Long): String = "$channelId@${atMillis / BUCKET_MS}"

    fun slice(programs: List<EpgProgram>, atMillis: Long): List<EpgProgram> {
        val start = atMillis / BUCKET_MS * BUCKET_MS
        val end = start + BUCKET_MS
        return programs.filter { it.endMillis > start && it.startMillis < end }
    }
}
