package com.olympus.iptv.ui.player

import androidx.media3.exoplayer.DefaultLoadControl

/** Live playback buffers optimized for smooth 60fps streaming without stutter or underruns. */
object PlayerBuffers {
    const val MIN_MS = 15_000
    const val MAX_MS = 35_000
    const val PLAYBACK_MS = 2_500
    const val REBUFFER_MS = 4_000

    fun live(lowRam: Boolean = false): DefaultLoadControl {
        val min = if (lowRam) 8_000 else MIN_MS
        val max = if (lowRam) 20_000 else MAX_MS
        val bytes = if (lowRam) 16 * 1024 * 1024 else 32 * 1024 * 1024
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, max, PLAYBACK_MS, REBUFFER_MS)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
}
