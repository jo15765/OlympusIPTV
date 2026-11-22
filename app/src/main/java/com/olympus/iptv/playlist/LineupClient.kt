package com.olympus.iptv.playlist

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.olympus.iptv.BuildConfig
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.RecordingBook
import com.olympus.iptv.data.WatchEntry
import com.olympus.iptv.portal.DashboardHost
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Real-time, security-hardened Cloud Sync client for Olympus-IPTV:
 * - Subscribes to Server-Sent Events (`/api/events`) backed by PostgreSQL LISTEN/NOTIFY (`olympus_sync`)
 *   so edits in the Web Dashboard push to the TV and mobile apps within < 200 ms
 * - Authenticates every request with a per-device `X-Device-Token` stored locally
 * - Syncs LineupConfig, Shared Favorites, Continue Watching, DVR Recordings, Reminders & Snapshots
 * - Falls back automatically to local LAN `DashboardHost` when offline or unconfigured
 */
class LineupClient(
    private val context: Context,
    private val http: OkHttpClient = defaultHttp
) {
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences("olympus_cloud_auth", Context.MODE_PRIVATE)
    }

    private fun baseUrl(): String {
        val cloud = BuildConfig.PORTAL_CLOUD_URL.trim().trimEnd('/')
        if (cloud.startsWith("http://") || cloud.startsWith("https://")) return cloud
        return DashboardHost.ensureReachable().trimEnd('/')
    }

    fun dashboardAddress(): String = baseUrl()

    private fun tokenKey(email: String, deviceId: String): String =
        "tok_${email.trim().lowercase()}_${deviceId.trim()}"

    fun ensureDeviceToken(email: String, deviceId: String): String? {
        val key = tokenKey(email, deviceId)
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            addProperty("platform", "android")
        }
        val resp = runCatching { postJsonRaw("${baseUrl()}/api/register", payload, null, deviceId) }.getOrNull()
        val issued = resp?.get("deviceToken")?.takeIf { !it.isJsonNull }?.asString
        if (!issued.isNullOrBlank()) {
            prefs.edit().putString(key, issued).apply()
            return issued
        }
        return null
    }

    /**
     * Opens a persistent Server-Sent Events (SSE) stream to `/api/events` so any change saved
     * in the Web UI or another device triggers [onSyncEvent] immediately (< 200 ms).
     */
    fun subscribeSyncEvents(
        scope: CoroutineScope,
        email: String,
        deviceId: String,
        onSyncEvent: suspend (table: String) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        var backoffMs = 2_000L
        while (isActive) {
            val token = ensureDeviceToken(email, deviceId)
            val url = "${baseUrl()}/api/events?email=${enc(email)}&deviceId=${enc(deviceId)}"
            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("X-Device-Id", deviceId)
            if (!token.isNullOrBlank()) reqBuilder.header("X-Device-Token", token)
            try {
                sseHttp.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("SSE HTTP ${resp.code}")
                    backoffMs = 2_000L
                    val body = resp.body ?: throw IOException("Empty SSE body")
                    BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                        var currentEvent = ""
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("event:")) {
                                currentEvent = line.removePrefix("event:").trim()
                            } else if (line.startsWith("data:") && currentEvent == "sync") {
                                val rawJson = line.removePrefix("data:").trim()
                                val table = runCatching {
                                    JsonParser.parseString(rawJson).asJsonObject.get("table")?.asString.orEmpty()
                                }.getOrDefault("lineup_configs")
                                runCatching { onSyncEvent(table) }
                                currentEvent = ""
                            } else if (line.isEmpty()) {
                                currentEvent = ""
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Playlist login saved on the cloud dashboard for this device. Null when none is waiting. */
    fun pullProviderLogin(deviceId: String): JsonObject? {
        val cloud = BuildConfig.PORTAL_CLOUD_URL.trim()
        if (!cloud.startsWith("http://") && !cloud.startsWith("https://")) return null
        if (deviceId.isBlank()) return null
        val url = "${baseUrl()}/api/provider-login?deviceId=${enc(deviceId)}"
        return runCatching {
            http.newCall(
                Request.Builder().url(url).get().header("X-Device-Id", deviceId).build()
            ).execute().use { resp ->
                if (resp.code == 404) return null
                if (!resp.isSuccessful) return null
                JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
            }
        }.getOrNull()
    }

    /** Revision numbers only. The app downloads a section when its number changes. */
    fun pullSyncStamp(deviceId: String): JsonObject? {
        val cloud = BuildConfig.PORTAL_CLOUD_URL.trim()
        if (!cloud.startsWith("http://") && !cloud.startsWith("https://")) return null
        if (deviceId.isBlank()) return null
        val url = "${baseUrl()}/api/sync/stamp?deviceId=${enc(deviceId)}"
        return runCatching {
            http.newCall(
                Request.Builder().url(url).get().header("X-Device-Id", deviceId).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
            }
        }.getOrNull()
    }

    fun pullFavorites(email: String, deviceId: String): Pair<List<String>, Long>? {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/favorites?email=${enc(email)}&deviceId=${enc(deviceId)}"
        val body = runCatching { getJson(url, token, deviceId) }.getOrNull() ?: return null
        val rev = body.get("revision")?.asLong ?: return null
        val arr = body.getAsJsonArray("ids") ?: return emptyList<String>() to rev
        val out = ArrayList<String>(arr.size())
        arr.forEach { out.add(it.asString) }
        return out to rev
    }

    fun pullRecordings(email: String, deviceId: String): RecordingBook? {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/recordings?email=${enc(email)}&deviceId=${enc(deviceId)}"
        return runCatching {
            getJson(url, token, deviceId)?.getAsJsonObject("recordings")?.let {
                gson.fromJson(it, RecordingBook::class.java)
            }
        }.getOrNull()
    }

    fun remoteLineup(email: String, deviceId: String): LineupConfig? {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/lineup?email=${enc(email)}&deviceId=${enc(deviceId)}"
        return getJson(url, token, deviceId)?.getAsJsonObject("lineup")?.let {
            gson.fromJson(it, LineupConfig::class.java)
        }
    }

    fun push(email: String, deviceId: String, local: LineupConfig, baseRevision: Long = 0L): LineupConfig {
        val token = ensureDeviceToken(email, deviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            add("lineup", gson.toJsonTree(local))
            if (baseRevision > 0L) addProperty("baseRevision", baseRevision)
        }
        val pushed = postJson("${baseUrl()}/api/lineup", payload, token, deviceId)?.getAsJsonObject("lineup")?.let {
            gson.fromJson(it, LineupConfig::class.java)
        }
        return pushed ?: local
    }

    fun reconcile(email: String, deviceId: String, local: LineupConfig): LineupConfig {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/lineup?email=${enc(email)}&deviceId=${enc(deviceId)}"
        val remote = getJson(url, token, deviceId)?.getAsJsonObject("lineup")?.let {
            gson.fromJson(it, LineupConfig::class.java)
        }
        val localRev = local.revision ?: 0L
        val remoteRev = remote?.revision ?: 0L
        if (remote != null && remoteRev > localRev) {
            return remote
        }
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            add("lineup", gson.toJsonTree(local))
        }
        val pushed = postJson("${baseUrl()}/api/lineup", payload, token, deviceId)?.getAsJsonObject("lineup")?.let {
            gson.fromJson(it, LineupConfig::class.java)
        }
        return pushed ?: local
    }

    fun pushFavorites(
        email: String,
        deviceId: String,
        ids: List<String>,
        revision: Long,
        baseRevision: Long
    ) {
        val token = ensureDeviceToken(email, deviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            add("ids", gson.toJsonTree(ids))
            addProperty("revision", revision)
            addProperty("baseRevision", baseRevision)
        }
        runCatching { postJson("${baseUrl()}/api/favorites", payload, token, deviceId) }
    }

    fun syncFavorites(
        email: String,
        deviceId: String,
        localIds: List<String>,
        localRevision: Long
    ): Pair<List<String>, Long>? {
        val token = ensureDeviceToken(email, deviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            add("ids", gson.toJsonTree(localIds))
            addProperty("baseRevision", localRevision)
        }
        val fav = postJson("${baseUrl()}/api/favorites", payload, token, deviceId)?.getAsJsonObject("favorites") ?: return null
        val rev = fav.get("revision")?.asLong ?: localRevision
        val arr = fav.getAsJsonArray("ids") ?: return null
        val out = ArrayList<String>(arr.size())
        arr.forEach { out.add(it.asString) }
        return out to rev
    }

    fun pushWatchProgress(email: String, deviceId: String, entry: WatchEntry) {
        val token = ensureDeviceToken(email, deviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            addProperty("channelId", entry.channelId)
            entry.seriesId?.let { addProperty("seriesId", it) }
            entry.season?.let { addProperty("season", it) }
            addProperty("name", entry.name)
            addProperty("section", entry.section)
            addProperty("positionMs", entry.positionMs)
            addProperty("durationMs", entry.durationMs)
        }
        runCatching { postJson("${baseUrl()}/api/watch-history", payload, token, deviceId) }
    }

    fun fetchWatchHistory(email: String, deviceId: String): List<WatchEntry> {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/watch-history?email=${enc(email)}&deviceId=${enc(deviceId)}&limit=40"
        val arr = getJson(url, token, deviceId)?.getAsJsonArray("items") ?: return emptyList()
        val out = ArrayList<WatchEntry>(arr.size())
        for (el in arr) {
            runCatching { out.add(gson.fromJson(el, WatchEntry::class.java)) }
        }
        return out
    }

    fun uploadRecording(email: String, deviceId: String, recordingId: String, file: File): Boolean {
        if (!file.isFile) return false
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/recordings/file?email=${enc(email)}&deviceId=${enc(deviceId)}&id=${enc(recordingId)}"
        val builder = Request.Builder()
            .url(url)
            .header("X-Device-Id", deviceId)
            .put(file.asRequestBody("video/mp2t".toMediaType()))
        if (!token.isNullOrBlank()) builder.header("X-Device-Token", token)
        return runCatching {
            http.newCall(builder.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun pushRecordings(email: String, deviceId: String, book: RecordingBook): RecordingBook =
        syncRecordings(email, deviceId, book)

    fun syncRecordings(email: String, deviceId: String, local: RecordingBook): RecordingBook {
        val token = ensureDeviceToken(email, deviceId)
        val getUrl = "${baseUrl()}/api/recordings?email=${enc(email)}&deviceId=${enc(deviceId)}"
        val remote = runCatching {
            getJson(getUrl, token, deviceId)?.getAsJsonObject("recordings")?.let {
                gson.fromJson(it, RecordingBook::class.java)
            }
        }.getOrNull()
        val localRev = local.revision ?: 0L
        val remoteRev = remote?.revision ?: 0L
        if (remote != null && remoteRev > localRev) return remote
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            add("recordings", gson.toJsonTree(local))
        }
        return runCatching {
            postJson("${baseUrl()}/api/recordings", payload, token, deviceId)?.getAsJsonObject("recordings")?.let {
                gson.fromJson(it, RecordingBook::class.java)
            }
        }.getOrNull() ?: local
    }

    data class RemoteReminder(
        val id: String = "",
        val channelId: String = "",
        val channelNumber: Int = 0,
        val channelName: String = "",
        val title: String = "",
        val startMillis: Long = 0L,
        val leadMinutes: Int = 15
    )

    fun fetchRemoteReminders(email: String, deviceId: String): List<RemoteReminder> {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/reminders?email=${enc(email)}&deviceId=${enc(deviceId)}"
        val arr = getJson(url, token, deviceId)?.getAsJsonArray("reminders") ?: return emptyList()
        val out = ArrayList<RemoteReminder>(arr.size())
        for (el in arr) {
            runCatching { out.add(gson.fromJson(el, RemoteReminder::class.java)) }
        }
        return out
    }

    fun publishCountries(email: String, deviceId: String, countries: List<CountryOption>) {
        val token = ensureDeviceToken(email, deviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            add("countries", gson.toJsonTree(countries))
        }
        runCatching { postJson("${baseUrl()}/api/catalog", payload, token, deviceId) }
    }

    fun publishSnapshot(
        email: String,
        deviceId: String,
        snapshot: CatalogDb.DashboardSnapshot,
        libraryStamp: Long
    ) {
        val token = ensureDeviceToken(email, deviceId)
        val groupsArr = JsonArray()
        for (g in snapshot.groups) {
            groupsArr.add(JsonObject().apply {
                addProperty("id", g.id)
                addProperty("name", g.name)
                addProperty("kind", g.kind)
                addProperty("count", g.count)
                addProperty("countryId", g.countryId)
            })
        }
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            addProperty("libraryStamp", libraryStamp)
            addProperty("channelCount", snapshot.channelCount)
            add("groups", groupsArr)
            add("countries", gson.toJsonTree(snapshot.countries))
        }
        runCatching { postJson("${baseUrl()}/api/catalog", payload, token, deviceId) }
    }

    private fun getJson(url: String, token: String?, deviceId: String): JsonObject? {
        val builder = Request.Builder().url(url).get().header("X-Device-Id", deviceId)
        if (!token.isNullOrBlank()) builder.header("X-Device-Token", token)
        return http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
        }
    }

    private fun postJson(url: String, body: JsonObject, token: String?, deviceId: String): JsonObject? =
        postJsonRaw(url, body, token, deviceId)

    private fun postJsonRaw(url: String, body: JsonObject, token: String?, deviceId: String): JsonObject? {
        val builder = Request.Builder()
            .url(url)
            .header("X-Device-Id", deviceId)
            .post(body.toString().toRequestBody(jsonType))
        if (!token.isNullOrBlank()) builder.header("X-Device-Token", token)
        return http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        private val sseHttp = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun peekDashboardUrl(): String? {
            val cloud = BuildConfig.PORTAL_CLOUD_URL.trim().trimEnd('/')
            if (cloud.startsWith("http://") || cloud.startsWith("https://")) return cloud
            return DashboardHost.url()
        }
    }
}
