package com.olympus.iptv.ui.browse

object GuideMetrics {
    const val SPAN_MS = 90L * 60L * 1000L

    fun start(now: Long, halfHourMs: Long): Long = now / halfHourMs * halfHourMs

    fun end(start: Long): Long = start + SPAN_MS

    fun rowDp(compact: Boolean, tall: Boolean): Int = when {
        compact && tall -> 42
        compact -> 38
        tall -> 50
        else -> 46
    }

    fun rowPx(dp: Int, density: Float): Int = (dp * density).toInt().coerceAtLeast(1)

    fun verticalDelta(channelTop: Int, programTop: Int): Int = programTop - channelTop
}
