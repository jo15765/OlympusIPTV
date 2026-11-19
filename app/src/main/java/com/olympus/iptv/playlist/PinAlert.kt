package com.olympus.iptv.playlist

import android.content.Context
import com.olympus.iptv.AppLog
import com.olympus.iptv.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional parental PIN-fail email via SendGrid.
 * Set `sendgrid.apiKey` and `sendgrid.fromEmail` in local.properties (verified sender).
 */
object PinAlert {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val lastSentAt = AtomicLong(0L)
    private const val COOLDOWN_MS = 15L * 60L * 1000L
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun configured(): Boolean =
        BuildConfig.SENDGRID_API_KEY.isNotBlank() && BuildConfig.SENDGRID_FROM_EMAIL.isNotBlank()

    fun notifyFailedUnlock(context: Context, email: String, deviceId: String) {
        if (!configured()) return
        val to = email.trim()
        if (to.isBlank() || !to.contains("@")) return
        val now = System.currentTimeMillis()
        val last = lastSentAt.get()
        if (now - last < COOLDOWN_MS) return
        if (!lastSentAt.compareAndSet(last, now)) return
        val from = BuildConfig.SENDGRID_FROM_EMAIL.trim()
        val device = deviceId.ifBlank { "device" }
        val body = """
            {
              "personalizations":[{"to":[{"email":${jsonString(to)}}]}],
              "from":{"email":${jsonString(from)},"name":"Olympus IPTV"},
              "subject":"Parental PIN failed twice",
              "content":[{
                "type":"text/plain",
                "value":${jsonString(
                    "Someone entered an incorrect parental PIN twice on $device. " +
                        "If this was not you, change the group PIN on that device."
                )}
              }]
            }
        """.trimIndent()
        Thread {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.sendgrid.com/v3/mail/send")
                    .header("Authorization", "Bearer ${BuildConfig.SENDGRID_API_KEY}")
                    .post(body.toRequestBody(jsonType))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLog.error("PinAlert", "SendGrid HTTP ${response.code}")
                        lastSentAt.set(0L)
                    }
                }
            }.onFailure { error ->
                AppLog.error("PinAlert", "SendGrid send failed", error)
                lastSentAt.set(0L)
            }
        }.start()
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
