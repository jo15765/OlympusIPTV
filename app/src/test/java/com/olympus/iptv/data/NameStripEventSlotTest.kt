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
        val preWindow = etMillis(year, Calendar.NOVEMBER, 25, 18, 50)
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
            assertTrue("Expected $raw visible at 6:50 PM ET", status.isVisibleAt(preWindow))
            assertFalse("Expected $raw hidden after ending", status.isVisibleAt(ended))
        }
    }

    @Test
    fun londonAndIsoAndOrdinalEventsParseCorrectly() {
        val year = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).get(Calendar.YEAR)
        val eveningEt = etMillis(year, Calendar.NOVEMBER, 25, 18, 0)

        val ukDazn = NameStrip.inspect("DAZN UK 12: Red Stars vs. Diables Noirs @ 25 Nov 03:00 PM London")
        assertTrue(ukDazn.isEventSlot)
        assertFalse("3:00 PM London (10:00 AM ET) should be hidden at 6:00 PM ET", ukDazn.isVisibleAt(eveningEt))

        val beDazn = NameStrip.inspect("DAZN BE 04 : Union SG - RSC Anderlecht (2025-08-31 18:30:00)")
        assertTrue(beDazn.isEventSlot)
        assertFalse("2025-08-31 ISO game should be hidden", beDazn.isVisibleAt(eveningEt))

        val tv4 = NameStrip.inspect("Tv4 Play SE 02: Bollklubben 25th NOV 5PM CET")
        assertTrue(tv4.isEventSlot)
        assertFalse("5PM CET (11AM ET) should be hidden at 6:00 PM ET", tv4.isVisibleAt(eveningEt))
    }

    @Test
    fun regularNumberedLiveChannelStaysVisible() {
        val status = NameStrip.inspect("101: HBO East")
        assertFalse(status.isEventSlot)
        assertTrue(status.isVisibleAt(System.currentTimeMillis()))
    }

    @Test
    fun eventHidesExactly30MinutesAfterScheduledEndAndDummyEpgMatches() {
        val year = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).get(Calendar.YEAR)
        val raw = "ESPN+ 16: Akron vs. West Virginia @ Nov 25 7:00PM ET"
        val status = NameStrip.inspect(raw)
        assertTrue(status.isEventSlot)
        // ESPN+ basketball duration = 135 min (7:00 PM -> 9:15 PM ET)
        val atEnd = status.eventEndMs
        val twentyNineAfter = atEnd + 29L * 60L * 1000L
        val thirtyAfter = atEnd + 30L * 60L * 1000L

        assertTrue("Visible during 30m post-event window", status.isVisibleAt(twentyNineAfter))
        assertFalse("Hidden at exactly 30m after scheduled end", status.isVisibleAt(thirtyAfter))

        val epgDuringGrace = com.olympus.iptv.playlist.DummyEpg.programs(raw, twentyNineAfter)
        assertFalse("DummyEpg should still show event within 30m grace", epgDuringGrace.first().title.equals("No live event right now", ignoreCase = true))

        val epgAfterGrace = com.olympus.iptv.playlist.DummyEpg.programs(raw, thirtyAfter)
        assertTrue("DummyEpg should show 'No live event right now' at 30m post-end", epgAfterGrace.first().title.equals("No live event right now", ignoreCase = true))
    }

    @Test
    fun notLivePlaceholderChannelsAreAlwaysHidden() {
        val now = System.currentTimeMillis()
        val offAir = listOf(
            "ESPN+ 12: NO EVENT",
            "DAZN 04 | Off Air",
            "PPV 01: No Broadcasting",
            "Sky Sport 07: Sendepause"
        )
        for (raw in offAir) {
            val status = NameStrip.inspect(raw)
            assertFalse("Expected placeholder $raw to be hidden", status.isVisibleAt(now))
        }
    }
}
