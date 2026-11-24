package com.olympus.iptv

import com.google.gson.Gson
import com.olympus.iptv.data.Recording
import com.olympus.iptv.data.RecordingBook
import com.olympus.iptv.data.SmbSettings
import com.olympus.iptv.remind.ShowReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun cloudDriveSettingsValidateProvidersAndRoundTrip() {
        val gdrive = com.olympus.iptv.data.CloudDriveSettings(
            provider = "GDRIVE",
            clientId = "test-client-id",
            refreshToken = "refresh-123",
            folder = "  ",
            accountLabel = "user@gmail.com",
            deleteLocalAfterUpload = true
        )
        assertTrue(gdrive.ready())
        assertEquals("gdrive", gdrive.normalizedProvider())
        assertEquals("Olympus-IPTV", gdrive.folderName())
        assertEquals("test-client-id", com.olympus.iptv.record.CloudDriveUploader.effectiveClientId("gdrive", gdrive.clientId))

        val disconnected = gdrive.copy(refreshToken = "")
        assertFalse(disconnected.ready())

        val book = RecordingBook(cloudDrive = gdrive, revision = 42L)
        val roundTripped = gson.fromJson(gson.toJson(book), RecordingBook::class.java)
        assertTrue(roundTripped.cloudDriveOrEmpty().ready())
        assertEquals("user@gmail.com", roundTripped.cloudDriveOrEmpty().accountLabel)
        assertEquals(true, roundTripped.cloudDriveOrEmpty().deleteLocalAfterUpload)
    }

    @Test
    fun proStateRoundTripsProfilesPlaybackAndDownloads() {
        val state = com.olympus.iptv.data.ProState(
            activeProfileId = "kids",
            profiles = listOf(
                com.olympus.iptv.data.HouseholdProfile(id = "default", name = "Main", avatar = "🎬"),
                com.olympus.iptv.data.HouseholdProfile(id = "kids", name = "Kids", avatar = "🧸", kidsMode = true)
            ),
            playback = com.olympus.iptv.data.PlaybackDefaults(
                preferredAudioLang = "eng",
                preferredSubtitleLang = "spa",
                subtitleScale = 1.25f,
                subtitleBgOpacity = 70,
                defaultVodSpeed = 1.5f,
                timeshiftMinutes = 10,
                statsOverlayEnabled = true,
                quadMultiViewEnabled = true,
                externalPlayerEnabled = true,
                externalPlayerPackage = "org.videolan.vlc",
                epgOffsetMinutes = -60,
                customXmltvUrl = "https://example.com/epg.xml",
                backupFailoverEnabled = true,
                spoilerFreeSports = true
            ),
            downloads = listOf(
                com.olympus.iptv.data.OfflineDownload(
                    id = "dl-1",
                    title = "Pilot Episode",
                    channelId = "ep-101",
                    streamUrl = "https://example.com/pilot.mp4",
                    fileName = "pilot.mp4",
                    bytes = 120_000_000L,
                    status = com.olympus.iptv.data.OfflineDownload.STATUS_DONE
                )
            )
        )
        val decoded = gson.fromJson(gson.toJson(state), com.olympus.iptv.data.ProState::class.java)
        val active = decoded.profiles.first { it.id == decoded.activeProfileId }
        assertEquals("kids", decoded.activeProfileId)
        assertEquals("Kids", active.name)
        assertTrue(active.kidsMode)
        assertEquals("eng", decoded.playback.preferredAudioLang)
        assertEquals(1.25f, decoded.playback.subtitleScale, 0.001f)
        assertEquals(10, decoded.playback.timeshiftMinutes)
        assertTrue(decoded.playback.spoilerFreeSports)
        assertEquals(1, decoded.downloads.size)
        assertEquals("pilot.mp4", decoded.downloads.first().fileName)
    }
}
