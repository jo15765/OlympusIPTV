package com.olympus.iptv.ui.browse

import com.olympus.iptv.data.WatchEntry
import com.olympus.iptv.playlist.GroupLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideFeelTest {
    @Test
    fun continueStripTakesFocusOnMoviesWhenVisible() {
        assertTrue(GuideFeel.landOnContinueStrip(sectionIsLive = false, stripVisible = true))
        assertFalse(GuideFeel.landOnContinueStrip(sectionIsLive = true, stripVisible = true))
        assertFalse(GuideFeel.landOnContinueStrip(sectionIsLive = false, stripVisible = false))
    }

    @Test
    fun chipsDropToContinueWhenStripIsUp() {
        assertEquals("homeContinue", GuideFeel.chipDownTarget(continueVisible = true))
        assertEquals("categoryList", GuideFeel.chipDownTarget(continueVisible = false))
    }

    @Test
    fun autoSkipRemembersSeries() {
        assertTrue(GuideFeel.autoSkipIntro("show-1", setOf("show-1", "show-2")))
        assertFalse(GuideFeel.autoSkipIntro("show-3", setOf("show-1")))
        assertFalse(GuideFeel.autoSkipIntro("  ", setOf("show-1")))
        assertFalse(GuideFeel.autoSkipIntro(null, setOf("show-1")))
    }

    @Test
    fun sectionKeysRoundTrip() {
        assertEquals("vod", GuideFeel.sectionFromKey(GuideFeel.sectionKey(live = false, movies = true, shows = false)))
        assertEquals("series", GuideFeel.sectionFromKey("SHOWS"))
        assertEquals("live", GuideFeel.sectionFromKey("nope"))
    }

    @Test
    fun watchEntryResumeRules() {
        assertFalse(WatchEntry(positionMs = 10_000L, durationMs = 3_600_000L).canResume())
        assertTrue(WatchEntry(positionMs = 60_000L, durationMs = 3_600_000L).canResume())
        assertFalse(WatchEntry(positionMs = 3_590_000L, durationMs = 3_600_000L).canResume())
    }

    @Test
    fun parentalUnlockUsesRequestedMinutes() {
        GroupLock.lock("g1")
        assertTrue(GroupLock.locked(com.olympus.iptv.data.LineupConfig(groupPins = mapOf("g1" to GroupLock.code("1234"))), "g1"))
        GroupLock.unlock("g1", 15)
        assertFalse(GroupLock.locked(com.olympus.iptv.data.LineupConfig(groupPins = mapOf("g1" to GroupLock.code("1234"))), "g1"))
        GroupLock.lock("g1")
    }
}
