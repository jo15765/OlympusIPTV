package com.olympus.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFavoritesLocalTest {

    @Test
    fun matchesCompactNetworkNumbersAndNonPipeGroups() {
        val channels = listOf(
            AutoFavorites.GuideChannel("1", "US: ABC7 New York", "US Locals"),
            AutoFavorites.GuideChannel("2", "US: CBS2 New York", "US Locals"),
            AutoFavorites.GuideChannel("3", "US: NBC4 New York", "US Locals"),
            AutoFavorites.GuideChannel("4", "US: FOX5 New York Live", "US Locals"),
            AutoFavorites.GuideChannel("5", "FOX News Channel", "USA | News")
        )
        val result = AutoFavorites.localStations(channels, 40.7128, -74.0060)
        assertEquals("New York", result.market)
        assertEquals(setOf("1", "2", "3", "4"), result.picks.map { it.id }.toSet())
    }

    @Test
    fun supplementsMissingNetworksFromNearbyMarketAndOfflineZip() {
        val place = ZipLocator.fallbackForPrefix("75201")
        assertNotNull(place)
        val channels = listOf(
            AutoFavorites.GuideChannel("d1", "Dallas | ABC 8", "USA | ABC"),
            AutoFavorites.GuideChannel("d2", "Dallas | CBS 11", "USA | CBS"),
            AutoFavorites.GuideChannel("h1", "Houston | NBC 2", "USA | NBC"),
            AutoFavorites.GuideChannel("h2", "Houston | FOX 26", "USA | FOX")
        )
        val result = AutoFavorites.localStations(channels, place!!.lat, place.lon)
        assertTrue("Expected at least 4 local picks, got ${result.picks}", result.picks.size >= 4)
        assertEquals(setOf("d1", "d2", "h1", "h2"), result.picks.map { it.id }.toSet())
    }

    @Test
    fun smallCityWithFewChannelsFallsBackToClosestMajorCityAllFiveNetworks() {
        // Smaller city (Macon, GA ~ 32.84, -83.63) only has 1 channel; Atlanta (~130km away) has all 5 majors
        val channels = listOf(
            AutoFavorites.GuideChannel("m1", "Macon | CBS 13", "USA | Local CBS"),
            AutoFavorites.GuideChannel("a1", "Atlanta | ABC 2", "USA | Local ABC"),
            AutoFavorites.GuideChannel("a2", "Atlanta | CBS 46", "USA | Local CBS"),
            AutoFavorites.GuideChannel("a3", "Atlanta | NBC 11", "USA | Local NBC"),
            AutoFavorites.GuideChannel("a4", "Atlanta | FOX 5", "USA | Local FOX"),
            AutoFavorites.GuideChannel("a5", "Atlanta | PBS 8", "USA | Local PBS")
        )
        val result = AutoFavorites.localStations(channels, 32.8407, -83.6324)
        assertEquals("Atlanta", result.market)
        assertEquals(setOf("a1", "a2", "a3", "a4", "a5"), result.picks.map { it.id }.toSet())
    }
}
