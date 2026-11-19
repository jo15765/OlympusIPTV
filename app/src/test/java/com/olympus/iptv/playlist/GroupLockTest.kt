package com.olympus.iptv.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupLockTest {
    @Test
    fun codeMatchesTheDashboard() {
        assertEquals("fdc422fd", GroupLock.code("1234"))
    }
}
