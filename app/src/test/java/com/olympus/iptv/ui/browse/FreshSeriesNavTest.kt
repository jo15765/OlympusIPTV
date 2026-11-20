package com.olympus.iptv.ui.browse

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshSeriesNavTest {
    private fun ep(id: String, season: Int, number: Int = 1) = Channel(
        id = id,
        name = "Episode $number",
        streamUrl = "http://example/$id",
        kind = ContentKind.VOD,
        season = season,
        number = number
    )

    @Test
    fun seasonsAreSortedDistinct() {
        val episodes = listOf(
            ep("s2e1", 2, 1),
            ep("s1e2", 1, 2),
            ep("s1e1", 1, 1),
            ep("s3e1", 3, 1),
            ep("s2e2", 2, 2)
        )
        assertEquals(listOf(1, 2, 3), FreshSeriesNav.seasons(episodes))
    }

    @Test
    fun bySeasonKeepsEpisodeOrderWithinSeason() {
        val episodes = listOf(
            ep("s1e1", 1, 1),
            ep("s2e1", 2, 1),
            ep("s1e2", 1, 2),
            ep("s2e2", 2, 2)
        )
        val grouped = FreshSeriesNav.bySeason(episodes)
        assertEquals(listOf("s1e1", "s1e2"), grouped[1]?.map { it.id })
        assertEquals(listOf("s2e1", "s2e2"), grouped[2]?.map { it.id })
    }

    @Test
    fun nullSeasonCountsAsSeasonOne() {
        val episodes = listOf(
            Channel("a", "A", "http://a", kind = ContentKind.VOD, season = null, number = 1),
            ep("b", 2, 1)
        )
        assertEquals(listOf(1, 2), FreshSeriesNav.seasons(episodes))
        assertEquals(listOf("a"), FreshSeriesNav.bySeason(episodes)[1]?.map { it.id })
    }

    @Test
    fun pickSeasonKeepsCurrentWhenValid() {
        assertEquals(2, FreshSeriesNav.pickSeason(listOf(1, 2, 3), 2))
    }

    @Test
    fun pickSeasonFallsBackToFirstWhenCurrentMissing() {
        assertEquals(1, FreshSeriesNav.pickSeason(listOf(1, 3), 2))
        assertEquals(4, FreshSeriesNav.pickSeason(emptyList(), 4))
    }

    @Test
    fun pickSeasonPrefersPremiereSeason() {
        assertEquals(3, FreshSeriesNav.pickSeason(listOf(1, 2, 3), 1, prefer = 3))
        assertEquals(1, FreshSeriesNav.pickSeason(listOf(1, 2), 1, prefer = 3))
    }

    @Test
    fun seasonsIgnoreEpisodesWithoutStreamUrl() {
        val episodes = listOf(
            ep("s1e1", 1, 1),
            Channel("s3e1", "S3", "", kind = ContentKind.VOD, season = 3, number = 1),
            ep("s2e1", 2, 1)
        )
        assertEquals(listOf(1, 2), FreshSeriesNav.seasons(episodes))
        assertTrue(FreshSeriesNav.playQueue(episodes, 3).isEmpty())
    }

    @Test
    fun playQueueOnlyIncludesChosenSeason() {
        val episodes = listOf(
            ep("s1e1", 1, 1),
            ep("s2e1", 2, 1),
            ep("s2e2", 2, 2),
            ep("s3e1", 3, 1)
        )
        val queue = FreshSeriesNav.playQueue(episodes, 2)
        assertEquals(listOf("s2e1", "s2e2"), queue.map { it.id })
        assertTrue(queue.any { it.id == "s2e2" })
        assertFalse(queue.any { it.id == "s1e1" })
    }

    @Test
    fun playQueueUsesEpisodeSeasonWhenSelectingPlaybackTarget() {
        val episodes = listOf(
            ep("s1e1", 1, 1),
            ep("s2e3", 2, 3),
            ep("s2e1", 2, 1)
        )
        val target = episodes.first { it.id == "s2e3" }
        val queue = FreshSeriesNav.playQueue(episodes, target.season ?: 1)
        assertEquals("s2e3", queue.first { it.id == target.id }.id)
        assertEquals(2, queue.size)
        assertTrue(queue.all { (it.season ?: 1) == 2 })
    }

    @Test
    fun sameSeasonsComparesExactOrder() {
        assertTrue(FreshSeriesNav.sameSeasons(listOf(1, 2, 3), listOf(1, 2, 3)))
        assertFalse(FreshSeriesNav.sameSeasons(listOf(1, 2), listOf(1, 2, 3)))
        assertFalse(FreshSeriesNav.sameSeasons(listOf(1, 3, 2), listOf(1, 2, 3)))
    }

    @Test
    fun switchingSeasonDoesNotAffectOtherSeasonQueues() {
        val episodes = listOf(
            ep("s1e1", 1, 1),
            ep("s1e2", 1, 2),
            ep("s4e1", 4, 1),
            ep("s4e2", 4, 2)
        )
        val fromSeason1 = FreshSeriesNav.playQueue(episodes, 1)
        val fromSeason4 = FreshSeriesNav.playQueue(episodes, 4)
        assertEquals(listOf("s1e1", "s1e2"), fromSeason1.map { it.id })
        assertEquals(listOf("s4e1", "s4e2"), fromSeason4.map { it.id })
        assertTrue(fromSeason1.none { it.id in fromSeason4.map { ep -> ep.id } })
    }
}
