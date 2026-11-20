package com.olympus.iptv

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.playlist.CatalogDelta
import com.olympus.iptv.playlist.ChannelFailover
import com.olympus.iptv.ui.browse.SportsEvent
import com.olympus.iptv.ui.player.CatchupPrompt
import com.olympus.iptv.ui.player.FrameRateMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackExtrasTest {
    @Test
    fun searchPrefixAndGuideTags() {
        assertEquals("nfl*", com.olympus.iptv.playlist.FtsQuery.match("NFL"))
        assertEquals("news* kids*", com.olympus.iptv.playlist.FtsQuery.match("News Kids"))
        assertEquals("Ending in 6m", com.olympus.iptv.ui.browse.EpgTiming.tag(54 * 60_000L, 0L, 60 * 60_000L))
        assertEquals("Up Next", com.olympus.iptv.ui.browse.EpgTiming.tag(0L, 10 * 60_000L, 40 * 60_000L))
        assertEquals("4K 60fps", com.olympus.iptv.data.StreamQuality.badge("Sports 4K 60fps"))
        assertTrue(com.olympus.iptv.ui.DeviceForm.isLowRam(true, 512L * 1024 * 1024))
        assertTrue(com.olympus.iptv.ui.DeviceForm.isLowRam(false, 256L * 1024 * 1024))
        assertFalse(com.olympus.iptv.ui.DeviceForm.isLowRam(false, 512L * 1024 * 1024))
        val hour = 60L * 60L * 1000L
        val show = com.olympus.iptv.data.EpgProgram("News", 1 * hour, 2 * hour)
        assertEquals("news@0", com.olympus.iptv.playlist.EpgWindow.key("news", 30 * 60_000L))
        assertEquals(1, com.olympus.iptv.playlist.EpgWindow.slice(listOf(show), 30 * 60_000L).size)
        assertEquals(0, com.olympus.iptv.playlist.EpgWindow.slice(listOf(show), 5 * hour).size)
        assertEquals(-1, com.olympus.iptv.ui.browse.LetterJump.indexOf(emptyList(), "A"))
        val now = 1_000_000_000_000L
        val window = com.olympus.iptv.playlist.XmltvParser.window(now)
        assertFalse(com.olympus.iptv.playlist.XmltvParser.keep(now - 5 * 3_600_000L, now - 3 * 3_600_000L, window.first, window.second))
        assertTrue(com.olympus.iptv.playlist.XmltvParser.keep(now, now + 3_600_000L, window.first, window.second))
        assertFalse(com.olympus.iptv.playlist.XmltvParser.keep(now + 49 * 3_600_000L, now + 50 * 3_600_000L, window.first, window.second))
        assertEquals("account", com.olympus.iptv.ui.player.PlaybackTrace.advice(403, 0))
        assertEquals("backup", com.olympus.iptv.ui.player.PlaybackTrace.advice(502, 0))
    }

    @Test
    fun frameRateMapsToTvRefresh() {
        assertEquals(23.976f, FrameRateMatch.targetHz(23.976f))
        assertEquals(23.976f, FrameRateMatch.targetHz(24f))
        assertEquals(50f, FrameRateMatch.targetHz(50f))
        assertEquals(59.94f, FrameRateMatch.targetHz(59.94f))
        assertEquals(0f, FrameRateMatch.targetHz(0f))
    }

    @Test
    fun sportsBadgeShowsMinutesLeft() {
        assertTrue(SportsEvent.isMatchup("Chiefs vs Bills"))
        assertTrue(SportsEvent.isMatchup("Lakers @ Celtics"))
        assertTrue(SportsEvent.isMatchup("UFC 300 main card"))
        assertFalse(SportsEvent.isMatchup("Local news"))
        val start = 0L
        val end = 60 * 60_000L
        assertEquals(18, SportsEvent.minutesLeft(42 * 60_000L, start, end))
        assertEquals("LIVE EVENT · 18m left", SportsEvent.badge("Chiefs vs Bills", 42 * 60_000L, start, end))
        assertNull(SportsEvent.badge("Local news", 42 * 60_000L, start, end))
    }

    @Test
    fun catchupOffersOnlyAfterFiveMinutes() {
        val program = EpgProgram("Show", startMillis = 0L, endMillis = 30 * 60_000L)
        assertFalse(CatchupPrompt.shouldOffer(4 * 60_000L, program, true))
        assertTrue(CatchupPrompt.shouldOffer(6 * 60_000L, program, true))
        assertFalse(CatchupPrompt.shouldOffer(6 * 60_000L, program, false))
    }

    @Test
    fun failoverMatchesBackupFeed() {
        val current = Channel(id = "1", name = "ESPN HD [1]", streamUrl = "a")
        val backup = Channel(id = "2", name = "ESPN FHD [2]", streamUrl = "b")
        val other = Channel(id = "3", name = "CNN HD", streamUrl = "c")
        assertEquals("espn", ChannelFailover.stem(current.name))
        assertEquals(backup.id, ChannelFailover.alternate(current, listOf(other, backup))?.id)
        assertNull(ChannelFailover.alternate(current, listOf(other)))
    }

    @Test
    fun catalogDeltaSkipsUnchangedRows() {
        val first = Channel(id = "a", name = "One", streamUrl = "http://one")
        val same = first.copy()
        val renamed = first.copy(name = "Two")
        val known = setOf("a")
        assertTrue(CatalogDelta.writes(emptyMap(), listOf(first), known).isEmpty())
        val hashes = mapOf("a" to CatalogDelta.rowHash(first))
        assertTrue(CatalogDelta.writes(hashes, listOf(same), known).isEmpty())
        assertEquals("a", CatalogDelta.writes(hashes, listOf(renamed), known).single().id)
    }
}
