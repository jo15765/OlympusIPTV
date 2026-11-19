package com.olympus.iptv.playlist

import android.content.Context
import com.olympus.iptv.R
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object PortalTls {
    @Volatile
    private var cached: OkHttpClient? = null

    fun client(context: Context): OkHttpClient {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(context.applicationContext, 60, 60).also { cached = it }
        }
    }

    fun uploadClient(context: Context): OkHttpClient {
        return build(context.applicationContext, readSeconds = 120, writeSeconds = 0)
    }

    private fun build(context: Context, readSeconds: Long, writeSeconds: Long): OkHttpClient {
        val certificate = context.resources.openRawResource(R.raw.olympus_portal).use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        store.setCertificateEntry("olympus-iptv-portal", certificate)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeSeconds, TimeUnit.SECONDS)
            .build()
    }
}
