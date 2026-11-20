package com.olympus.iptv.ui.browse

/** Timeline window used by the program grid. Kept out of the browse coordinators. */
object GuideMetrics {
    const val SPAN_MS = 8L * 60L * 60L * 1000L

    fun start(now: Long, halfHourMs: Long): Long = now / halfHourMs * halfHourMs

    fun end(start: Long): Long = start + SPAN_MS
}
