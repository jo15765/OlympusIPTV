package com.olympus.iptv.ui.player

object FrameRateMatch {
    fun targetHz(videoFps: Float): Float = when {
        videoFps <= 0f -> 0f
        videoFps < 24.5f -> 23.976f
        videoFps < 27f -> 25f
        videoFps < 40f -> 29.97f
        videoFps < 55f -> 50f
        else -> 59.94f
    }
}
