package com.olympus.iptv.ui.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl

object PlayerBuffers {
    const val MIN_MS = 15_000
    const val MAX_MS = 40_000
    const val PLAYBACK_MS = 1_200
    const val REBUFFER_MS = 2_500

    fun live(lowRam: Boolean = false, highEndTv: Boolean = false, timeshiftMinutes: Int = 0): DefaultLoadControl {
        val min = if (lowRam) 8_000 else MIN_MS
        val max = when {
            lowRam -> 20_000
            highEndTv -> 45_000
            else -> MAX_MS
        }
        val start = if (lowRam) PLAYBACK_MS else 1_000
        val extraBackMs = if (lowRam) 0 else (timeshiftMinutes.coerceIn(0, 60) * 15_000).coerceAtMost(120_000)
        val back = when {
            lowRam -> 4_000
            highEndTv -> 10_000 + extraBackMs
            else -> 8_000 + extraBackMs
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
