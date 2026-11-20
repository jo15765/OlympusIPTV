package com.olympus.iptv.ui.browse

/** Timeline window used by the program grid. Kept out of the browse coordinators. */
object GuideMetrics {
    const val SPAN_MS = 8L * 60L * 60L * 1000L

    fun start(now: Long, halfHourMs: Long): Long = now / halfHourMs * halfHourMs

    fun end(start: Long): Long = start + SPAN_MS

    /** Guide row height in dp. Compact is the tighter living-room density. */
    fun rowDp(compact: Boolean, tall: Boolean): Int = when {
        compact && tall -> 36
        compact -> 32
        tall -> 44
        else -> 40
    }

    fun rowPx(dp: Int, density: Float): Int = (dp * density).toInt().coerceAtLeast(1)

    /** Program-list top minus channel-list top. Positive means the programs start lower. */
    fun verticalDelta(channelTop: Int, programTop: Int): Int = programTop - channelTop
}
