package com.olympus.iptv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NameStripEventSlotTest {
    private fun etMillis(year: Int, month: Int, day: Int, hour24: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(year, month, day, hour24, minute)
        return cal.timeInMillis
    }

    @Test
    fun sanitizedAndPrefixedEspnPlusFutureGamesAreHiddenUntilPreEventWindow() {
        val year = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).get(Calendar.YEAR)
        val afternoon = etMillis(year, Calendar.NOVEMBER, 25, 14, 0)
        val preWindow = etMillis(year, Calendar.NOVEMBER, 25, 18, 40)
        val ended = etMillis(year, Calendar.NOVEMBER, 25, 22, 15)

        val cases = listOf(
            "ESPN+ 16: Akron vs. #22 West Virginia @ Nov 25 7:00PM ET",
            "16: Akron vs. #22 West Virginia @ Nov 25 7:00PM ET",
            "USA | ESPN+ 37: Chargers vs. Cyclones @ Nov 25 7:00PM ET (HD)",
            "Akron vs. West Virginia @ Nov 25 7:00PM ET"
        )
        for (raw in cases) {
            val status = NameStrip.inspect(raw)
            assertTrue("Expected event slot for $raw", status.isEventSlot)
            assertFalse("Expected $raw hidden at 2:00 PM ET", status.isVisibleAt(afternoon))
            assertTrue("Expected $raw visible at 6:40 PM ET", status.isVisibleAt(preWindow))
            assertFalse("Expected $raw hidden after ending", status.isVisibleAt(ended))
        }
    }

    @Test
    fun regularNumberedLiveChannelStaysVisible() {
        val status = NameStrip.inspect("101: HBO East")
        assertFalse(status.isEventSlot)
        assertTrue(status.isVisibleAt(System.currentTimeMillis()))
    }
}
