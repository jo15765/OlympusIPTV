package com.olympus.iptv.playlist

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.olympus.iptv.BuildConfig
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
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

class LineupClient(
    private val context: Context,
    private val http: OkHttpClient = defaultHttp
) {
    private val gson: Gson get() = sharedGson
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences("olympus_cloud_auth", Context.MODE_PRIVATE)
    }

    private fun baseUrl(): String {
        val cloud = BuildConfig.PORTAL_CLOUD_URL.trim().trimEnd('/')
        if (cloud.startsWith("http://") || cloud.startsWith("https://")) return cloud
        return DashboardHost.ensureReachable().trimEnd('/')
    }

    fun dashboardAddress(): String = DashboardHost.url()

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

    fun pushProviderLogin(
        playlist: com.olympus.iptv.data.SavedPlaylist,
        deviceId: String,
        fallbackEmail: String = ""
    ): Boolean {
        val cloud = BuildConfig.PORTAL_CLOUD_URL.trim()
        if (!cloud.startsWith("http://") && !cloud.startsWith("https://")) return false
        val email = playlist.email?.trim()?.takeIf { it.contains("@") } ?: fallbackEmail.trim()
        val zip = playlist.homeZip?.filter { it.isDigit() }?.take(5).orEmpty().ifBlank { "10001" }
        if (!email.contains("@") || deviceId.isBlank()) return false
        val isM3u = playlist.type == com.olympus.iptv.data.PlaylistType.M3U
        val rawHost = playlist.xtreamHost?.trim().orEmpty()
        val host = when {
            rawHost.isBlank() -> ""
            rawHost.startsWith("http://", ignoreCase = true) || rawHost.startsWith("https://", ignoreCase = true) -> rawHost
            else -> "http://$rawHost"
        }
        val url = playlist.m3uUrl?.trim().orEmpty()
        val user = (if (isM3u) playlist.username else playlist.xtreamUsername)?.trim().orEmpty()
        val pass = (if (isM3u) playlist.password else playlist.xtreamPassword).orEmpty()
        if (isM3u && !url.startsWith("http", ignoreCase = true)) return false
        if (!isM3u && (!host.startsWith("http", ignoreCase = true) || user.isBlank() || pass.isBlank())) return false
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            addProperty("zip", zip)
            addProperty("mode", if (isM3u) "m3u" else "xtream")
            addProperty("host", host)
            addProperty("url", url)
            addProperty("username", user)
            addProperty("password", pass)
            addProperty("includeVod", playlist.includeVod)
            addProperty("includeInternational", playlist.includeInternational == true)
        }
        return runCatching {
            postJson("${baseUrl()}/api/setup", body, null, deviceId)?.get("ok")?.asBoolean == true
        }.onFailure { err ->
            com.olympus.iptv.AppLog.error("Cloud", "Failed to register provider login: ${err.message ?: err.javaClass.simpleName}", err)
        }.getOrDefault(false)
    }

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
            return AdultGroup.preserveLocalParentalOverrides(local, remote)
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

    data class AccountDevice(
        val id: String,
        val name: String,
        val online: Boolean
    )

    fun fetchAccountDevices(email: String, deviceId: String): List<AccountDevice> {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/devices?email=${enc(email)}"
        val arr = getJson(url, token, deviceId)?.getAsJsonArray("devices") ?: return emptyList()
        val out = ArrayList<AccountDevice>(arr.size())
        for (el in arr) {
            val obj = el.asJsonObject
            val id = obj.get("id")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
            if (id.isEmpty()) continue
            val name = obj.get("name")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty().ifEmpty { id }
            val online = obj.get("online")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
            out.add(AccountDevice(id = id, name = name, online = online))
        }
        return out
    }

    fun cloneConfigToDevices(
        email: String,
        sourceDeviceId: String,
        targetDeviceIds: List<String>,
        lineup: LineupConfig
    ): List<String> {
        val targets = targetDeviceIds.map { it.trim() }.filter { it.isNotEmpty() && !it.equals(sourceDeviceId, ignoreCase = true) }.distinct()
        if (targets.isEmpty()) return emptyList()
        val token = ensureDeviceToken(email, sourceDeviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("sourceDeviceId", sourceDeviceId)
            add("targetDeviceIds", gson.toJsonTree(targets))
            add("lineup", gson.toJsonTree(lineup))
        }
        val resp = postJson("${baseUrl()}/api/devices/clone", payload, token, sourceDeviceId) ?: return emptyList()
        val copied = resp.getAsJsonArray("copiedTo") ?: return emptyList()
        return copied.mapNotNull { it.takeUnless { el -> el.isJsonNull }?.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
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

    data class CloudCatalogResponse(
        val categories: List<com.olympus.iptv.data.Category> = emptyList(),
        val channels: List<com.olympus.iptv.data.Channel> = emptyList(),
        val stamp: Long = 0L
    )

    fun fetchCloudCatalog(email: String, deviceId: String, kind: String): CloudCatalogResponse? {
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/catalog/sync?email=${enc(email)}&deviceId=${enc(deviceId)}&kind=$kind"
        val req = Request.Builder()
            .url(url)
            .header("X-Device-Id", deviceId)
            .apply { if (!token.isNullOrBlank()) header("X-Device-Token", token) }
            .get()
            .build()
        return runCatching {
            catalogHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null

                JsonReader(body.charStream()).use { reader ->
                    parseCloudCatalog(reader)
                }
            }
        }.getOrNull()
    }

    private fun parseCloudCatalog(reader: JsonReader): CloudCatalogResponse? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
        val categories = ArrayList<Category>()
        val channels = ArrayList<Channel>(512)
        var stamp = 0L
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "categories" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            parseCategory(reader)?.let { categories.add(it) }
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                "channels" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            parseChannel(reader)?.let { channels.add(it) }
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                "stamp" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        stamp = runCatching { reader.nextLong() }.getOrDefault(0L)
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CloudCatalogResponse(categories = categories, channels = channels, stamp = stamp)
    }

    private fun parseCategory(reader: JsonReader): Category? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var id = ""
        var name = ""
        var kind = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextStringOrEmpty()
                "name" -> name = reader.nextStringOrEmpty()
                "kind" -> kind = reader.nextStringOrEmpty()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Category(id = id, name = name, kind = contentKind(kind))
    }

    private fun parseChannel(reader: JsonReader): Channel? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var id = ""
        var name = ""
        var streamUrl = ""
        var logoUrl: String? = null
        var groupTitle: String? = null
        var categoryId: String? = null
        var kind = ""
        var seriesId: String? = null
        var season: Int? = null
        var tvArchive: Boolean? = null
        var tvArchiveDuration: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextStringOrEmpty()
                "name" -> name = reader.nextStringOrEmpty()
                "streamUrl" -> streamUrl = reader.nextStringOrEmpty()
                "logoUrl" -> logoUrl = reader.nextStringOrNull()
                "groupTitle" -> groupTitle = reader.nextStringOrNull()
                "categoryId" -> categoryId = reader.nextStringOrNull()
                "kind" -> kind = reader.nextStringOrEmpty()
                "seriesId" -> seriesId = reader.nextStringOrNull()
                "season" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        season = runCatching { reader.nextInt() }.getOrNull()
                    }
                }
                "tvArchive" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        tvArchive = runCatching { reader.nextBoolean() }.getOrNull()
                    }
                }
                "tvArchiveDuration" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        tvArchiveDuration = runCatching { reader.nextInt() }.getOrNull()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Channel(
            id = id,
            name = name,
            streamUrl = streamUrl,
            logoUrl = logoUrl,
            groupTitle = groupTitle,
            categoryId = categoryId,
            kind = contentKind(kind),
            seriesId = seriesId,
            season = season,
            tvArchive = tvArchive,
            tvArchiveDuration = tvArchiveDuration
        )
    }

    private fun JsonReader.nextStringOrEmpty(): String =
        if (peek() == JsonToken.NULL) {
            nextNull()
            ""
        } else {
            nextString()
        }

    private fun JsonReader.nextStringOrNull(): String? =
        nextStringOrEmpty().takeIf { it.isNotBlank() }

    fun uploadErrorLog(
        email: String?,
        deviceId: String,
        tag: String,
        message: String,
        stackTrace: String? = null
    ): Boolean {
        val payload = JsonObject().apply {
            if (!email.isNullOrBlank()) addProperty("email", email)
            addProperty("deviceId", deviceId)
            addProperty("platform", "android")
            addProperty("tag", tag)
            addProperty("message", message)
            if (!stackTrace.isNullOrBlank()) addProperty("stackTrace", stackTrace)
            addProperty("appVersion", BuildConfig.VERSION_NAME)
        }
        return runCatching { postJsonRaw("${baseUrl()}/api/logs/error", payload, null, deviceId) != null }
            .getOrDefault(false)
    }

    private fun contentKind(raw: String): ContentKind =
        if (raw.equals("vod", true) || raw.equals("series", true)) ContentKind.VOD else ContentKind.LIVE

    private fun JsonObject.text(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.textOrNull(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> =
        if (this == null) emptyList() else this.toList()

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

    data class DvrSeriesRule(
        val id: String = "",
        val titlePattern: String,
        val channelId: String = "",
        val channelName: String = "",
        val leadMinutes: Int = 2,
        val tailMinutes: Int = 5,
        val keepLast: Int = 5,
        val enabled: Boolean = true
    )

    fun fetchAlternateFeeds(email: String, deviceId: String, channelId: String): List<Channel> {
        if (!email.contains("@") || deviceId.isBlank() || channelId.isBlank()) return emptyList()
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/channels/${enc(channelId)}/alternates?email=${enc(email)}&deviceId=${enc(deviceId)}"
        val arr = runCatching { getJson(url, token, deviceId)?.getAsJsonArray("alternates") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Channel>(arr.size())
        for (el in arr) {
            val obj = el.asJsonObject
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val streamUrl = obj.get("streamUrl")?.takeIf { !it.isJsonNull }?.asString ?: continue
            out.add(
                Channel(
                    id = id,
                    name = name,
                    streamUrl = streamUrl,
                    logoUrl = obj.get("logoUrl")?.takeIf { !it.isJsonNull }?.asString,
                    groupTitle = obj.get("groupTitle")?.takeIf { !it.isJsonNull }?.asString,
                    kind = ContentKind.LIVE
                )
            )
        }
        return out
    }

    fun pushStreamTelemetry(
        email: String,
        deviceId: String,
        channelId: String,
        channelName: String,
        bitrateKbps: Int,
        bufferCount: Int,
        rebufferMs: Int,
        failed: Boolean,
        status: String
    ) {
        if (!email.contains("@") || deviceId.isBlank() || channelId.isBlank()) return
        val token = ensureDeviceToken(email, deviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            addProperty("channelId", channelId)
            addProperty("channelName", channelName)
            addProperty("bitrateKbps", bitrateKbps)
            addProperty("bufferCount", bufferCount)
            addProperty("rebufferMs", rebufferMs)
            addProperty("failed", failed)
            addProperty("status", status)
        }
        runCatching { postJson("${baseUrl()}/api/telemetry/heartbeat", payload, token, deviceId) }
    }

    fun fetchDvrRules(email: String, deviceId: String): List<DvrSeriesRule> {
        if (!email.contains("@") || deviceId.isBlank()) return emptyList()
        val token = ensureDeviceToken(email, deviceId)
        val url = "${baseUrl()}/api/dvr/rules?email=${enc(email)}&deviceId=${enc(deviceId)}"
        val arr = runCatching { getJson(url, token, deviceId)?.getAsJsonArray("rules") }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                DvrSeriesRule(
                    id = o.get("id")?.asString.orEmpty(),
                    titlePattern = o.get("titlePattern")?.asString.orEmpty(),
                    channelId = o.get("channelId")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    channelName = o.get("channelName")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    leadMinutes = o.get("leadMinutes")?.takeIf { !it.isJsonNull }?.asInt ?: 2,
                    tailMinutes = o.get("tailMinutes")?.takeIf { !it.isJsonNull }?.asInt ?: 5,
                    keepLast = o.get("keepLast")?.takeIf { !it.isJsonNull }?.asInt ?: 5,
                    enabled = o.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                )
            }.getOrNull()
        }
    }

    fun upsertDvrRule(email: String, deviceId: String, rule: DvrSeriesRule): String? {
        if (!email.contains("@") || deviceId.isBlank()) return null
        val token = ensureDeviceToken(email, deviceId)
        val payload = JsonObject().apply {
            addProperty("email", email)
            addProperty("deviceId", deviceId)
            if (rule.id.isNotBlank()) addProperty("id", rule.id)
            addProperty("titlePattern", rule.titlePattern)
            addProperty("channelId", rule.channelId)
            addProperty("channelName", rule.channelName)
            addProperty("leadMinutes", rule.leadMinutes)
            addProperty("tailMinutes", rule.tailMinutes)
            addProperty("keepLast", rule.keepLast)
            addProperty("enabled", rule.enabled)
        }
        return runCatching { postJson("${baseUrl()}/api/dvr/rules", payload, token, deviceId)?.get("id")?.asString }.getOrNull()
    }

    fun exportPlaylistUrl(email: String, deviceId: String, useProxy: Boolean = false): String =
        "${baseUrl()}/api/export/playlist.m3u?email=${enc(email)}&deviceId=${enc(deviceId)}" +
            if (useProxy) "&proxy=1" else ""

    fun exportEpgUrl(email: String, deviceId: String): String =
        "${baseUrl()}/api/export/epg.xml?email=${enc(email)}&deviceId=${enc(deviceId)}"

    private fun getJson(url: String, token: String?, deviceId: String, client: OkHttpClient = http): JsonObject? {
        val builder = Request.Builder().url(url).get().header("X-Device-Id", deviceId)
        if (!token.isNullOrBlank()) builder.header("X-Device-Token", token)
        return client.newCall(builder.build()).execute().use { resp ->
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
        private val sharedGson get() = com.olympus.iptv.data.AppJson.gson

        private val apiKeyInterceptor = okhttp3.Interceptor { chain ->
            val key = BuildConfig.PORTAL_API_KEY.trim()
            val req = if (key.isNotEmpty() && chain.request().header("X-Olympus-Api-Key") == null) {
                chain.request().newBuilder().header("X-Olympus-Api-Key", key).build()
            } else {
                chain.request()
            }
            chain.proceed(req)
        }

        private val defaultHttp by lazy {
            HttpClients.shared.newBuilder()
                .addInterceptor(apiKeyInterceptor)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private val catalogHttp by lazy {
            HttpClients.shared.newBuilder()
                .addInterceptor(apiKeyInterceptor)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        }

        private val sseHttp by lazy {
            HttpClients.shared.newBuilder()
                .addInterceptor(apiKeyInterceptor)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun peekDashboardUrl(): String? {
            return DashboardHost.url()
        }
    }
}
