package com.olympus.iptv.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnCoreTest {
    @Test
    fun publicRoutesSkipTheHomeNetwork() {
        val covered = arrayOf(
            "1.1.1.1",
            "8.8.8.8",
            "11.0.0.1",
            "172.15.255.255",
            "172.32.0.1",
            "192.167.255.255",
            "192.169.0.1",
            "126.0.0.1",
            "128.0.0.1"
        )
        val local = arrayOf(
            "192.168.1.20",
            "10.0.0.5",
            "172.16.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "224.0.0.1",
            "255.255.255.255",
            "0.0.0.0"
        )
        for (host in covered) assertTrue(host, LanRoutes.contains(LanRoutes.parse(host)!!))
        for (host in local) assertFalse(host, LanRoutes.contains(LanRoutes.parse(host)!!))
    }

    @Test
    fun bcryptMatchesTheCanonicalVector() {
        val hashed = bcryptForTest("a", "$" + "2a$" + "10$" + "k87L/MF28Q673VKh8/cPi.", 10)
            .replace("$" + "2y$", "$" + "2a$")
        assertEquals("$" + "2a$" + "10$" + "k87L/MF28Q673VKh8/cPi.SUl7MU/rWuSiIDDFayrKk/1tBsSQu4u", hashed)
    }

    @Test
    fun handshakeSealsBothWaysAndRejectsAReplay() {
        val clientKeys = WgKeys.generate()
        val serverKeys = WgKeys.generate()
        val client = WgHandshake(clientKeys.first, serverKeys.second, ByteArray(32))
        val initiation = client.initiation()
        val server = WgHandshake.respond(serverKeys.first, initiation, ByteArray(32))
        assertNotNull(server)
        assertTrue(client.consumeResponse(server!!.wirePacket()))
        val toServer = "live".toByteArray()
        val sealed = client.sealPacket(toServer)
        assertNotNull(sealed)
        assertArrayEquals(toServer, server.openPacket(sealed!!))
        val toClient = "ok".toByteArray()
        val reply = server.sealPacket(toClient)
        assertNotNull(reply)
        assertArrayEquals(toClient, client.openPacket(reply!!))
        assertNull(client.openPacket(reply))
        val fresh = WgHandshake(clientKeys.first, serverKeys.second, ByteArray(32))
        fresh.initiation()
        val still = client.sealPacket("still".toByteArray())
        assertNotNull(still)
        assertArrayEquals("still".toByteArray(), server.openPacket(still!!))
    }
}
