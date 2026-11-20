package com.olympus.iptv.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TitleRatingsCleanTitleTest {
    @Test
    fun stripsBracketsAndBracesWithoutCrashing() {
        val cleaned = TitleRatings.cleanTitle(
            "Percy Jackson and the Olympians - S03E01 - My Rescue [1080p] {US}"
        )
        assertFalse(cleaned.contains("["))
        assertFalse(cleaned.contains("{"))
        assertEquals(
            "Percy Jackson and the Olympians - S03E01 - My Rescue",
            cleaned
        )
    }

    @Test
    fun blankInputStaysBlank() {
        assertEquals("", TitleRatings.cleanTitle("   "))
    }
}
