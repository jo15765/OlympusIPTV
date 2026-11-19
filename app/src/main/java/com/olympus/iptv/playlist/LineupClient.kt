package com.olympus.iptv.playlist

import android.content.Context
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.RecordingBook
import java.io.File
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class LineupClient(context: Context) {
    private val baseUrl = dashboardBase(context)
    private val appContext = context.applicationContext
    private val http by lazy { PortalTls.client(appContext) }
    private val gson = Gson()

    suspend fun exchange(
        email: String,
        deviceId: String,
        loaded: LoadedPlaylist,
        local: LineupConfig
    ): LineupConfig = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || deviceId.isBlank()) return@withContext local
        runCatching {
            post("/api/register", mapOf("email" to normalized, "deviceId" to deviceId))
            val remote = fetchLineup(normalized, deviceId)
            if ((remote.revision ?: 0L) > 0L) {
                remote
            } else {
                post("/api/lineup", mapOf("email" to normalized, "deviceId" to deviceId, "lineup" to local))
                local
            }
        }.getOrDefault(local)
    }

    /** Country list from the TV catalog. Leaves groups and channels already stored on the dashboard. */
    suspend fun publishCountries(email: String, deviceId: String, options: List<CountryOption>) =
        withContext(Dispatchers.IO) {
            val normalized = email.trim().lowercase()
            if (normalized.isBlank() || deviceId.isBlank()) return@withContext
            runCatching {
                post(
                    "/api/catalog",
                    mapOf(
                        "email" to normalized,
                        "deviceId" to deviceId,
                        "countries" to options.map { mapOf("id" to it.id, "label" to it.label, "count" to it.count) }
                    )
                )
            }
        }

    /**
     * The guide's lineup wins until it has been saved with a clock time.
     * After that, the later edit — TV or dashboard — is the one both sides keep.
     */
    suspend fun reconcile(email: String, deviceId: String, local: LineupConfig): LineupConfig =
        withContext(Dispatchers.IO) {
            val normalized = email.trim().lowercase()
            if (normalized.isBlank() || deviceId.isBlank()) return@withContext local
            val remote = runCatching { fetchLineup(normalized, deviceId) }.getOrElse { return@withContext local }
            val localRev = local.revision ?: 0L
            val remoteRev = remote.revision ?: 0L
            val remoteHidden = remote.hiddenCountries.orEmpty()
            val localHidden = local.hiddenCountries.orEmpty()
            val remoteIsPlaceholder = remoteHidden.isEmpty() || remoteHidden.all { it == CountryIndex.OTHER_ID }
            val localHasCountries = localHidden.any { it != CountryIndex.OTHER_ID }
            if (localHasCountries && remoteIsPlaceholder) {
                val outgoing = local.copy(revision = System.currentTimeMillis())
                runCatching { push(normalized, deviceId, outgoing) }
                return@withContext outgoing
            }
            if (localRev > STAMP && remoteRev > localRev) return@withContext remote
            if (localRev > STAMP && remoteRev == localRev && !(localHasCountries && remoteIsPlaceholder)) {
                return@withContext local
            }
            val outgoing = local.copy(revision = System.currentTimeMillis())
            runCatching { push(normalized, deviceId, outgoing, remote.revision ?: 0L) }
            outgoing
        }

    suspend fun remoteLineup(email: String, deviceId: String): LineupConfig? = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || deviceId.isBlank()) return@withContext null
        runCatching { fetchLineup(normalized, deviceId) }.getOrNull()
    }

    suspend fun push(email: String, deviceId: String, config: LineupConfig, baseRevision: Long = 0L) = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || deviceId.isBlank()) return@withContext
        runCatching {
            post("/api/register", mapOf("email" to normalized, "deviceId" to deviceId))
            post(
                "/api/lineup",
                mapOf(
                    "email" to normalized,
                    "deviceId" to deviceId,
                    "lineup" to config,
                    "baseRevision" to baseRevision
                )
            )
        }
    }

    suspend fun pushFavorites(
        email: String,
        deviceId: String,
        ids: List<String>,
        revision: Long,
        baseRevision: Long = revision
    ) = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || deviceId.isBlank()) return@withContext
        runCatching {
            post(
                "/api/favorites",
                mapOf(
                    "email" to normalized,
                    "deviceId" to deviceId,
                    "ids" to ids,
                    "revision" to revision,
                    "baseRevision" to baseRevision
                )
            )
        }
    }

    suspend fun syncFavorites(
        email: String,
        deviceId: String,
        localIds: List<String>,
        localRevision: Long
    ): Pair<List<String>, Long> = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || deviceId.isBlank()) return@withContext localIds to localRevision
        val remote = runCatching { fetchFavorites(normalized, deviceId) }.getOrElse {
            return@withContext localIds to localRevision
        }
        if (remote.revision > localRevision) {
            val localKept = localIds.filter { it !in remote.removed }
            val remoteKept = remote.ids.filter { it !in remote.removed }
            if (localKept.toSet() == remoteKept.toSet()) {
                return@withContext remoteKept to remote.revision
            }
            val merged = ArrayList<String>(localKept.size + remoteKept.size)
            for (id in localKept) if (id !in merged) merged.add(id)
            for (id in remoteKept) if (id !in merged) merged.add(id)
            if (merged != remoteKept) {
                val revision = System.currentTimeMillis()
                pushFavorites(normalized, deviceId, merged, revision, localRevision)
                return@withContext merged to revision
            }
            return@withContext remoteKept to remote.revision
        }
        if (localIds == remote.ids && localIds.none { it in remote.removed }) {
            return@withContext localIds to localRevision.coerceAtLeast(remote.revision)
        }
        val outgoingRevision = if (localRevision > 0L) localRevision else System.currentTimeMillis()
        pushFavorites(normalized, deviceId, localIds, outgoingRevision, localRevision)
        localIds to outgoingRevision
    }

    private data class RemoteFavorites(
        val ids: List<String>,
        val removed: Set<String>,
        val revision: Long
    )

    private fun fetchFavorites(email: String, deviceId: String): RemoteFavorites {
        val emailQuery = java.net.URLEncoder.encode(email, Charsets.UTF_8.name())
        val deviceQuery = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$baseUrl/api/favorites?email=$emailQuery&deviceId=$deviceQuery")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Portal HTTP ${response.code}")
            val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
            val ids = root?.getAsJsonArray("ids")?.mapNotNull { element ->
                element?.asString?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val removed = root?.getAsJsonArray("removed")?.mapNotNull { element ->
                element?.asString?.takeIf { it.isNotBlank() }
            }.orEmpty().toSet()
            val revision = root?.get("revision")?.asLong ?: 0L
            return RemoteFavorites(ids, removed, revision)
        }
    }

    suspend fun publishSearchCatalog(
        email: String,
        deviceId: String,
        groups: List<Map<String, String>>,
        channels: List<Map<String, String>>,
        stamp: String
    ) = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || deviceId.isBlank() || (groups.isEmpty() && channels.isEmpty())) return@withContext
        runCatching {
            post(
                "/api/catalog",
                mapOf(
                    "email" to normalized,
                    "deviceId" to deviceId,
                    "groups" to groups,
                    "channels" to channels,
                    "stamp" to stamp
                )
            )
        }
    }

    suspend fun syncRecordings(email: String, deviceId: String, local: RecordingBook): RecordingBook =
        withContext(Dispatchers.IO) {
            val normalized = email.trim().lowercase()
            if (normalized.isBlank() || deviceId.isBlank()) return@withContext local
            val remote = runCatching { fetchRecordings(normalized, deviceId) }.getOrElse { return@withContext local }
            if (remote.revisionOrZero() > local.revisionOrZero()) {
                return@withContext if (remote.series == null && local.seriesOrEmpty().isNotEmpty()) {
                    remote.copy(series = local.series)
                } else {
                    remote
                }
            }
            if (local.revisionOrZero() > remote.revisionOrZero()) {
                runCatching { pushRecordings(normalized, deviceId, local) }
                return@withContext local
            }
            if (local.revisionOrZero() == 0L && local.itemsOrEmpty().isNotEmpty()) {
                val stamped = local.copy(revision = System.currentTimeMillis())
                runCatching { pushRecordings(normalized, deviceId, stamped) }
                return@withContext stamped
            }
            local
        }

    suspend fun pushRecordings(email: String, deviceId: String, book: RecordingBook) = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || deviceId.isBlank()) return@withContext
        runCatching {
            post("/api/register", mapOf("email" to normalized, "deviceId" to deviceId))
            post(
                "/api/recordings",
                mapOf(
                    "email" to normalized,
                    "deviceId" to deviceId,
                    "recordings" to book
                )
            )
        }
    }

    suspend fun uploadRecording(email: String, deviceId: String, recordingId: String, file: File): Boolean =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() <= 0L) return@withContext false
            val normalized = email.trim().lowercase()
            val emailQuery = java.net.URLEncoder.encode(normalized, Charsets.UTF_8.name())
            val deviceQuery = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
            val idQuery = java.net.URLEncoder.encode(recordingId, Charsets.UTF_8.name())
            val request = Request.Builder()
                .url("$baseUrl/api/recordings/file?email=$emailQuery&deviceId=$deviceQuery&id=$idQuery")
                .put(file.asRequestBody("video/mp2t".toMediaType()))
                .build()
            runCatching {
                PortalTls.uploadClient(appContext).newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    private fun fetchRecordings(email: String, deviceId: String): RecordingBook {
        val emailQuery = java.net.URLEncoder.encode(email, Charsets.UTF_8.name())
        val deviceQuery = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$baseUrl/api/recordings?email=$emailQuery&deviceId=$deviceQuery")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Portal HTTP ${response.code}")
            return gson.fromJson(response.body?.string().orEmpty(), RecordingBook::class.java) ?: RecordingBook()
        }
    }

    suspend fun dashboardAddress(): String = withContext(Dispatchers.IO) {
        val fresh = com.olympus.iptv.portal.DashboardHost.ensureReachable()
        if (!fresh.contains("127.0.0.1") && !fresh.contains("localhost")) cachedDashboardUrl = fresh
        fresh
    }

    private fun fetchLineup(email: String, deviceId: String): LineupConfig {
        val emailQuery = java.net.URLEncoder.encode(email, Charsets.UTF_8.name())
        val deviceQuery = java.net.URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$baseUrl/api/lineup?email=$emailQuery&deviceId=$deviceQuery")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Portal HTTP ${response.code}")
            val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
            return gson.fromJson(root?.get("lineup"), LineupConfig::class.java) ?: LineupConfig()
        }
    }

    private fun catalogGroups(loaded: LoadedPlaylist): List<Map<String, String>> {
        return (loaded.liveCategories + loaded.vodCategories)
            .filter { it.id != PlaylistLoader.ALL_ID && it.id != PlaylistLoader.FAVORITES_ID }
            .map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "kind" to if (it.kind == ContentKind.VOD) "VOD" else "LIVE"
                )
            }
    }

    private fun catalogChannels(loaded: LoadedPlaylist): List<Map<String, String>> {
        return (loaded.liveChannels + loaded.vodChannels).map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "groupId" to (it.categoryId ?: it.groupTitle.orEmpty()),
                "kind" to if (it.kind == ContentKind.VOD) "VOD" else "LIVE",
                "countryId" to (it.countryId ?: CountryIndex.OTHER_ID)
            )
        }
    }

    private fun dashboardBase(@Suppress("UNUSED_PARAMETER") context: Context): String =
        "http://127.0.0.1:${com.olympus.iptv.portal.DashboardHost.port}"

    private fun post(path: String, body: Any) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Portal HTTP ${response.code}")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val STAMP = 1_000_000_000_000L

        @Volatile
        private var cachedDashboardUrl: String? = null

        fun peekDashboardUrl(): String? = cachedDashboardUrl
    }
}
