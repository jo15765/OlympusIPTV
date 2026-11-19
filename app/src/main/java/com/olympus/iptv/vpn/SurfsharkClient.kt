package com.olympus.iptv.vpn

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object SurfsharkClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun signIn(username: String, password: String): VpnAccount {
        val login = """{"username":${quote(username)},"password":${quote(password)}}"""
        val tokenText = post("https://api.surfshark.com/v1/auth/login", login, null)
        val token = runCatching { JsonParser.parseString(tokenText).asJsonObject.get("token")?.asString }.getOrNull()
        if (token.isNullOrBlank()) throw VpnAuthException("Surfshark did not accept that username and password.")
        val keys = WgKeys.generate()
        post("https://api.surfshark.com/v1/account/users/public-keys", """{"pubKey":${quote(WgKeys.text(keys.second))}}""", token)
        val list = VpnHttp.client.newCall(
            Request.Builder().url("https://api.surfshark.com/v4/server/clusters/generic").header("Authorization", "Bearer $token").get().build()
        ).execute().use { response ->
            val body = VpnHttp.text(response)
            if (!response.isSuccessful) throw VpnAuthException("Surfshark did not send a city list.")
            body
        }
        val best = HashMap<String, Server>()
        for (item in JsonParser.parseString(list).asJsonArray) {
            val row = item.asJsonObject
            val location = row.get("location")?.asString ?: continue
            val country = row.get("country")?.asString.orEmpty()
            val host = row.get("connectionName")?.asString ?: continue
            val peer = row.get("pubKey")?.asString ?: continue
            val load = row.get("load")?.asInt ?: 100
            val name = if (country.isBlank()) location else "$location, $country"
            val current = best[name]
            if (current == null || load < current.load) best[name] = Server(name, host, peer, load)
        }
        val cities = best.values.sortedBy { it.name.lowercase() }.map { VpnCity(it.name, it.name) }
        if (cities.isEmpty()) throw VpnAuthException("Surfshark did not send a city list.")
        val privateKey = WgKeys.text(keys.first)
        return VpnAccount(cities) { cityId ->
            val server = best[cityId] ?: throw VpnAuthException("That city is no longer available.")
            TunnelConfig(
                provider = "surfshark",
                label = server.name,
                privateKey = privateKey,
                address = "10.14.0.2/16",
                dns = listOf("162.252.172.57"),
                peerKey = server.peer,
                presharedKey = null,
                endpointHost = server.host,
                endpointPort = 51820
            )
        }
    }

    private fun post(url: String, body: String, token: String?): String {
        val builder = Request.Builder().url(url).post(body.toRequestBody(jsonType))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return VpnHttp.client.newCall(builder.build()).execute().use { response ->
            val text = VpnHttp.text(response)
            if (response.code == 409) return text
            if (!response.isSuccessful) throw VpnAuthException(VpnHttp.explain(response.code, text))
            text
        }
    }

    private fun quote(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private class Server(val name: String, val host: String, val peer: String, val load: Int)
}
