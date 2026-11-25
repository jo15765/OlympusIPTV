package com.olympus.iptv.playlist

import android.content.Context
import com.olympus.iptv.AppLog
import com.olympus.iptv.BuildConfig
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object PinAlert {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val lastSentAt = AtomicLong(0L)
    private const val COOLDOWN_MS = 15L * 60L * 1000L

    fun configured(): Boolean =
        BuildConfig.MAILGUN_API_KEY.isNotBlank() &&
            BuildConfig.MAILGUN_DOMAIN.isNotBlank() &&
            BuildConfig.MAILGUN_FROM_EMAIL.isNotBlank()

    fun notifyFailedUnlock(context: Context, email: String, deviceId: String) {
        if (!configured()) return
        val to = email.trim()
        if (to.isBlank() || !to.contains("@")) return
        val now = System.currentTimeMillis()
        val last = lastSentAt.get()
        if (now - last < COOLDOWN_MS) return
        if (!lastSentAt.compareAndSet(last, now)) return
        val domain = BuildConfig.MAILGUN_DOMAIN.trim()
        val from = BuildConfig.MAILGUN_FROM_EMAIL.trim()
        val device = deviceId.ifBlank { "device" }
        val text = "Someone entered an incorrect parental PIN twice on $device. " +
            "If this was not you, change the group PIN on that device."
        val formBody = FormBody.Builder()
            .add("from", "Olympus IPTV <$from>")
            .add("to", to)
            .add("subject", "Parental PIN failed twice")
            .add("text", text)
            .build()
        Thread {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.mailgun.net/v3/$domain/messages")
                    .header("Authorization", Credentials.basic("api", BuildConfig.MAILGUN_API_KEY.trim()))
                    .post(formBody)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLog.error("PinAlert", "Mailgun HTTP ${response.code}")
                        lastSentAt.set(0L)
                    }
                }
            }.onFailure { error ->
                AppLog.error("PinAlert", "Mailgun send failed", error)
                lastSentAt.set(0L)
            }
        }.start()
    }
}
