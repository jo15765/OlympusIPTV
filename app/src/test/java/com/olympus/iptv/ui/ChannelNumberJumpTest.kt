package com.olympus.iptv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNumberJumpTest {
    @Test
    fun digitsAndTuneIndex() {
        assertEquals(2, ChannelNumberJump.digit(android.view.KeyEvent.KEYCODE_2))
        assertEquals(7, ChannelNumberJump.digit(android.view.KeyEvent.KEYCODE_NUMPAD_7))
        assertEquals(-1, ChannelNumberJump.digit(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("204", ChannelNumberJump.append("20", 4))
        assertEquals("9", ChannelNumberJump.append("2041", 9))
        assertEquals(1, ChannelNumberJump.indexFor(listOf(10, 204, null), 204))
        assertEquals(-1, ChannelNumberJump.indexFor(listOf(10, 204), 3, allowRowFallback = false))
        assertEquals(2, ChannelNumberJump.indexFor(listOf(10, 204, null), 3))
    }

    @Test
    fun prefetchesTheNextSix() {
        val urls = ImagePrefetcher.ahead(listOf("a", null, "c", "d", "e", "f", "g", "h"), 1)
        assertEquals(listOf("c", "d", "e", "f", "g"), urls)
    }
}
