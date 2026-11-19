package com.olympus.iptv.ui.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTraceTest {
    @Test
    fun streamHintRedactsXtreamCredentials() {
        val hint = PlaybackTrace.streamHint("http://cdn.example.com:8080/live/user/secret/1234.ts")
        assertTrue(hint.contains("cdn.example.com"))
        assertTrue(hint.contains("/live/***/***/"))
        assertFalse(hint.contains("secret"))
        assertFalse(hint.contains("/user/"))
    }

    @Test
    fun streamHintHandlesBlank() {
        assertEquals("url=-", PlaybackTrace.streamHint(null))
        assertEquals("url=-", PlaybackTrace.streamHint("  "))
    }

    @Test
    fun likelyCauseMapsIoToProvider() {
        assertEquals(
            "likely provider/network IO",
            PlaybackTrace.likelyCause(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )
    }

    @Test
    fun likelyCauseMapsDecoderToApp() {
        assertEquals(
            "likely device/app decoder",
            PlaybackTrace.likelyCause(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        )
    }

    @Test
    fun likelyCauseMapsBehindLiveWindow() {
        assertEquals(
            "live window (often provider lag)",
            PlaybackTrace.likelyCause(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
        )
    }
}
