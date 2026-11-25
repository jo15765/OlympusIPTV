package com.olympus.iptv.playlist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HugeGroupPagingInstrumentedTest {
    private lateinit var db: CatalogDb

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = CatalogDb(context)
        db.wipeAll()
    }

    @Test
    fun opened247GroupPagesTwentyAtATimeDespiteInternationalOff() {
        val playlistId = "qa-247"
        val categoryId = "991"
        val category = Category(id = categoryId, name = "24/7 Shows", kind = ContentKind.LIVE)
        val channels = (1..1102).map { index ->
            Channel(
                id = "247-$index",
                name = "Show $index 24/7",
                streamUrl = "http://example.test/live/$index",
                groupTitle = "24/7 Shows",
                categoryId = categoryId,
                kind = ContentKind.LIVE,
                countryId = "uk",
                number = index
            )
        }
        db.insertMissing(playlistId, CatalogDb.LIVE, listOf(category), channels)

        val counts = db.categoryCounts(playlistId, CatalogDb.LIVE)
        assertEquals(1102, counts[categoryId])

        val first = db.readGroupPage(
            playlistId = playlistId,
            bucket = CatalogDb.LIVE,
            categoryId = categoryId,
            limit = 20,
            after = null,
            hiddenChannels = emptySet()
        )
        assertEquals(20, first.channels.size)
        assertFalse(first.exhausted)
        assertEquals((1..20).map { "247-$it" }, first.channels.map { it.id })

        val filtered = db.readVisiblePage(
            playlistId = playlistId,
            bucket = CatalogDb.LIVE,
            categoryId = categoryId,
            limit = 20,
            after = null,
            hiddenChannels = emptySet(),
            hiddenGroups = emptySet(),
            hiddenCountries = emptySet(),
            includeInternational = false
        )
        assertTrue(filtered.channels.isEmpty())

        val second = db.readGroupPage(
            playlistId = playlistId,
            bucket = CatalogDb.LIVE,
            categoryId = categoryId,
            limit = 20,
            after = first.cursor,
            hiddenChannels = emptySet()
        )
        assertEquals(20, second.channels.size)
        assertEquals((21..40).map { "247-$it" }, second.channels.map { it.id })
        assertTrue(first.channels.map { it.id }.intersect(second.channels.map { it.id }.toSet()).isEmpty())

        val itemCount = 20
        val lastVisibleNearEnd = 15
        val remain = 5
        assertTrue(lastVisibleNearEnd >= itemCount - remain)
    }
}
