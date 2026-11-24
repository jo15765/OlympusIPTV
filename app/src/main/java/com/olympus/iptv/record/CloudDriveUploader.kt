package com.olympus.iptv.record

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.olympus.iptv.BuildConfig
import com.olympus.iptv.data.CloudDriveSettings
import com.olympus.iptv.data.Recording
import com.olympus.iptv.playlist.HttpClients
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Pure-OkHttp resumable cloud DVR uploader & OAuth Device-Code client for Google Drive and OneDrive.
 * Requires zero Google Play Services or Microsoft SDKs, so it runs identically on Fire TV Sticks,
 * Android TV, and mobile devices.
 */
object CloudDriveUploader {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val streamType = "video/mp2t".toMediaType()

    // Google Drive requires chunk sizes to be multiples of 256 KiB; OneDrive requires multiples of 320 KiB.
    // 3,276,800 bytes (3.2 MiB) is an exact multiple of 320 KiB (10 * 320 KiB); 4 MiB is 16 * 256 KiB.
    private const val GDRIVE_CHUNK = 4 * 1024 * 1024
    private const val ONEDRIVE_CHUNK = 10 * 320 * 1024

    private val uploadHttp by lazy {
        HttpClients.shared.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    data class DeviceAuthStart(
        val provider: String,
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int,
        val interval: Int
    )

    sealed class DeviceAuthPoll {
        object Pending : DeviceAuthPoll()
        data class Connected(val refreshToken: String, val accountLabel: String) : DeviceAuthPoll()
        data class Failed(val message: String) : DeviceAuthPoll()
    }

    data class UploadResult(
        val provider: String,
        val webUrl: String
    )

    fun effectiveClientId(provider: String, overrideClientId: String?): String {
        val custom = overrideClientId?.trim().orEmpty()
        if (custom.isNotBlank()) return custom
        return when (provider.trim().lowercase()) {
            Recording.GDRIVE -> BuildConfig.GOOGLE_DRIVE_CLIENT_ID.trim()
            Recording.ONEDRIVE -> BuildConfig.ONEDRIVE_CLIENT_ID.trim()
            else -> ""
        }
    }

    fun startDeviceAuth(provider: String, overrideClientId: String?): DeviceAuthStart {
        val normalized = provider.trim().lowercase()
        val clientId = effectiveClientId(normalized, overrideClientId)
        if (clientId.isBlank()) {
            error("Enter your OAuth Client ID first (Google Cloud Console for Drive or Azure App Registration for OneDrive)")
        }
        return when (normalized) {
            Recording.GDRIVE -> {
                val form = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("scope", "https://www.googleapis.com/auth/drive.file")
                    .build()
                val req = Request.Builder()
                    .url("https://oauth2.googleapis.com/device/code")
                    .post(form)
                    .build()
                val json = executeJson(req)
                DeviceAuthStart(
                    provider = Recording.GDRIVE,
                    deviceCode = json.str("device_code"),
                    userCode = json.str("user_code"),
                    verificationUrl = json.str("verification_url").ifBlank { "https://www.google.com/device" },
                    expiresIn = json.int("expires_in", 1800),
                    interval = json.int("interval", 5)
                )
            }
            Recording.ONEDRIVE -> {
                val form = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("scope", "Files.ReadWrite offline_access User.Read")
                    .build()
                val req = Request.Builder()
                    .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode")
                    .post(form)
                    .build()
                val json = executeJson(req)
                DeviceAuthStart(
                    provider = Recording.ONEDRIVE,
                    deviceCode = json.str("device_code"),
                    userCode = json.str("user_code"),
                    verificationUrl = json.str("verification_uri").ifBlank { "https://microsoft.com/devicelogin" },
                    expiresIn = json.int("expires_in", 900),
                    interval = json.int("interval", 5)
                )
            }
            else -> error("Unsupported cloud provider: $provider")
        }
    }

    fun pollDeviceAuth(
        provider: String,
        overrideClientId: String?,
        clientSecret: String?,
        deviceCode: String
    ): DeviceAuthPoll {
        val normalized = provider.trim().lowercase()
        val clientId = effectiveClientId(normalized, overrideClientId)
        if (clientId.isBlank() || deviceCode.isBlank()) {
            return DeviceAuthPoll.Failed("Missing client ID or device code")
        }
        val (url, formBuilder) = when (normalized) {
            Recording.GDRIVE -> "https://oauth2.googleapis.com/token" to FormBody.Builder()
                .add("client_id", clientId)
                .apply { if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret.trim()) }
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            Recording.ONEDRIVE -> "https://login.microsoftonline.com/consumers/oauth2/v2.0/token" to FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            else -> return DeviceAuthPoll.Failed("Unsupported cloud provider")
        }
        val req = Request.Builder().url(url).post(formBuilder.build()).build()
        return uploadHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return@use DeviceAuthPoll.Failed("Unexpected response (${resp.code})")
            val err = json.str("error")
            if (err == "authorization_pending" || err == "slow_down") {
                return@use DeviceAuthPoll.Pending
            }
            if (!resp.isSuccessful || err.isNotBlank()) {
                val desc = json.str("error_description").ifBlank { err.ifBlank { "Authorization failed" } }
                return@use DeviceAuthPoll.Failed(desc)
            }
            val refresh = json.str("refresh_token")
            val access = json.str("access_token")
            if (refresh.isBlank()) {
                return@use DeviceAuthPoll.Failed("Provider did not return a refresh token")
            }
            val label = fetchAccountLabel(normalized, access)
            DeviceAuthPoll.Connected(refreshToken = refresh, accountLabel = label)
        }
    }

    fun upload(settings: CloudDriveSettings, file: File, fileName: String): UploadResult {
        if (!settings.ready()) error("Cloud Drive is not connected")
        if (!file.exists() || file.length() <= 0L) error("Recording file is empty")
        val provider = settings.normalizedProvider()
        val accessToken = refreshAccessToken(settings)
        return when (provider) {
            Recording.GDRIVE -> uploadToGoogleDrive(accessToken, settings.folderName(), file, fileName)
            Recording.ONEDRIVE -> uploadToOneDrive(accessToken, settings.folderName(), file, fileName)
            else -> error("Unsupported cloud provider: $provider")
        }
    }

    private fun refreshAccessToken(settings: CloudDriveSettings): String {
        val provider = settings.normalizedProvider()
        val clientId = effectiveClientId(provider, settings.clientId)
        val refresh = settings.refreshToken?.trim().orEmpty()
        if (clientId.isBlank() || refresh.isBlank()) error("Cloud Drive credentials missing")
        val (url, form) = when (provider) {
            Recording.GDRIVE -> "https://oauth2.googleapis.com/token" to FormBody.Builder()
                .add("client_id", clientId)
                .apply { if (!settings.clientSecret.isNullOrBlank()) add("client_secret", settings.clientSecret.trim()) }
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build()
            Recording.ONEDRIVE -> "https://login.microsoftonline.com/consumers/oauth2/v2.0/token" to FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .add("scope", "Files.ReadWrite offline_access User.Read")
                .build()
            else -> error("Unsupported provider")
        }
        val json = executeJson(Request.Builder().url(url).post(form).build())
        val token = json.str("access_token")
        if (token.isBlank()) error("Could not refresh cloud access token")
        return token
    }

    private fun uploadToGoogleDrive(
        accessToken: String,
        folderName: String,
        file: File,
        fileName: String
    ): UploadResult {
        val folderId = ensureGoogleDriveFolder(accessToken, folderName)
        val meta = JsonObject().apply {
            addProperty("name", fileName)
            if (folderId.isNotBlank()) {
                add("parents", com.google.gson.JsonArray().apply { add(folderId) })
            }
        }
        val initReq = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,webViewLink")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "video/mp2t")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody(jsonType))
            .build()
        val uploadUrl = uploadHttp.newCall(initReq).execute().use { resp ->
            if (!resp.isSuccessful) error("Google Drive init failed (${resp.code})")
            resp.header("Location").orEmpty()
        }
        if (uploadUrl.isBlank()) error("Google Drive did not return upload session URL")
        val finalJson = streamChunks(uploadUrl, file, GDRIVE_CHUNK, authHeader = null)
        val fileId = finalJson?.str("id").orEmpty()
        val link = finalJson?.str("webViewLink")
            ?.takeIf { it.isNotBlank() }
            ?: if (fileId.isNotBlank()) "https://drive.google.com/file/d/$fileId/view" else "https://drive.google.com"
        return UploadResult(provider = Recording.GDRIVE, webUrl = link)
    }

    private fun ensureGoogleDriveFolder(accessToken: String, folderName: String): String {
        val safeName = folderName.replace("'", "\\'")
        val query = URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and name='$safeName' and trashed=false",
            "UTF-8"
        )
        val searchReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id)&pageSize=1")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val existing = runCatching {
            executeJson(searchReq).getAsJsonArray("files")?.firstOrNull()?.asJsonObject?.str("id").orEmpty()
        }.getOrDefault("")
        if (existing.isNotBlank()) return existing
        val createMeta = JsonObject().apply {
            addProperty("name", folderName)
            addProperty("mimeType", "application/vnd.google-apps.folder")
        }
        val createReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(createMeta.toString().toRequestBody(jsonType))
            .build()
        return executeJson(createReq).str("id")
    }

    private fun uploadToOneDrive(
        accessToken: String,
        folderName: String,
        file: File,
        fileName: String
    ): UploadResult {
        val cleanFolder = folderName.trim('/').ifBlank { "Olympus-IPTV" }
        val encodedPath = "${encodeSegment(cleanFolder)}/${encodeSegment(fileName)}"
        val sessionBody = JsonObject().apply {
            add("item", JsonObject().apply {
                addProperty("@microsoft.graph.conflictBehavior", "replace")
                addProperty("name", fileName)
            })
        }
        val initReq = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/drive/root:/$encodedPath:/createUploadSession")
            .header("Authorization", "Bearer $accessToken")
            .post(sessionBody.toString().toRequestBody(jsonType))
            .build()
        val uploadUrl = executeJson(initReq).str("uploadUrl")
        if (uploadUrl.isBlank()) error("OneDrive did not return upload session URL")
        val finalJson = streamChunks(uploadUrl, file, ONEDRIVE_CHUNK, authHeader = null)
        val link = finalJson?.str("webUrl").orEmpty().ifBlank { "https://onedrive.live.com" }
        return UploadResult(provider = Recording.ONEDRIVE, webUrl = link)
    }

    private fun streamChunks(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        authHeader: String?
    ): JsonObject? {
        val total = file.length()
        val buffer = ByteArray(chunkSize)
        var offset = 0L
        var finalResponse: JsonObject? = null
        FileInputStream(file).use { input ->
            while (offset < total) {
                val want = minOf(chunkSize.toLong(), total - offset).toInt()
                var filled = 0
                while (filled < want) {
                    val read = input.read(buffer, filled, want - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled <= 0) break
                val end = offset + filled - 1
                val reqBuilder = Request.Builder()
                    .url(uploadUrl)
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .put(buffer.toRequestBody(streamType, 0, filled))
                if (!authHeader.isNullOrBlank()) reqBuilder.header("Authorization", authHeader)
                uploadHttp.newCall(reqBuilder.build()).execute().use { resp ->
                    val code = resp.code
                    val raw = resp.body?.string().orEmpty()
                    if (code !in setOf(200, 201, 202, 308)) {
                        error("Cloud upload failed at byte $offset (HTTP $code)")
                    }
                    if (code == 200 || code == 201) {
                        finalResponse = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                    }
                }
                offset += filled
            }
        }
        return finalResponse
    }

    private fun fetchAccountLabel(provider: String, accessToken: String): String {
        if (accessToken.isBlank()) return if (provider == Recording.GDRIVE) "Google Drive" else "OneDrive"
        return runCatching {
            when (provider) {
                Recording.GDRIVE -> {
                    val req = Request.Builder()
                        .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)")
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                    val user = executeJson(req).getAsJsonObject("user")
                    user?.str("emailAddress")?.ifBlank { user.str("displayName") } ?: "Google Drive"
                }
                Recording.ONEDRIVE -> {
                    val req = Request.Builder()
                        .url("https://graph.microsoft.com/v1.0/me?\$select=displayName,userPrincipalName,mail")
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                    val me = executeJson(req)
                    me.str("mail").ifBlank { me.str("userPrincipalName").ifBlank { me.str("displayName") } }
                        .ifBlank { "OneDrive" }
                }
                else -> "Cloud Drive"
            }
        }.getOrDefault(if (provider == Recording.GDRIVE) "Google Drive" else "OneDrive")
    }

    private fun executeJson(req: Request): JsonObject {
        uploadHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    val err = JsonParser.parseString(raw).asJsonObject
                    err.str("error_description").ifBlank { err.str("error") }
                }.getOrDefault("")
                error(msg.ifBlank { "HTTP ${resp.code}" })
            }
            return JsonParser.parseString(raw).asJsonObject
        }
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.int(key: String, fallback: Int): Int =
        get(key)?.takeIf { !it.isJsonNull }?.asInt ?: fallback
}
