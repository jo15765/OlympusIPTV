package com.olympus.iptv.playlist

import com.olympus.iptv.data.LineupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLockTest {
    @Test
    fun codeMatchesTheDashboard() {
        assertEquals("fdc422fd", GroupLock.code("1234"))
    }

    @Test
    fun pushedConfigNeverUnhidesLocalAdultGroupOrClearsPins() {
        val restrictedLocal = LineupConfig(
            hiddenGroups = listOf(AdultGroup.GROUP_ID, "XXX 18+ VIP", "Kids"),
            groupPins = mapOf(AdultGroup.GROUP_ID to GroupLock.code("4321"))
        )
        val masterWithAdultEnabled = LineupConfig(
            groupOrder = listOf("Sports", "News"),
            hiddenGroups = listOf("News"),
            groupPins = emptyMap()
        )
        val merged = AdultGroup.preserveLocalParentalOverrides(restrictedLocal, masterWithAdultEnabled)
        assertTrue(merged.hiddenGroups.orEmpty().contains(AdultGroup.GROUP_ID))
        assertTrue(merged.hiddenGroups.orEmpty().contains("XXX 18+ VIP"))
        assertTrue(merged.hiddenGroups.orEmpty().contains("News"))
        assertFalse(merged.hiddenGroups.orEmpty().contains("Kids"))
        assertEquals(GroupLock.code("4321"), merged.groupPins.orEmpty()[AdultGroup.GROUP_ID])
        assertEquals(listOf("Sports", "News"), merged.groupOrder)
    }

    @Test
    fun unrestrictedLocalAcceptsIncomingLineupCleanly() {
        val local = LineupConfig(hiddenGroups = listOf("Kids"), groupPins = emptyMap())
        val incoming = LineupConfig(groupOrder = listOf("Sports"), hiddenGroups = listOf("News"))
        val merged = AdultGroup.preserveLocalParentalOverrides(local, incoming)
        assertEquals(listOf("News"), merged.hiddenGroups)
        assertEquals(listOf("Sports"), merged.groupOrder)
    }
}
