package com.olympus.iptv.playlist

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object HttpClients {
    private const val CACHE_BYTES = 50L * 1024L * 1024L
    private const val CACHE_BYTES_LOW_RAM = 20L * 1024L * 1024L
    private const val DNS_TTL_MS = 60_000L

    private data class DnsEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val dnsCache = ConcurrentHashMap<String, DnsEntry>()

    private val fastDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            val hit = dnsCache[hostname]
            if (hit != null && hit.expiresAt > now) return hit.addresses
            val resolved = Dns.SYSTEM.lookup(hostname)
            val ordered = if (resolved.size > 1) {
                resolved.sortedBy { if (it is Inet4Address) 0 else 1 }
            } else {
                resolved
            }
            if (dnsCache.size > 128) dnsCache.clear()
            dnsCache[hostname] = DnsEntry(ordered, now + DNS_TTL_MS)
            return ordered
        }
    }

    @Volatile private var cacheDir: File? = null
    @Volatile private var lowRamDevice = false

    fun install(context: Context) {
        val app = context.applicationContext
        cacheDir = File(app.cacheDir, "http-shared")
        lowRamDevice = com.olympus.iptv.ui.DeviceForm.isLowRam(app)
    }

    fun warm(hosts: List<String>) {
        val unique = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
        if (unique.isEmpty()) return
        Thread {
            val client = shared
            for (host in unique) {
                runCatching { fastDns.lookup(host) }
                runCatching {
                    client.newCall(
                        Request.Builder().url("https://$host/").head().build()
                    ).execute().close()
                }
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }.start()
    }

    fun warmStreamHosts(urls: List<String>) {
        val hosts = urls.mapNotNull { url ->
            runCatching { java.net.URI(url).host }.getOrNull()
        }.filter { it.isNotBlank() }
        warm(hosts)
    }

    fun evictConnections() {
        dnsCache.clear()
        runCatching { shared.dispatcher.cancelAll() }
        runCatching { xtream.dispatcher.cancelAll() }
        runCatching { shared.connectionPool.evictAll() }
        runCatching { xtream.connectionPool.evictAll() }
    }

    val shared: OkHttpClient by lazy { buildShared() }

    val xtream: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
            .build()
    }

    val scrobble: OkHttpClient by lazy {
        shared.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    val streamDataSourceFactory: androidx.media3.datasource.DefaultHttpDataSource.Factory by lazy {
        androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Olympus-IPTV")
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
    }

    private fun buildShared(): OkHttpClient {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir"), "olympus-http")
        val maxCache = if (lowRamDevice) CACHE_BYTES_LOW_RAM else CACHE_BYTES
        val poolSize = if (lowRamDevice) 8 else 16
        return OkHttpClient.Builder()
            .dns(fastDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(poolSize, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .cache(Cache(dir, maxCache))
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val host = chain.request().url.host
                if (host.endsWith(".")) {
                    throw IOException("Refusing trailing-dot host")
                }
                chain.proceed(chain.request())
            }
            .build()
    }
}
