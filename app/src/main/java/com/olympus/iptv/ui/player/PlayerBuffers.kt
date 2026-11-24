package com.olympus.iptv.ui.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl

/** Live playback buffers tuned to hold a steady cushion on both raw MPEG-TS and HLS streams. */
object PlayerBuffers {
    const val MIN_MS = 15_000
    const val MAX_MS = 40_000
    const val PLAYBACK_MS = 2_000
    const val REBUFFER_MS = 3_500

    fun live(lowRam: Boolean = false, highEndTv: Boolean = false): DefaultLoadControl {
        val min = if (lowRam) 8_000 else MIN_MS
        val max = when {
            lowRam -> 20_000
            highEndTv -> 45_000
            else -> MAX_MS
        }
        val start = if (lowRam || highEndTv) 1_500 else PLAYBACK_MS
        val back = when {
            lowRam -> 4_000
            highEndTv -> 10_000
            else -> 8_000
        }
        val builder = DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, max, start, REBUFFER_MS)
            .setBackBuffer(back, false)
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
