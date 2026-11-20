package com.olympus.iptv.ui.player

import com.olympus.iptv.data.EpgProgram

object CatchupPrompt {
    const val LATE_MS = 5 * 60_000L
    const val DISMISS_MS = 6_000L

    fun shouldOffer(now: Long, program: EpgProgram?, catchupReady: Boolean): Boolean {
        if (!catchupReady || program == null) return false
        if (now < program.startMillis || now >= program.endMillis) return false
        return now - program.startMillis > LATE_MS
    }
}
