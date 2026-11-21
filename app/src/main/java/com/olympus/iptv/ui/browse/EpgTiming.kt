package com.olympus.iptv.ui.browse

import com.olympus.iptv.playlist.DummyEpg

/** Labels for programs that are about to end or about to start. */
object EpgTiming {
    fun tag(now: Long, start: Long, end: Long): String? {
        if (end <= start) return null
        if (now in start until end) {
            val left = (((end - now) + 59_999L) / 60_000L).toInt()
            if (left in 1..10) return "Ending in ${left}m"
            return null
        }
        if (start > now && start - now <= 30 * 60_000L) return "Up Next"
        return null
    }

    fun clock(millis: Long): String = com.olympus.iptv.playlist.EpgUtils.formatClock(millis)

    fun dress(program: com.olympus.iptv.data.EpgProgram, now: Long): com.olympus.iptv.data.EpgProgram {
        if (program.guideClock.isNotEmpty()) return program
        val cleanTitle = DummyEpg.stripChannelNumber(program.title).ifBlank { program.title }
        val timing = tag(now, program.startMillis, program.endMillis)
        val title = if (timing.isNullOrBlank()) cleanTitle else "$timing · $cleanTitle"
        val clock = clock(program.startMillis) + " – " + clock(program.endMillis)
        return program.copy(guideTitle = title, guideClock = clock)
    }
}
