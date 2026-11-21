package com.olympus.iptv.ui.player

import androidx.media3.exoplayer.DefaultLoadControl

/** Live playback buffers. Tight enough for instant channel zapping, large enough to ride a brief stall. */
object PlayerBuffers {
    const val MIN_MS = 2_000
    const val MAX_MS = 15_000
    const val PLAYBACK_MS = 650
    const val REBUFFER_MS = 1_750

    fun live(lowRam: Boolean = false): DefaultLoadControl {
        val max = if (lowRam) 10_000 else MAX_MS
        val bytes = if (lowRam) 4 * 1024 * 1024 else 8 * 1024 * 1024
        val builder = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_MS, max, PLAYBACK_MS, REBUFFER_MS)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(true)
        if (lowRam) builder.setBackBuffer(10_000, true)
        return builder.build()
    }
}
