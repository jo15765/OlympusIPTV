package com.olympus.iptv.vpn

import com.google.gson.JsonParser
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object NordClient {
    fun signIn(username: String, password: String): VpnAccount {
        val privateKey = credentials(username, password)
        val countries = VpnHttp.client.newCall(
            Request.Builder().url("https://api.nordvpn.com/v1/servers/countries").get().build()
        ).execute().use { response ->
            val body = VpnHttp.text(response)
            if (!response.isSuccessful) throw VpnAuthException("NordVPN did not send a city list.")
            body
        }
        val cities = ArrayList<VpnCity>()
        for (item in JsonParser.parseString(countries).asJsonArray) {
            val country = item.asJsonObject
            val countryName = jsonText(country.get("name")) ?: continue
            val countryId = jsonText(country.get("id")) ?: continue
            val listed = country.getAsJsonArray("cities")
            if (listed == null || listed.size() == 0) {
                cities += VpnCity("country:$countryId", countryName)
                continue
            }
            for (city in listed) {
                val row = city.asJsonObject
                val id = jsonText(row.get("id")) ?: continue
                val name = jsonText(row.get("name")) ?: continue
                cities += VpnCity(id, "$name, $countryName")
            }
        }
        cities.sortBy { it.name.lowercase() }
        if (cities.isEmpty()) throw VpnAuthException("NordVPN did not send a city list.")
        return VpnAccount(cities) { cityId -> server(privateKey, cityId, cities.first { it.id == cityId }.name) }
    }

    private fun credentials(username: String, password: String): String {
        val posted = runCatching {
            val body = """{"username":${json(username)},"password":${json(password)}}"""
            VpnHttp.client.newCall(
                Request.Builder()
                    .url("https://api.nordvpn.com/v1/users/tokens")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { response ->
                val text = VpnHttp.text(response)
                if (!response.isSuccessful) null else JsonParser.parseString(text).asJsonObject.get("token")?.asString
            }
        }.getOrNull()
        val attempts = ArrayList<String>()
        if (!posted.isNullOrBlank()) attempts += Credentials.basic("token", posted)
        attempts += Credentials.basic("token", password)
        if (!username.equals("token", true)) attempts += Credentials.basic(username, password)
        var last = "NordVPN did not accept that username and password. An access token from Nord's manual setup can go in the password box."
        for (header in attempts) {
            val result = VpnHttp.client.newCall(
                Request.Builder().url("https://api.nordvpn.com/v1/users/services/credentials").header("Authorization", header).get().build()
            ).execute().use { response -> Triple(response.isSuccessful, response.code, VpnHttp.text(response)) }
            if (!result.first) {
                last = VpnHttp.explain(result.second, result.third)
                continue
            }
            val key = runCatching { jsonText(JsonParser.parseString(result.third).asJsonObject.get("nordlynx_private_key")) }.getOrNull()
            if (!key.isNullOrBlank()) {
                WgKeys.raw(key)
                return key
            }
        }
        throw VpnAuthException(last)
    }

    private fun server(privateKey: String, cityId: String, label: String): TunnelConfig {
        val url = "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
            .addQueryParameter(
                if (cityId.startsWith("country:")) "filters[country_id]" else "filters[city_id]",
                cityId.removePrefix("country:")
            )
            .addQueryParameter("filters[servers_technologies][identifier]", "wireguard_udp")
            .addQueryParameter("limit", "8")
            .build()
        val body = VpnHttp.client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val text = VpnHttp.text(response)
            if (!response.isSuccessful) throw VpnAuthException("NordVPN did not open that city.")
            text
        }
        val rows = JsonParser.parseString(body).asJsonArray
        var bestPeer = ""
        var bestHost = ""
        var bestLoad = Int.MAX_VALUE
        for (item in rows) {
            val row = item.asJsonObject
            val host = row.get("station")?.asString ?: continue
            val load = row.get("load")?.asInt ?: 100
            val technologies = row.getAsJsonArray("technologies") ?: continue
            var peer = ""
            for (technology in technologies) {
                val tech = technology.asJsonObject
                if (tech.get("identifier")?.asString != "wireguard_udp") continue
                val metadata = tech.getAsJsonArray("metadata") ?: continue
                for (entry in metadata) {
                    val pair = entry.asJsonObject
                    if (pair.get("name")?.asString == "public_key") peer = pair.get("value")?.asString.orEmpty()
                }
            }
            if (peer.isNotBlank() && load < bestLoad) {
                bestLoad = load
                bestPeer = peer
                bestHost = host
            }
        }
        if (bestPeer.isBlank() || bestHost.isBlank()) throw VpnAuthException("NordVPN has no WireGuard server in that city.")
        return TunnelConfig(
            provider = "nord",
            label = label,
            privateKey = privateKey,
            address = "10.5.0.2/32",
            dns = listOf("103.86.96.100", "103.86.99.100"),
            peerKey = bestPeer,
            presharedKey = null,
            endpointHost = bestHost,
            endpointPort = 51820
        )
    }

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
