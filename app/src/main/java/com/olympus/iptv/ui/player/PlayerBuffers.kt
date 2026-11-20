package com.olympus.iptv.ui.player

import androidx.media3.exoplayer.DefaultLoadControl

/** Live playback buffers. Tight enough to start, large enough to ride a brief stall. */
object PlayerBuffers {
    const val MIN_MS = 2_500
    const val MAX_MS = 15_000
    const val PLAYBACK_MS = 1_000
    const val REBUFFER_MS = 2_000

    fun live(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_MS, MAX_MS, PLAYBACK_MS, REBUFFER_MS)
        .setTargetBufferBytes(6 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
