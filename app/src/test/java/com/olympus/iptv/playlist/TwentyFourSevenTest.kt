package com.olympus.iptv.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwentyFourSevenTest {
    @Test
    fun detectsAllCommon247Formats() {
        assertTrue(TwentyFourSeven.is247("24/7: The Office"))
        assertTrue(TwentyFourSeven.is247("USA | 24/7 Shows"))
        assertTrue(TwentyFourSeven.is247("24-7 Movies"))
        assertTrue(TwentyFourSeven.is247("24x7 Sitcoms"))
        assertTrue(TwentyFourSeven.is247("Friends 24/7"))
        assertFalse(TwentyFourSeven.is247("ESPN 2"))
        assertFalse(TwentyFourSeven.is247("Channel 124/70"))
    }

    @Test
    fun alphabetizesByActualChannelNameNot247Prefix() {
        val raw = listOf(
            "24/7: Seinfeld",
            "USA | 24/7 - Alf",
            "[24/7] Cheers",
            "Friends 24/7",
            "US: 24/7 Breaking Bad",
            "24-7 Dexter",
            "24x7 | Family Guy"
        )
        val sorted = raw.sortedBy { TwentyFourSeven.sortKey(it) }
        assertEquals(
            listOf(
                "USA | 24/7 - Alf",
                "US: 24/7 Breaking Bad",
                "[24/7] Cheers",
                "24-7 Dexter",
                "24x7 | Family Guy",
                "Friends 24/7",
                "24/7: Seinfeld"
            ),
            sorted
        )
    }
}
