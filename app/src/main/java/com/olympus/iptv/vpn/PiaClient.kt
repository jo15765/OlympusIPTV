package com.olympus.iptv.vpn

import android.content.Context
import com.google.gson.JsonParser
import com.olympus.iptv.R
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Request

internal object PiaClient {
    fun signIn(context: Context, username: String, password: String): VpnAccount {
        val tokenBody = FormBody.Builder().add("username", username).add("password", password).build()
        val tokenCall = Request.Builder()
            .url("https://www.privateinternetaccess.com/api/client/v2/token")
            .post(tokenBody)
            .build()
        val tokenText = VpnHttp.client.newCall(tokenCall).execute().use { response ->
            val body = VpnHttp.text(response)
            if (!response.isSuccessful) throw VpnAuthException(VpnHttp.explain(response.code, body))
            body
        }
        val token = runCatching { JsonParser.parseString(tokenText).asJsonObject.get("token")?.asString }.getOrNull()
        if (token.isNullOrBlank()) throw VpnAuthException("PIA did not accept that username and password.")
        val listCall = Request.Builder().url("https://serverlist.piaservers.net/vpninfo/servers/v6").get().build()
        val listText = VpnHttp.client.newCall(listCall).execute().use { response ->
            val body = VpnHttp.text(response)
            if (!response.isSuccessful) throw VpnAuthException("PIA did not send a city list.")
            body
        }
        val json = listText.substringBefore("-----BEGIN")
        val regions = JsonParser.parseString(json).asJsonObject.getAsJsonArray("regions")
        val cities = ArrayList<VpnCity>()
        val lookup = HashMap<String, Pair<String, String>>()
        for (item in regions) {
            val region = item.asJsonObject
            val servers = region.getAsJsonObject("servers")?.getAsJsonArray("wg") ?: continue
            if (servers.size() == 0) continue
            val server = servers[0].asJsonObject
            val ip = server.get("ip")?.asString ?: continue
            val cn = server.get("cn")?.asString ?: continue
            val id = region.get("id")?.asString ?: continue
            val name = region.get("name")?.asString?.ifBlank { id } ?: id
            cities += VpnCity(id, name)
            lookup[id] = ip to cn
        }
        cities.sortBy { it.name.lowercase() }
        if (cities.isEmpty()) throw VpnAuthException("PIA did not send a city list.")
        return VpnAccount(cities) { cityId ->
            val picked = lookup[cityId] ?: throw VpnAuthException("That city is no longer available.")
            register(context, token, picked.first, picked.second, cities.first { it.id == cityId }.name)
        }
    }

    private fun register(context: Context, token: String, ip: String, host: String, label: String): TunnelConfig {
        val keys = WgKeys.generate()
        val publicKey = WgKeys.text(keys.second)
        val factory = CertificateFactory.getInstance("X.509")
        val certificate = context.resources.openRawResource(R.raw.pia_ca).use { factory.generateCertificate(it) as X509Certificate }
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        store.load(null, null)
        store.setCertificateEntry("pia", certificate)
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        val manager = trust.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(manager), SecureRandom())
        val http = VpnHttp.client.newBuilder()
            .sslSocketFactory(ssl.socketFactory, manager)
            .hostnameVerifier { name, _ -> name.equals(host, true) }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname.equals(host, true)) return listOf(InetAddress.getByName(ip))
                    return Dns.SYSTEM.lookup(hostname)
                }
            })
            .build()
        val url = "https://$host:1337/addKey?pt=${java.net.URLEncoder.encode(token, "UTF-8")}&pubkey=${java.net.URLEncoder.encode(publicKey, "UTF-8")}"
        val body = http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val text = VpnHttp.text(response)
            if (!response.isSuccessful) throw VpnAuthException("PIA did not open that city.")
            text
        }
        val json = JsonParser.parseString(body).asJsonObject
        if (json.get("status")?.asString != "OK") throw VpnAuthException("PIA did not open that city.")
        val dns = json.getAsJsonArray("dns_servers")?.map { it.asString }?.ifEmpty { null } ?: listOf("10.0.0.243")
        return TunnelConfig(
            provider = "pia",
            label = label,
            privateKey = WgKeys.text(keys.first),
            address = json.get("peer_ip").asString + "/32",
            dns = dns,
            peerKey = json.get("server_key").asString,
            presharedKey = null,
            endpointHost = json.get("server_ip")?.asString ?: ip,
            endpointPort = json.get("server_port")?.asInt ?: 1337
        )
    }
}
