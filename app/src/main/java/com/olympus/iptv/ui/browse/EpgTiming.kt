package com.olympus.iptv.ui.browse

import com.olympus.iptv.playlist.DummyEpg

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
        if (program.guideTitle.isNotEmpty()) return program
        val cleanTitle = DummyEpg.stripChannelNumber(program.title).ifBlank { program.title }
        return program.copy(guideTitle = cleanTitle, guideClock = "")
    }
}
