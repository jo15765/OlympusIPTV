package com.olympus.iptv.vpn

object LanRoutes {
    private val publicBlocks: List<Pair<Long, Int>> by lazy { buildBlocks() }
    private val publicRoutes: List<Pair<String, Int>> by lazy {
        publicBlocks.map { (addr, prefix) -> format(addr) to prefix }
    }

    fun publicV4(): List<Pair<String, Int>> = publicRoutes

    /** True when 10/8, 172.16/12, and 192.168/16 are absent from the VPN route table. */
    fun excludesRfc1918(): Boolean {
        val samples = listOf("10.1.2.3", "172.16.0.1", "172.31.255.9", "192.168.1.20")
        if (samples.any { !isPrivateV4(it) }) return false
        return samples.none { host -> routed(host) }
    }

    private fun routed(host: String): Boolean {
        val addr = parse(host) ?: return true
        for ((baseText, prefix) in publicRoutes) {
            val base = parse(baseText) ?: continue
            if (prefix <= 0) return true
            val shift = 32 - prefix
            if ((addr ushr shift) == (base ushr shift)) return true
        }
        return false
    }

    fun isPrivateV4(host: String): Boolean {
        val addr = parse(host) ?: return false
        return !contains(addr)
    }

    internal fun contains(addr: Long): Boolean {
        for ((base, prefix) in publicBlocks) {
            if (prefix <= 0) return true
            val shift = 32 - prefix
            if ((addr ushr shift) == (base ushr shift)) return true
        }
        return false
    }

    internal fun parse(host: String): Long? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        var addr = 0L
        for (part in parts) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            addr = (addr shl 8) or value.toLong()
        }
        return addr
    }

    private fun buildBlocks(): List<Pair<Long, Int>> {
        val blocked = arrayOf(
            0L to (1L shl 24),
            (10L shl 24) to (11L shl 24),
            (127L shl 24) to (128L shl 24),
            pack(169, 254, 0, 0) to pack(169, 255, 0, 0),
            pack(172, 16, 0, 0) to pack(172, 32, 0, 0),
            pack(192, 168, 0, 0) to pack(192, 169, 0, 0),
            pack(224, 0, 0, 0) to (1L shl 32)
        )
        val out = ArrayList<Pair<Long, Int>>()
        var cursor = 0L
        for ((start, end) in blocked) {
            if (start > cursor) out += span(cursor, start)
            if (end > cursor) cursor = end
        }
        if (cursor < 1L shl 32) out += span(cursor, 1L shl 32)
        return out
    }

    private fun span(start: Long, end: Long): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        var addr = start
        while (addr < end) {
            var size = if (addr == 0L) 1L shl 32 else addr and -addr
            while (size > 1L && addr + size > end) size = size shr 1
            if (size < 1L || addr + size > end) break
            val prefix = 32 - java.lang.Long.numberOfTrailingZeros(size)
            out += addr to prefix
            addr += size
        }
        return out
    }

    private fun pack(a: Int, b: Int, c: Int, d: Int): Long {
        return (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()
    }

    private fun format(addr: Long): String {
        return "${(addr shr 24) and 255}.${(addr shr 16) and 255}.${(addr shr 8) and 255}.${addr and 255}"
    }
}
