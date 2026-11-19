package com.olympus.iptv.vpn

internal fun parseWireGuard(text: String): TunnelConfig {
    var section = ""
    var privateKey = ""
    var address = ""
    var dns = emptyList<String>()
    var peer = ""
    var preshared: String? = null
    var endpoint = ""
    for (raw in text.lines()) {
        val line = raw.substringBefore("#").trim()
        if (line.isEmpty()) continue
        if (line.startsWith("[")) {
            section = line.lowercase()
            continue
        }
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) continue
        val key = parts[0].trim().lowercase()
        val value = parts[1].trim()
        when (section) {
            "[interface]" -> when (key) {
                "privatekey" -> privateKey = value
                "address" -> if (address.isEmpty()) address = value.split(",").map { it.trim() }.firstOrNull { it.contains('.') }.orEmpty()
                "dns" -> dns = value.split(",").map { it.trim() }.filter { it.contains('.') }
            }
            "[peer]" -> when (key) {
                "publickey" -> if (peer.isEmpty()) peer = value
                "presharedkey" -> if (preshared == null) preshared = value
                "endpoint" -> if (endpoint.isEmpty()) endpoint = value
            }
        }
    }
    if (privateKey.isBlank() || peer.isBlank() || endpoint.isBlank() || address.isBlank()) {
        throw VpnAuthException("That WireGuard profile needs a private key, an address, a peer key, and an endpoint.")
    }
    try {
        WgKeys.raw(privateKey)
        WgKeys.raw(peer)
        if (preshared != null) WgKeys.raw(preshared)
    } catch (_: IllegalArgumentException) {
        throw VpnAuthException("That WireGuard profile has a key this app cannot read.")
    }
    val split = splitEndpoint(endpoint)
    return TunnelConfig(
        provider = "wireguard",
        label = split.first,
        privateKey = privateKey.trim(),
        address = address.substringBefore(",").trim(),
        dns = dns.ifEmpty { listOf("1.1.1.1") },
        peerKey = peer.trim(),
        presharedKey = preshared?.trim(),
        endpointHost = split.first,
        endpointPort = split.second
    )
}

private fun splitEndpoint(value: String): Pair<String, Int> {
    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        if (end > 1) {
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: 51820
            return host to port
        }
    }
    val colon = value.lastIndexOf(':')
    if (colon > 0 && value.substring(colon + 1).all { it.isDigit() }) {
        return value.substring(0, colon) to value.substring(colon + 1).toInt()
    }
    return value to 51820
}
