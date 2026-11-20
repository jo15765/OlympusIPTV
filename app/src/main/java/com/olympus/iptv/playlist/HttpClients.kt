package com.olympus.iptv.playlist

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.io.IOException
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

    val shared: OkHttpClient by lazy { buildShared() }

    /** Same pool and cache as [shared]. Playlist bodies may read for several minutes. */
    val xtream: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
            .build()
    }

    val scrobble: OkHttpClient by lazy {
        shared.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun buildShared(): OkHttpClient {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir"), "olympus-http")
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
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
