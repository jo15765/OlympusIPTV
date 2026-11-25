package com.olympus.iptv.ui.player

import android.media.audiofx.DynamicsProcessing
import android.os.Build

object AudioLeveler {
    fun attach(audioSessionId: Int): DynamicsProcessing? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (audioSessionId == 0) return null
        return runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,
                false,
                0,
                true,
                1,
                false,
                0,
                true
            ).build()
            val band = config.getMbcByChannelIndex(0).getBand(0)
            band.isEnabled = true
            band.cutoffFrequency = 20_000f
            band.attackTime = 8f
            band.releaseTime = 180f
            band.ratio = 4f
            band.threshold = -20f
            band.kneeWidth = 8f
            band.noiseGateThreshold = -70f
            band.expanderRatio = 1f
            band.preGain = 8f
            band.postGain = 0f
            val limiter = config.getLimiterByChannelIndex(0)
            limiter.isEnabled = true
            limiter.attackTime = 1f
            limiter.releaseTime = 60f
            limiter.ratio = 10f
            limiter.threshold = -2f
            limiter.postGain = 0f
            DynamicsProcessing(0, audioSessionId, config).apply { enabled = true }
        }.getOrNull()
    }
}
