package com.olympus.iptv.vpn

import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.util.encoders.Base64

internal object ProtonClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private const val ROOT = "https://account.proton.me/api"
    private const val AGENT = "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun signIn(username: String, password: String, code: String): VpnAccount {
        val session = Session()
        val http = VpnHttp.client.newBuilder().cookieJar(session).build()
        call(http, session, "https://account.proton.me/vpn", null)
        val opened = call(http, session, "$ROOT/auth/v4/sessions", ByteArray(0).toRequestBody(null))
        val openJson = json(opened)
        session.uid = jsonText(openJson.get("UID")).orEmpty()
        session.bearer = jsonText(openJson.get("AccessToken")).orEmpty()
        session.bearerType = jsonText(openJson.get("TokenType")).orEmpty().ifBlank { "Bearer" }
        val refresh = jsonText(openJson.get("RefreshToken")).orEmpty()
        if (session.uid.isBlank() || refresh.isBlank()) throw VpnAuthException("Proton could not start the sign-in.")
        val state = buildString(24) {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val random = SecureRandom()
            repeat(24) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
        call(
            http,
            session,
            "$ROOT/core/v4/auth/cookies",
            """{"GrantType":"refresh_token","Persistent":0,"RedirectURI":"https://protonmail.com","RefreshToken":${quote(refresh)},"ResponseType":"token","State":${quote(state)},"UID":${quote(session.uid)}}""".toRequestBody(jsonType)
        )
        val info = json(call(http, session, "$ROOT/core/v4/auth/info", """{"Intent":"Proton","Username":${quote(username)}}""".toRequestBody(jsonType)))
        val proof = protonProof(
            jsonText(info.get("Version"))?.toIntOrNull() ?: 4,
            password,
            jsonText(info.get("Salt")).orEmpty(),
            jsonText(info.get("Modulus")).orEmpty(),
            jsonText(info.get("ServerEphemeral")).orEmpty()
        )
        val authText = call(
            http,
            session,
            "$ROOT/core/v4/auth",
            """{"ClientEphemeral":${quote(proof.clientEphemeral)},"ClientProof":${quote(proof.clientProof)},"SRPSession":${quote(jsonText(info.get("SRPSession")).orEmpty())},"Username":${quote(username)}}""".toRequestBody(jsonType)
        )
        val auth = json(authText)
        val serverProof = Base64.decode(jsonText(auth.get("ServerProof")).orEmpty())
        if (!MessageDigest.isEqual(serverProof, proof.expectedServerProof)) throw VpnAuthException("Proton did not accept that username and password.")
        val twoFactor = (jsonText(auth.get("TwoFactor"))?.toIntOrNull() ?: 0) or
            (jsonText(auth.getAsJsonObject("2FA")?.get("Enabled"))?.toIntOrNull() ?: 0)
        if (twoFactor != 0) {
            if (code.isBlank()) throw VpnAuthException("Proton needs the authenticator code.")
            json(call(http, session, "$ROOT/core/v4/auth/2fa", """{"TwoFactorCode":${quote(code)}}""".toRequestBody(jsonType)))
        } else {
            val scopes = auth.getAsJsonArray("Scopes")
            if (scopes != null && scopes.none { jsonText(it) == "vpn" }) throw VpnAuthException("That Proton account does not include VPN.")
        }
        val listed = json(call(http, session, "$ROOT/vpn/v1/logicals", null)).getAsJsonArray("LogicalServers")
            ?: throw VpnAuthException("Proton did not send a city list.")
        val best = HashMap<String, Server>()
        for (item in listed) {
            val row = item.asJsonObject
            if ((jsonText(row.get("Status"))?.toIntOrNull() ?: 0) != 1) continue
            val city = jsonText(row.get("City")) ?: continue
            val country = jsonText(row.get("ExitCountry")) ?: jsonText(row.get("EntryCountry")).orEmpty()
            val name = if (country.isBlank()) city else "$city, $country"
            val load = jsonText(row.get("Load"))?.toIntOrNull() ?: 100
            val servers = row.getAsJsonArray("Servers") ?: continue
            for (server in servers) {
                val node = server.asJsonObject
                if ((jsonText(node.get("Status"))?.toIntOrNull() ?: 1) != 1) continue
                val ip = jsonText(node.get("EntryIP")) ?: continue
                if (!ip.contains('.')) continue
                val key = jsonText(node.get("X25519PublicKey")) ?: continue
                val current = best[name]
                if (current == null || load < current.load) best[name] = Server(name, ip, key, load)
            }
        }
        val cities = best.values.sortedBy { it.name.lowercase() }.map { VpnCity(it.name, it.name) }
        if (cities.isEmpty()) throw VpnAuthException("Proton did not send a city list.")
        val keys = WgKeys.generate()
        val privateKey = WgKeys.text(keys.first)
        val publicKey = WgKeys.text(keys.second)
        return VpnAccount(cities) { cityId ->
            val server = best[cityId] ?: throw VpnAuthException("That city is no longer available.")
            runCatching {
                call(
                    http,
                    session,
                    "$ROOT/vpn/v1/certificate",
                    """{"ClientPublicKey":${quote(publicKey)},"Mode":"persistent","DeviceName":"Olympus IPTV"}""".toRequestBody(jsonType)
                )
            }
            TunnelConfig(
                provider = "proton",
                label = server.name,
                privateKey = privateKey,
                address = "10.2.0.2/32",
                dns = listOf("10.2.0.1"),
                peerKey = server.key,
                presharedKey = null,
                endpointHost = server.ip,
                endpointPort = 51820
            )
        }
    }

    private fun call(http: okhttp3.OkHttpClient, session: Session, url: String, body: RequestBody?): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", AGENT)
            .header("x-pm-appversion", "android-vpn@5.13.0")
            .header("x-pm-locale", "en_US")
        if (session.uid.isNotBlank()) builder.header("x-pm-uid", session.uid)
        val cookie = session.header()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        if (session.bearer.isNotBlank()) builder.header("Authorization", session.bearerType + " " + session.bearer)
        if (body == null) builder.get() else builder.post(body)
        val result = http.newCall(builder.build()).execute().use { response -> response.code to VpnHttp.text(response) }
        if (result.first == 422 && result.second.contains("9001")) {
            throw VpnAuthException("Proton needs a browser check on this account before sign-in can continue.")
        }
        if (result.first !in 200..299) throw VpnAuthException(VpnHttp.explain(result.first, result.second))
        val code = runCatching { jsonText(JsonParser.parseString(result.second).asJsonObject.get("Code"))?.toIntOrNull() }.getOrNull()
        if (code != null && code != 1000) throw VpnAuthException(VpnHttp.explain(result.first, result.second))
        return result.second
    }

    private fun json(body: String) = JsonParser.parseString(body).asJsonObject

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private class Server(val name: String, val ip: String, val key: String, val load: Int)

    private class Session : CookieJar {
        var sessionId = ""
        var uid = ""
        var token = ""
        var bearer = ""
        var bearerType = "Bearer"

        fun header(): String {
            val parts = ArrayList<String>()
            if (token.isNotBlank() && uid.isNotBlank()) parts += "AUTH-$uid=$token"
            if (sessionId.isNotBlank()) parts += "Session-Id=$sessionId"
            if (token.isNotBlank()) parts += "Tag=default; iaas=W10; Domain=proton.me; Feature=VPNDashboard:A"
            return parts.joinToString("; ")
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                if (cookie.name == "Session-Id") sessionId = cookie.value
                if (uid.isNotBlank() && cookie.name == "AUTH-$uid") token = cookie.value
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }
}
