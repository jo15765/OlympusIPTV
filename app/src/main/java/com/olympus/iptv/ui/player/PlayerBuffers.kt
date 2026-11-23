package com.olympus.iptv.ui.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl

/** Live playback buffers tuned to hold a steady cushion on both raw MPEG-TS and HLS streams. */
object PlayerBuffers {
    const val MIN_MS = 15_000
    const val MAX_MS = 40_000
    const val PLAYBACK_MS = 2_000
    const val REBUFFER_MS = 3_500

    fun live(lowRam: Boolean = false): DefaultLoadControl {
        val min = if (lowRam) 8_000 else MIN_MS
        val max = if (lowRam) 20_000 else MAX_MS
        val builder = DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, max, PLAYBACK_MS, REBUFFER_MS)
            .setBackBuffer(8_000, false)
        if (lowRam) {
            builder
                .setTargetBufferBytes(24 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
        } else {
            builder
                .setTargetBufferBytes(C.LENGTH_UNSET)
                .setPrioritizeTimeOverSizeThresholds(true)
        }
        return builder.build()
    }
}
