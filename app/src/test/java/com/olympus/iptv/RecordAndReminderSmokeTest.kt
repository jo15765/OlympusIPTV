package com.olympus.iptv

import com.google.gson.Gson
import com.olympus.iptv.data.Recording
import com.olympus.iptv.data.RecordingBook
import com.olympus.iptv.data.SmbSettings
import com.olympus.iptv.remind.ShowReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordAndReminderSmokeTest {

    private val gson = Gson()

    @Test
    fun recordingBookRoundTripsScheduledRecordingAndSmb() {
        val now = 1_760_000_000_000L
        val rec = Recording(
            id = "rec-1",
            channelId = "101",
            channelName = "ESPN HD",
            title = "Monday Night Game",
            startMillis = now + 60_000L,
            endMillis = now + 7_260_000L,
            destination = Recording.LOCAL,
            status = Recording.SCHEDULED
        )
        assertTrue(rec.windowStart() < rec.start())
        assertTrue(rec.windowEnd() > rec.end())
        val book = RecordingBook(
            items = listOf(rec),
            smb = SmbSettings(host = "192.168.1.20", share = "Media", folder = "Olympus-IPTV"),
            revision = now
        )
        val decoded = gson.fromJson(gson.toJson(book), RecordingBook::class.java)
        assertEquals(1, decoded.itemsOrEmpty().size)
        assertEquals("rec-1", decoded.itemsOrEmpty().first().id)
        assertEquals(Recording.SCHEDULED, decoded.itemsOrEmpty().first().status)
        assertEquals("192.168.1.20", decoded.smb?.host)
        assertEquals(now, decoded.revisionOrZero())
    }

    @Test
    fun showReminderCalculatesLeadTimeAndRoundTrips() {
        val start = 1_760_000_900_000L
        val lead = 15
        val reminder = ShowReminder().apply {
            id = "rem-1"
            playlistId = "pl-1"
            channelId = "202"
            channelName = "FOX"
            channelNumber = 5
            title = "Evening News"
            startMillis = start
            leadMinutes = lead
            fireAt = start - lead * 60_000L
        }
        val decoded = gson.fromJson(gson.toJson(reminder), ShowReminder::class.java)
        assertEquals("rem-1", decoded.id)
        assertEquals("Evening News", decoded.title)
        assertEquals(15, decoded.leadMinutes)
        assertEquals(start - 900_000L, decoded.fireAt)
        assertTrue((decoded.fireAt ?: 0L) < (decoded.startMillis ?: 0L))
    }
}
