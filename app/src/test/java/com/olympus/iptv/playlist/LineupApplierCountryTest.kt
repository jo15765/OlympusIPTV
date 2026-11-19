package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupApplierCountryTest {
    @Test
    fun untaggedLiveChannelStaysVisibleWhenInternationalIsOff() {
        val channel = live("247-1", "Friends 24/7", "24/7 Shows", countryId = null)
        assertTrue(LineupApplier.keep(channel, emptySet(), emptySet(), emptySet(), includeInternational = false))
    }

    @Test
    fun blankCountryLiveChannelStaysVisibleWhenInternationalIsOff() {
        val channel = live("247-2", "Seinfeld 24/7", "24/7 Shows", countryId = "")
        assertTrue(LineupApplier.keep(channel, emptySet(), emptySet(), emptySet(), includeInternational = false))
    }

    @Test
    fun foreignLiveChannelIsHiddenWhenInternationalIsOff() {
        val channel = live("uk-1", "UK News", "UK | News", countryId = "uk")
        assertFalse(LineupApplier.keep(channel, emptySet(), emptySet(), emptySet(), includeInternational = false))
    }

    @Test
    fun usLiveChannelStaysVisibleWhenInternationalIsOff() {
        val channel = live("us-1", "US News", "US | News", countryId = "us")
        assertTrue(LineupApplier.keep(channel, emptySet(), emptySet(), emptySet(), includeInternational = false))
    }

    @Test
    fun mega247FolderDoesNotEmptyWhenInternationalIsOff() {
        // Mirrors CatalogDb.readVisiblePage + keep(): SQL allows blank country; keep must not drop them.
        val page = (1..1102).map { index ->
            live(
                id = "247-$index",
                name = "Show $index 24/7",
                group = "24/7 Shows",
                categoryId = "991",
                countryId = if (index % 2 == 0) null else ""
            )
        }
        val kept = page.filter {
            LineupApplier.keep(it, emptySet(), emptySet(), emptySet(), includeInternational = false)
        }
        assertEquals(1102, kept.size)
    }

    @Test
    fun foreignInferredFromGroupTitleStillHidden() {
        val channel = live("br-1", "Globo", "Brazil", countryId = null)
        // CountryIndex should parse "Brazil" → blocked when international is off.
        val blocked = !LineupApplier.keep(channel, emptySet(), emptySet(), emptySet(), includeInternational = false)
        assertTrue(blocked)
    }

    private fun live(
        id: String,
        name: String,
        group: String,
        countryId: String?,
        categoryId: String = "1"
    ): Channel = Channel(
        id = id,
        name = name,
        streamUrl = "http://example/live/$id",
        groupTitle = group,
        categoryId = categoryId,
        kind = ContentKind.LIVE,
        countryId = countryId
    )
}
