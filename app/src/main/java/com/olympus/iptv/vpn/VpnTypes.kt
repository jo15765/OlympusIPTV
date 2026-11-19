package com.olympus.iptv.vpn

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response

internal class VpnAuthException(message: String) : Exception(message)

internal object VpnHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    fun text(response: Response): String = response.body?.string().orEmpty().take(20_000_000)

    fun explain(code: Int, body: String): String {
        val message = runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            listOf("Error", "error", "message", "Message").firstNotNullOfOrNull { key ->
                json.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()
            }
        }.getOrNull()?.replace('\n', ' ')?.take(160)
        return if (message.isNullOrBlank()) "The VPN service refused the sign-in ($code)." else message
    }
}

internal data class VpnCity(val id: String, val name: String)

internal data class TunnelConfig(
    val provider: String,
    val label: String,
    val privateKey: String,
    val address: String,
    val dns: List<String>,
    val peerKey: String,
    val presharedKey: String?,
    val endpointHost: String,
    val endpointPort: Int
)

internal fun jsonText(element: JsonElement?): String? {
    val value = element ?: return null
    if (value.isJsonNull || !value.isJsonPrimitive) return null
    val primitive = value.asJsonPrimitive
    return if (primitive.isNumber) primitive.asNumber.toString() else primitive.asString
}

internal class VpnAccount(
    val cities: List<VpnCity>,
    val config: (String) -> TunnelConfig
)

internal fun preferCity(cities: List<VpnCity>, cityId: String?): List<VpnCity> {
    if (cityId.isNullOrBlank()) return cities
    val index = cities.indexOfFirst { it.id == cityId }
    if (index <= 0) return cities
    val next = cities.toMutableList()
    next.add(0, next.removeAt(index))
    return next
}
