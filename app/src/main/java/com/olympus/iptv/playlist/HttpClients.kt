package com.olympus.iptv.playlist

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * One OkHttp stack for Xtream, TMDB, Watchmode, logos, and subtitles.
 * OkHttp 5.4.0 caps each HTTP/2 response's headers at 256 KiB.
 * Trailing-dot hosts are refused so a pin check cannot be skipped by a dotted hostname.
 */
object HttpClients {
    private const val CACHE_BYTES = 50L * 1024L * 1024L

    @Volatile private var cacheDir: File? = null

    fun install(context: Context) {
        cacheDir = File(context.applicationContext.cacheDir, "http-shared")
    }

    /** Resolve DNS and open a keep-alive socket for a few stream hosts before the first tune. */
    fun warm(hosts: List<String>) {
        val unique = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
        if (unique.isEmpty()) return
        Thread {
            val client = shared
            for (host in unique) {
                runCatching { InetAddress.getAllByName(host) }
                runCatching {
                    client.newCall(
                        Request.Builder().url("https://$host/").head().build()
                    ).execute().close()
                }
            }
        }.start()
    }

    /** Pre-resolves DNS and pre-opens HTTP/HTTPS keep-alive sockets for upcoming channel stream URLs. */
    fun warmStreamHosts(urls: List<String>) {
        val hosts = urls.mapNotNull { url ->
            runCatching { java.net.URI(url).host }.getOrNull()
        }.filter { it.isNotBlank() }
        warm(hosts)
    }

    /** Drops keep-alive sockets and in-flight calls so a network change does not wait out a read timeout. */
    fun evictConnections() {
        runCatching { shared.dispatcher.cancelAll() }
        runCatching { xtream.dispatcher.cancelAll() }
        runCatching { shared.connectionPool.evictAll() }
        runCatching { xtream.connectionPool.evictAll() }
    }

    val shared: OkHttpClient by lazy { buildShared() }

    /** Same pool and cache as [shared]. Playlist bodies may read for several minutes. */
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
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .cache(Cache(dir, CACHE_BYTES))
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
