package com.olympus.iptv.portal

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.LruCache
import java.io.BufferedInputStream
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.AutoFavorites
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.NameImages
import com.olympus.iptv.data.RecordingBook
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.data.SmbSettings
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.CatalogIngest
import com.olympus.iptv.playlist.GroupLock
import com.olympus.iptv.playlist.HttpClients
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.playlist.XtreamClient
import com.olympus.iptv.ui.player.StreamSessions
import com.olympus.iptv.record.RecordingFiles
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Serves the dashboard from this device while the app process is running.
 * Phones and computers on the same Wi-Fi open the address returned by [url].
 */
object DashboardHost {
    const val PORT = 8765
    private const val MAX_JSON_BYTES = 512 * 1024
    private const val MAX_RECORDING_BYTES = 256 * 1024 * 1024
    private const val MAX_LOGO_BYTES = 1_500_000L
    private const val LOGO_ALLOW_CAP = 2_000
    @Volatile var port: Int = PORT
    @Volatile private var hostName: String = ""
    @Volatile private var server: ServerSocket? = null
    private val urlListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val started = AtomicBoolean(false)
    private val gson = Gson()
    private val requestGzip = ThreadLocal.withInitial { false }
    private val removed = ConcurrentHashMap<String, MutableList<String>>()
    private val logoAllow = ConcurrentHashMap<String, Boolean>()
    @Volatile private var appContext: Context? = null
    @Volatile private var page: ByteArray? = null
    @Volatile private var pageGzip: ByteArray? = null
    @Volatile private var logo: ByteArray? = null
    @Volatile private var cachedDeviceId: String? = null
    private val logoMemCache = object : LruCache<String, Pair<String, ByteArray>>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<String, ByteArray>): Int = value.second.size
    }
    private data class GuideHold(
        val playlistId: String,
        val stamp: Long,
        val rows: List<AutoFavorites.GuideChannel>
    )
    @Volatile private var guideHold: GuideHold? = null
    @Volatile private var catalogHold: Pair<CatalogDb.DashboardSnapshot, Map<String, Any>>? = null

    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "dashboard-http").apply { isDaemon = true }
    }
    private val logos by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        appContext = app
        watchNetwork(app)
        thread(name = "dashboard-accept", isDaemon = true) {
            try {
                while (started.get()) {
                    val socket = openFreePort()
                    if (socket == null) {
                        started.set(false)
                        com.olympus.iptv.AppLog.error("Dashboard", "The dashboard did not start")
                        return@thread
                    }
                    server = socket
                    refreshAddress()
                    com.olympus.iptv.AppLog.info("Dashboard", "Listening on ${url()}")
                    while (started.get() && socket === server && !socket.isClosed) {
                        val client = try {
                            socket.accept()
                        } catch (_: Exception) {
                            break
                        }
                        com.olympus.iptv.vpn.AppTunnel.protect(client)
                        workers.execute {
                            try {
                                handle(app, client)
                            } catch (error: Throwable) {
                                com.olympus.iptv.AppLog.error("Dashboard", "Request failed", error)
                                runCatching {
                                    writeBytes(client, 500, "text/plain; charset=utf-8", "Dashboard error\n".toByteArray(), "no-store")
                                }
                            } finally {
                                try {
                                    client.close()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    if (server === socket) server = null
                    runCatching { socket.close() }
                }
            } catch (error: Throwable) {
                com.olympus.iptv.AppLog.error("Dashboard", "The dashboard stopped", error)
            }
        }
    }

    fun addUrlListener(listener: (String) -> Unit) {
        urlListeners.add(listener)
    }

    fun removeUrlListener(listener: (String) -> Unit) {
        urlListeners.remove(listener)
    }

    /** Confirms this process is answering, and moves to a free port when it is not. */
    fun ensureReachable(): String {
        val current = server
        if (current != null && (current.isClosed || !current.isBound)) {
            runCatching { current.close() }
        }
        val deadline = System.currentTimeMillis() + 1_500L
        while (System.currentTimeMillis() < deadline) {
            val next = server
            if (next != null && next.isBound && !next.isClosed) break
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                break
            }
        }
        refreshAddress()
        return url()
    }

    fun url(): String {
        val host = hostName.ifBlank { "127.0.0.1" }
        return "http://$host:$port"
    }

    private fun openFreePort(): ServerSocket? {
        val choices = ArrayList<Int>()
        choices.add(PORT)
        for (extra in 8766..8776) choices.add(extra)
        choices.add(8787)
        choices.add(8910)
        choices.add(9091)
        for (candidate in choices.distinct()) {
            if (portOpen("127.0.0.1", candidate)) {
                com.olympus.iptv.AppLog.info("Dashboard", "Port $candidate is already reachable, choosing another")
                continue
            }
            try {
                val socket = ServerSocket()
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), candidate))
                port = candidate
                return socket
            } catch (error: Exception) {
                com.olympus.iptv.AppLog.error("Dashboard", "Port $candidate is already in use", error)
            }
        }
        return null
    }

    private fun portOpen(host: String, candidate: Int): Boolean {
        if (candidate <= 0) return false
        return try {
            Socket().use { socket ->
                com.olympus.iptv.vpn.AppTunnel.protect(socket)
                socket.connect(InetSocketAddress(host, candidate), 250)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun watchNetwork(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    workers.execute { refreshAddress() }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    workers.execute { refreshAddress() }
                }
            })
        }
    }

    private fun refreshAddress() {
        val options = rankedSites()
        val reachable = options.filter { portOpen(it.second, port) }
        val chosen = (if (reachable.isNotEmpty()) reachable else options)
            .minByOrNull { it.first }
            ?.second
            ?: "127.0.0.1"
        if (chosen == hostName) return
        hostName = chosen
        publishUrl()
    }

    private fun publishUrl() {
        val next = url()
        for (listener in urlListeners) {
            runCatching { listener(next) }
        }
    }

    private fun rankedSites(): List<Pair<Int, String>> {
        val ranked = ArrayList<Pair<Int, String>>()
        linkAddress()?.let { ranked.add(0 to it) }
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ranked.distinctBy { it.second }
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            val name = nic.name.lowercase(Locale.US)
            val rank = when {
                name.contains("wlan") || name.contains("wifi") -> 0
                name.contains("eth") -> 1
                else -> 2
            }
            for (address in nic.inetAddresses) {
                val host = ipv4Site(address) ?: continue
                ranked.add(rank to host)
            }
        }
        return ranked.distinctBy { it.second }
    }

    private fun linkAddress(): String? {
        val context = appContext ?: return null
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val networks = buildList {
            manager.activeNetwork?.let { add(it) }
            addAll(manager.allNetworks)
        }
        val ranked = ArrayList<Pair<Int, String>>()
        for (network in networks.distinct()) {
            val props = manager.getLinkProperties(network) ?: continue
            val capabilities = manager.getNetworkCapabilities(network)
            val rank = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 1
                else -> 2
            }
            for (link in props.linkAddresses) {
                val host = ipv4Site(link.address) ?: continue
                ranked.add(rank to host)
            }
        }
        return ranked.minByOrNull { it.first }?.second
    }

    private fun ipv4Site(address: InetAddress): String? {
        val host = address.hostAddress?.removePrefix("::ffff:")?.substringBefore('%') ?: return null
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return null
        val first = parts[0].toInt()
        val second = parts[1].toInt()
        val site = first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
        if (!site || host == "127.0.0.1") return null
        return host
    }

    private fun handle(context: Context, socket: Socket) {
        if (!privateClient(socket.inetAddress)) {
            writeJson(socket, 403, mapOf("error" to "Dashboard is only available on the home network"))
            return
        }
        socket.soTimeout = 20000
        val input = BufferedInputStream(socket.getInputStream(), 8192)
        val headerText = readHeader(input) ?: return
        val lines = headerText.split("\r\n")
        val request = lines.firstOrNull()?.split(" ") ?: return
        if (request.size < 2) return
        val method = request[0]
        val rawPath = request[1]
        val path = rawPath.substringBefore("?")
        val query = decodeQuery(rawPath.substringAfter("?", ""))
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).trim().lowercase(Locale.US)] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (method == "PUT" && path == "/api/recordings/file") {
            if (length <= 0 || length > MAX_RECORDING_BYTES) {
                writeJson(socket, 413, mapOf("error" to "Recording upload is too large"))
                return
            }
            writeRecording(context, socket, query, input, length)
            return
        }
        if (length > MAX_JSON_BYTES) {
            writeJson(socket, 413, mapOf("error" to "Request is too large"))
            return
        }
        val body = if (length > 0) readBytes(input, length) else ByteArray(0)
        requestGzip.set(headers["accept-encoding"].orEmpty().contains("gzip", ignoreCase = true))
        try {
            route(context, socket, method, path, query, body)
        } finally {
            requestGzip.set(false)
        }
    }

    private fun route(
        context: Context,
        socket: Socket,
        method: String,
        path: String,
        query: Map<String, String>,
        body: ByteArray
    ) {
        val app = context as? OlympusIptvApp ?: (context.applicationContext as OlympusIptvApp)
        when {
            method == "GET" && (path == "/" || path == "/index.html") -> writePortalPage(socket, context)
            method == "GET" && path == "/logo.png" -> writeBytes(socket, 200, "image/png", logoBytes(context), "public, max-age=86400", allowGzip = false)
            method == "GET" && path == "/api/info" -> writeJson(socket, 200, mapOf("url" to url(), "urls" to listOf(url()), "privateNetwork" to true))
            method == "GET" && path == "/api/logs" -> {
                val files = com.olympus.iptv.AppLog.list().map {
                    mapOf("name" to it.name, "bytes" to it.bytes, "current" to it.current)
                }
                writeJson(socket, 200, mapOf("files" to files))
            }
            method == "GET" && path == "/api/log" -> {
                val requested = query["name"]
                val name = com.olympus.iptv.AppLog.downloadName(requested)
                if (!requested.isNullOrBlank() && name == null) {
                    writeBytes(socket, 404, "text/plain; charset=utf-8", "Not found\n".toByteArray(), "no-store")
                } else {
                    val recorded = com.olympus.iptv.AppLog.read(name)
                    val body = if (recorded.isEmpty()) "No errors have been recorded yet.\n".toByteArray() else recorded
                    writeBytes(socket, 200, "text/plain; charset=utf-8", body, "no-store", name ?: "olympus-iptv-log.txt")
                }
            }
            method == "GET" && path == "/api/setup" -> setupStatus(app, socket)
            method == "POST" && path == "/api/setup" -> setupAccount(app, socket, json(body))
            method == "GET" && path == "/api/logo" -> serveLogo(app, socket, query["u"].orEmpty())
            method == "POST" && path == "/api/register" -> register(app, socket, json(body))
            method == "POST" && path == "/api/devices" -> devices(app, socket, json(body))
            method == "POST" && path == "/api/login" -> login(app, socket, json(body))
            method == "GET" && path == "/api/favorites" -> favorites(app, socket, query["email"], query["deviceId"])
            method == "POST" && path == "/api/favorites" -> saveFavorites(app, socket, json(body))
            method == "POST" && path == "/api/favorites/auto" -> autoFavorites(app, socket, json(body))
            method == "GET" && path == "/api/catalog/search" -> search(app, socket, query)
            method == "GET" && path == "/api/catalog/group" -> group(app, socket, query)
            method == "POST" && path == "/api/catalog" -> writeJson(socket, 200, mapOf("ok" to true))
            method == "GET" && path == "/api/profile" -> profile(app, socket, query["email"], query["deviceId"])
            method == "POST" && path == "/api/profile" -> restoreProfile(app, socket, json(body))
            method == "GET" && path == "/api/lineup" -> lineup(app, socket, query["email"], query["deviceId"])
            method == "POST" && path == "/api/lineup" || method == "PUT" && path == "/api/lineup" -> saveLineup(app, socket, json(body))
            method == "POST" && path == "/api/names/strip-images" -> strip(app, socket, json(body))
            method == "POST" && path == "/api/groups/rename" -> renameGroups(app, socket, json(body))
            method == "GET" && path == "/api/recordings" -> recordings(app, socket, query["email"], query["deviceId"])
            method == "POST" && path == "/api/recordings" -> saveRecordings(app, socket, json(body))
            method == "GET" && path == "/api/recordings/file" -> readRecording(app, socket, query)
            method == "POST" && path == "/api/recordings/delete-file" -> deleteRecording(app, socket, json(body))
            method == "GET" && path == "/api/reminders" -> reminders(app, socket, query["email"], query["deviceId"])
            method == "POST" && path == "/api/reminders/update" -> reminderUpdate(app, socket, json(body))
            method == "POST" && path == "/api/reminders/delete" -> reminderDelete(app, socket, json(body))
            method == "GET" && path == "/api/vpn" -> writeJson(socket, 200, com.olympus.iptv.vpn.VpnDesk.status(app))
            method == "POST" && path == "/api/vpn/session" -> vpnSignIn(app, socket, json(body))
            method == "POST" && path == "/api/vpn/connect" -> vpnConnect(app, socket, json(body))
            method == "POST" && path == "/api/vpn/disconnect" -> vpnDisconnect(app, socket, json(body))
            method == "POST" && path == "/api/remote" -> {
                val payload = json(body)
                val action = payload.get("action")?.asString.orEmpty()
                val text = payload.get("text")?.asString.orEmpty()
                RemotePad.post(action, text)
                writeJson(socket, 200, mapOf("ok" to true))
            }
            method == "GET" && path == "/api/remote/favorites" -> remoteFavorites(app, socket)
            method == "POST" && path == "/api/remote/favorites" -> saveRemoteFavorites(app, socket, json(body))
            method == "GET" && path == "/api/streams" -> activeStreams(app, socket)
            method == "GET" && path == "/api/stream/probe" -> probeStream(app, socket, query["channelId"].orEmpty())
            method == "POST" && path == "/api/refresh" -> triggerCatalogRefresh(app, socket, json(body))
            method == "POST" && path == "/api/stream/health/clear" -> clearDeadStreams(app, socket, json(body))
            else -> writeJson(socket, 404, mapOf("error" to "Not found"))
        }
    }

    private fun triggerCatalogRefresh(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        if (gate(app, text(body, "email"), text(body, "deviceId")) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        val playlist = runBlocking { app.playlistStore.playlists.first().firstOrNull() }
        if (playlist == null) {
            writeJson(socket, 404, mapOf("error" to "No playlist configured on this device"))
            return
        }
        if (app.catalogRefresh?.isActive == true) {
            writeJson(socket, 200, mapOf("ok" to true, "running" to true, "message" to "Catalog sync is already running on the TV."))
            return
        }
        app.appScope.launch {
            runCatching {
                val loader = com.olympus.iptv.playlist.PlaylistLoader()
                val loaded = loader.load(playlist)
                val db = app.catalogDb
                db.insertMissing(playlist.id, com.olympus.iptv.playlist.CatalogDb.LIVE, loaded.liveCategories, loaded.liveChannels)
                db.insertMissing(playlist.id, com.olympus.iptv.playlist.CatalogDb.VOD, loaded.vodCategories, loaded.vodChannels)
                db.insertMissing(playlist.id, com.olympus.iptv.playlist.CatalogDb.SERIES, loaded.seriesCategories.orEmpty(), loaded.seriesChannels.orEmpty())
                app.playlistStore.saveLastRefreshAt(playlist.id, System.currentTimeMillis())
            }
        }
        writeJson(socket, 200, mapOf("ok" to true, "running" to true, "message" to "Started full catalog sync on the TV."))
    }

    private fun clearDeadStreams(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        if (gate(app, text(body, "email"), text(body, "deviceId")) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        val playlist = runBlocking { app.playlistStore.playlists.first().firstOrNull() }
        if (playlist != null) {
            runCatching {
                app.catalogDb.writableDatabase.delete("stream_health", "playlist_id=?", arrayOf(playlist.id))
            }
        }
        writeJson(socket, 200, mapOf("ok" to true))
    }

    private fun probeStream(app: OlympusIptvApp, socket: Socket, channelId: String) {
        if (channelId.isBlank()) {
            writeJson(socket, 400, mapOf("ok" to false, "error" to "Missing channelId"))
            return
        }
        val playlist = runBlocking { app.playlistStore.playlists.first().firstOrNull() }
        val channel = playlist?.let { saved ->
            runCatching {
                app.catalogDb.readChannel(saved.id, com.olympus.iptv.playlist.CatalogDb.LIVE, channelId)
                    ?: app.catalogDb.readChannel(saved.id, com.olympus.iptv.playlist.CatalogDb.VOD, channelId)
            }.getOrNull()
        }
        val url = channel?.streamUrl.orEmpty()
        if (url.isBlank()) {
            writeJson(socket, 404, mapOf("ok" to false, "error" to "Stream not found"))
            return
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val client = HttpClients.shared.newBuilder()
            .connectTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val outcome = runCatching {
            val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-1023").get().build()
            client.newCall(req).execute().use { resp ->
                resp.code
            }
        }
        val latencyMs = android.os.SystemClock.elapsedRealtime() - started
        val code = outcome.getOrNull() ?: 0
        val ok = code in 200..399
        writeJson(socket, 200, mapOf("ok" to ok, "status" to code, "latencyMs" to latencyMs))
    }

    private fun activeStreams(app: OlympusIptvApp, socket: Socket) {
        val playlist = runBlocking { app.playlistStore.playlists.first().firstOrNull() }
        val status = playlist?.let { saved ->
            runCatching { runBlocking { XtreamClient().accountStatus(saved) } }.getOrNull()
        }
        val ip = linkAddress().orEmpty()
        val local = StreamSessions.list().map { row ->
            mapOf("ip" to ip, "name" to row.name, "role" to row.role, "source" to "This TV")
        }
        val remote = status?.connections.orEmpty().map { (address, name) ->
            mapOf("ip" to address, "name" to name, "role" to "", "source" to "Provider")
        }
        writeJson(socket, 200, mapOf(
            "deviceIp" to ip,
            "providerActive" to (status?.activeConnections ?: "—"),
            "providerMax" to (status?.maxConnections ?: "—"),
            "streams" to (remote.ifEmpty { local }),
            "local" to local,
            "providerListedIps" to remote.isNotEmpty()
        ))
    }

    private fun vpnSignIn(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        if (gate(app, text(body, "email"), text(body, "deviceId")) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        val result = runCatching {
            com.olympus.iptv.vpn.VpnDesk.signIn(
                app,
                text(body, "provider"),
                text(body, "username"),
                text(body, "password"),
                text(body, "code"),
                text(body, "profile")
            )
        }
        result.onSuccess {
            val payload = com.olympus.iptv.vpn.VpnDesk.status(app).toMutableMap()
            payload["ok"] = true
            writeJson(socket, 200, payload)
        }
        result.onFailure { error ->
            val message = if (error is com.olympus.iptv.vpn.VpnAuthException) error.message else null
            writeJson(socket, 400, mapOf("error" to (message ?: "The VPN service refused the sign-in.")))
        }
    }

    private fun reminders(app: OlympusIptvApp, socket: Socket, email: String?, deviceId: String?) {
        if (gate(app, email, deviceId) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        writeJson(socket, 200, mapOf("reminders" to reminderRows(app)))
    }

    private fun reminderUpdate(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        if (gate(app, text(body, "email"), text(body, "deviceId")) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        val minutes = text(body, "minutes").toIntOrNull() ?: 0
        if (minutes != 15 && minutes != 30 && minutes != 45 && minutes != 60) {
            writeJson(socket, 400, mapOf("error" to "Choose 15, 30, 45, or 60 minutes."))
            return
        }
        val updated = com.olympus.iptv.remind.ReminderStore.retimed(app, text(body, "id"), minutes)
        if (updated == null) {
            writeJson(socket, 400, mapOf("error" to "That reminder is gone."))
            return
        }
        com.olympus.iptv.remind.ReminderClock.arm(app)
        writeJson(socket, 200, mapOf("ok" to true, "reminders" to reminderRows(app)))
    }

    private fun reminderDelete(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        if (gate(app, text(body, "email"), text(body, "deviceId")) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        com.olympus.iptv.remind.ReminderStore.remove(app, text(body, "id"))
        com.olympus.iptv.remind.ReminderClock.arm(app)
        writeJson(socket, 200, mapOf("ok" to true, "reminders" to reminderRows(app)))
    }

    private fun reminderRows(app: OlympusIptvApp): List<Map<String, Any?>> {
        return com.olympus.iptv.remind.ReminderStore.listed(app).map { item ->
            mapOf(
                "id" to item.id.orEmpty(),
                "channelNumber" to (item.channelNumber ?: 0),
                "channelName" to item.channelName.orEmpty(),
                "title" to item.title.orEmpty(),
                "startMillis" to (item.startMillis ?: 0L),
                "leadMinutes" to (item.leadMinutes ?: 0)
            )
        }
    }

    private fun vpnConnect(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        if (gate(app, text(body, "email"), text(body, "deviceId")) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        val result = runCatching {
            com.olympus.iptv.vpn.VpnDesk.connect(app, text(body, "cityId"), text(body, "backupCityId"))
        }
        result.onSuccess { needsConsent ->
            val payload = com.olympus.iptv.vpn.VpnDesk.status(app).toMutableMap()
            payload["ok"] = true
            payload["needsConsent"] = needsConsent
            writeJson(socket, 200, payload)
        }
        result.onFailure { error ->
            val message = if (error is com.olympus.iptv.vpn.VpnAuthException) error.message else null
            writeJson(socket, 400, mapOf("error" to (message ?: "The VPN service refused the sign-in.")))
        }
    }

    private fun vpnDisconnect(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        if (gate(app, text(body, "email"), text(body, "deviceId")) == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        com.olympus.iptv.vpn.VpnDesk.disconnect(app)
        val payload = com.olympus.iptv.vpn.VpnDesk.status(app).toMutableMap()
        payload["ok"] = true
        writeJson(socket, 200, payload)
    }

    private fun register(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId"))
        if (gate == null) {
            writeJson(socket, 400, mapOf("error" to "Email and device ID are required"))
            return
        }
        writeJson(socket, 200, mapOf("ok" to true, "devices" to listOf(gate.deviceId)))
    }

    private fun devices(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val email = text(body, "email")
        val session = session(app)
        val saved = session?.email?.trim()?.lowercase().orEmpty()
        if (session == null || saved.isBlank() || email.trim().lowercase() != saved) {
            writeJson(socket, 401, mapOf("error" to "That email is not on a TV yet. Open Olympus-IPTV once with this email."))
            return
        }
        val deviceId = runBlocking { app.playlistStore.deviceId() }
        val lineup = runBlocking { app.playlistStore.lineup(session.id) }
        writeJson(
            socket,
            200,
            mapOf(
                "ok" to true,
                "email" to saved,
                "devices" to listOf(
                    mapOf(
                        "id" to deviceId,
                        "hiddenCountries" to lineup.hiddenCountries.orEmpty().size,
                        "hiddenGroups" to lineup.hiddenGroups.orEmpty().size,
                        "hiddenChannels" to lineup.hiddenChannels.orEmpty().size
                    )
                )
            )
        )
    }

    private fun login(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId"))
        if (gate == null) {
            writeJson(socket, 401, mapOf("error" to "That device is not registered yet. Open Olympus-IPTV on the TV once with this email."))
            return
        }
        val playlistId = gate.playlist.id
        clearGuideCache()
        val page = runBlocking { app.playlistStore.savedPage(playlistId) }
        writeJson(
            socket,
            200,
            mapOf(
                "ok" to true,
                "email" to gate.playlist.email?.trim()?.lowercase().orEmpty(),
                "deviceId" to gate.deviceId,
                "devices" to listOf(mapOf("id" to gate.deviceId)),
                "catalog" to catalog(app, playlistId),
                "lineup" to filled(page.favorites.lineup),
                "refreshedAt" to page.refreshedAt,
                "favorites" to publicFavorites(app, playlistId, page.favorites),
                "recordings" to page.recordings
            )
        )
    }

    private fun remoteFavorites(app: OlympusIptvApp, socket: Socket) {
        val playlist = runBlocking { app.playlistStore.playlists.first().firstOrNull() }
        if (playlist == null) {
            writeJson(socket, 404, mapOf("error" to "No playlist"))
            return
        }
        val ids = runBlocking { app.playlistStore.favorites(playlist.id) }
        val bareIds = ids.map { it.substringAfter(':') }.distinct()
        val liveById = app.catalogDb.readChannelsByIds(playlist.id, CatalogDb.LIVE, bareIds).associateBy { it.id }
        val vodById = if (liveById.size < bareIds.size) {
            app.catalogDb.readChannelsByIds(playlist.id, CatalogDb.VOD, bareIds.filter { it !in liveById }).associateBy { it.id }
        } else {
            emptyMap()
        }
        val rows = ids.map { id ->
            val bare = id.substringAfter(':')
            val channel = liveById[bare] ?: vodById[bare]
            mapOf("id" to id, "name" to (channel?.name ?: bare))
        }
        writeJson(socket, 200, mapOf("favorites" to rows))
    }

    private fun saveRemoteFavorites(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val playlist = runBlocking { app.playlistStore.playlists.first().firstOrNull() }
        if (playlist == null) {
            writeJson(socket, 404, mapOf("error" to "No playlist"))
            return
        }
        val ids = body.getAsJsonArray("ids")?.map { it.asString }?.filter { it.isNotBlank() }.orEmpty()
        runBlocking { app.playlistStore.saveFavorites(playlist.id, ids, System.currentTimeMillis()) }
        writeJson(socket, 200, mapOf("ok" to true))
    }

    private fun favorites(app: OlympusIptvApp, socket: Socket, email: String?, deviceId: String?) {
        val gate = gate(app, email, deviceId) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        writeJson(socket, 200, publicFavorites(app, gate.playlist.id))
    }

    private fun saveFavorites(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val playlistId = gate.playlist.id
        val current = runBlocking { app.playlistStore.favorites(playlistId) }.toMutableList()
        val dropped = removed.getOrPut(playlistId) { mutableListOf() }
        val add = text(body, "add")
        val remove = text(body, "remove")
        var ids = current.toMutableList()
        if (add.isNotBlank()) {
            dropped.remove(add)
            if (!ids.contains(add)) ids.add(add)
        } else if (remove.isNotBlank()) {
            ids.remove(remove)
            if (!dropped.contains(remove)) dropped.add(remove)
        } else if (body.has("order") && body.get("order").isJsonArray) {
            val posted = ArrayList<String>()
            for (item in body.getAsJsonArray("order")) {
                val id = item.asString.trim()
                if (id.isNotBlank() && id !in posted && id in ids) posted.add(id)
            }
            ids = (posted + ids.filter { it !in posted }).toMutableList()
        } else if (body.has("ids") && body.get("ids").isJsonArray) {
            val incoming = body.getAsJsonArray("ids").map { it.asString.trim() }.filter { it.isNotBlank() }
            val base = body.get("baseRevision")?.asLong ?: 0L
            val serverRevision = runBlocking { app.playlistStore.favoritesRevision(playlistId) }
            ids = if (base < serverRevision) {
                val merged = incoming.filter { it !in dropped }.toMutableList()
                for (id in current) if (id !in merged) merged.add(id)
                merged
            } else {
                dropped.removeAll { it in incoming }
                incoming.toMutableList()
            }
        }
        if (ids != current) {
            runBlocking { app.playlistStore.saveFavorites(playlistId, ids, System.currentTimeMillis()) }
        }
        writeJson(socket, 200, mapOf("ok" to true, "favorites" to publicFavorites(app, playlistId)))
    }

    private fun autoFavorites(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val playlistId = gate.playlist.id
        val channels = guide(app, playlistId)
        val mode = text(body, "mode")
        if (mode == "teams") {
            val teams = AutoFavorites.teamsFor(text(body, "sport"), channels).map { it.name }
            writeJson(socket, 200, mapOf("ok" to true, "teams" to teams))
            return
        }
        val place = gate.playlist.homeLat to gate.playlist.homeLon
        val picks: List<AutoFavorites.ChannelPick>
        var market = ""
        when (mode) {
            "local" -> {
                val result = if (place.first != null && place.second != null) {
                    AutoFavorites.localPicks(channels, place.first!!, place.second!!)
                } else {
                    AutoFavorites.LocalResult("", AutoFavorites.nationalPicks(channels))
                }
                market = result.market
                picks = result.picks
            }
            "sports" -> picks = AutoFavorites.sportsPicks(channels)
            "filo", "utoob", "fubo", "directv", "frndly", "hulu" -> {
                val result = AutoFavorites.packagePicks(mode, channels, place.first, place.second)
                market = result.market
                picks = result.picks
            }
            "sling-orange", "sling-blue" -> {
                val result = AutoFavorites.slingPicks(mode == "sling-orange", channels, place.first, place.second)
                market = result.market
                picks = result.picks
            }
            "add-teams" -> {
                val names = if (body.has("teams") && body.get("teams").isJsonArray) {
                    body.getAsJsonArray("teams").map { it.asString }
                } else {
                    emptyList()
                }
                picks = AutoFavorites.picksForTeams(text(body, "sport"), channels, names)
            }
            else -> {
                writeJson(socket, 400, mapOf("error" to "Unknown favorites request"))
                return
            }
        }
        val counts = applyPicks(app, playlistId, picks)
        val favorites = runBlocking { app.playlistStore.favoritePage(playlistId) }
        writeJson(
            socket,
            200,
            mapOf(
                "ok" to true,
                "market" to market,
                "city" to "",
                "added" to counts.first,
                "already" to counts.second,
                "favorites" to publicFavorites(app, playlistId, favorites),
                "lineup" to filled(favorites.lineup)
            )
        )
    }

    private fun applyPicks(app: OlympusIptvApp, playlistId: String, picks: List<AutoFavorites.ChannelPick>): Pair<Int, Int> {
        val ids = runBlocking { app.playlistStore.favorites(playlistId) }.toMutableList()
        val dropped = removed.getOrPut(playlistId) { mutableListOf() }
        val lineup = runBlocking { app.playlistStore.lineup(playlistId) }
        val names = lineup.channelNames.orEmpty().toMutableMap()
        var added = 0
        var already = 0
        var labeled = false
        for (pick in picks) {
            val key = "live:${pick.id}"
            if (key in ids) already += 1
            else {
                ids.add(key)
                added += 1
            }
            dropped.remove(key)
            if (pick.label.isNotBlank()) {
                names[pick.id] = pick.label
                labeled = true
            }
        }
        val revision = System.currentTimeMillis()
        runBlocking {
            app.playlistStore.saveFavorites(playlistId, ids, revision)
            if (labeled) app.playlistStore.saveLineup(playlistId, filled(lineup).copy(channelNames = names, revision = revision))
        }
        return added to already
    }

    private fun search(app: OlympusIptvApp, socket: Socket, query: Map<String, String>) {
        val gate = gate(app, query["email"], query["deviceId"]) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val text = query["q"].orEmpty().trim()
        val limit = (query["limit"]?.toIntOrNull() ?: 40).coerceIn(1, 80)
        val kind = query["kind"].orEmpty().uppercase(Locale.US)
        val buckets = when (kind) {
            "VOD" -> listOf(CatalogDb.VOD)
            "SERIES" -> listOf(CatalogDb.SERIES)
            "LIVE" -> listOf(CatalogDb.LIVE)
            else -> listOf(CatalogDb.LIVE, CatalogDb.VOD, CatalogDb.SERIES)
        }
        val rows = ArrayList<Map<String, String>>()
        for (bucket in buckets) {
            if (rows.size >= limit) break
            val found = app.catalogDb.searchChannels(gate.playlist.id, bucket, text, limit - rows.size)
            val kindLabel = when (bucket) {
                CatalogDb.VOD -> "VOD"
                CatalogDb.SERIES -> "SERIES"
                else -> "LIVE"
            }
            for (channel in found) {
                val slim = mapOf(
                    "id" to channel.id,
                    "name" to channel.name,
                    "groupId" to (channel.categoryId ?: channel.groupTitle.orEmpty()),
                    "kind" to kindLabel,
                    "logo" to channel.logoUrl.orEmpty()
                )
                rememberLogo(channel.logoUrl)
                rows.add(slim)
            }
        }
        writeJson(socket, 200, mapOf("channels" to rows))
    }

    private fun group(app: OlympusIptvApp, socket: Socket, query: Map<String, String>) {
        val gate = gate(app, query["email"], query["deviceId"]) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val groupId = query["groupId"].orEmpty()
        val groupName = query["groupName"].orEmpty()
        val needle = query["q"].orEmpty().trim().lowercase(Locale.US)
        val limit = (query["limit"]?.toIntOrNull() ?: 400).coerceIn(1, 400)
        val lineup = runBlocking { app.playlistStore.lineup(gate.playlist.id) }
        val savedPin = lineup.groupPins.orEmpty()[groupId]
        if (!savedPin.isNullOrBlank()) {
            val given = query["pin"].orEmpty()
            if (!GroupLock.accepts(lineup, groupId, given)) {
                writeJson(socket, 403, mapOf("error" to "Incorrect password"))
                return
            }
        }
        val order = lineup.channelOrder.orEmpty()[groupId].orEmpty()
        val page: List<CatalogDb.SlimChannel>
        val total: Int
        if (order.isEmpty()) {
            page = app.catalogDb.slimInGroup(gate.playlist.id, groupId, needle, limit, byName = true, groupName = groupName)
            total = if (page.size < limit) page.size else app.catalogDb.countInGroup(gate.playlist.id, groupId, needle, groupName = groupName)
        } else {
            val rank = HashMap<String, Int>(order.size)
            order.forEachIndexed { index, id -> rank[id] = index }
            val fetchCap = (limit * 8).coerceIn(limit, 800)
            val matches = app.catalogDb.slimInGroup(
                gate.playlist.id,
                groupId,
                needle,
                limit = fetchCap,
                groupName = groupName
            )
            page = matches.sortedWith(compareBy({ rank[it.id] ?: 999999 }, { it.name.lowercase(Locale.US) })).take(limit)
            total = if (matches.size < fetchCap) matches.size else app.catalogDb.countInGroup(gate.playlist.id, groupId, needle, groupName = groupName)
        }
        val pageRows = page.map { channel ->
            rememberLogo(channel.logo)
            mapOf(
                "id" to channel.id,
                "name" to channel.name,
                "groupId" to channel.groupId,
                "kind" to channel.kind,
                "logo" to channel.logo
            )
        }
        writeJson(socket, 200, mapOf("channels" to pageRows, "total" to total))
    }

    private fun profile(app: OlympusIptvApp, socket: Socket, email: String?, deviceId: String?) {
        val gate = gate(app, email, deviceId) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val lineup = filled(runBlocking { app.playlistStore.lineup(gate.playlist.id) })
        val favorites = runBlocking { app.playlistStore.favorites(gate.playlist.id) }
        writeJson(
            socket,
            200,
            mapOf(
                "olympus-iptv" to "profile",
                "deviceId" to gate.deviceId,
                "exportedAt" to System.currentTimeMillis().toString(),
                "lineup" to lineup,
                "favorites" to favorites,
                "avSync" to com.olympus.iptv.data.AvSyncStore.all(app)
            )
        )
    }

    private fun restoreProfile(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val incoming = body.get("lineup")?.let { gson.fromJson(it, LineupConfig::class.java) }
        if (incoming != null) {
            val next = filled(incoming).let { config ->
                if ((config.revision ?: 0L) == 0L) config.copy(revision = System.currentTimeMillis()) else config
            }
            runBlocking { app.playlistStore.saveLineup(gate.playlist.id, next) }
        }
        if (body.has("favorites") && body.get("favorites").isJsonArray) {
            val ids = body.getAsJsonArray("favorites").map { it.asString.trim() }.filter { it.isNotBlank() }
            runBlocking { app.playlistStore.saveFavorites(gate.playlist.id, ids, System.currentTimeMillis()) }
        }
        if (body.has("avSync") && body.get("avSync").isJsonObject) {
            val offsets = LinkedHashMap<String, Int>()
            for ((key, value) in body.getAsJsonObject("avSync").entrySet()) {
                if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) offsets[key] = value.asInt
            }
            com.olympus.iptv.data.AvSyncStore.replace(app, offsets)
        }
        writeJson(socket, 200, mapOf("ok" to true))
    }

    private fun lineup(app: OlympusIptvApp, socket: Socket, email: String?, deviceId: String?) {
        val gate = gate(app, email, deviceId) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val config = filled(runBlocking { app.playlistStore.lineup(gate.playlist.id) })
        writeJson(socket, 200, mapOf("lineup" to config))
    }

    private fun saveLineup(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val current = filled(runBlocking { app.playlistStore.lineup(gate.playlist.id) })
        val base = body.get("baseRevision")?.asLong ?: 0L
        if (base > 0L && (current.revision ?: 0L) > base) {
            writeJson(socket, 200, mapOf("ok" to true, "lineup" to current, "kept" to true))
            return
        }
        val incoming = body.get("lineup")?.let { gson.fromJson(it, LineupConfig::class.java) } ?: LineupConfig()
        val next = filled(incoming).let { config ->
            if ((config.revision ?: 0L) == 0L) config.copy(revision = System.currentTimeMillis()) else config
        }
        if (gson.toJson(next) != gson.toJson(current)) {
            runBlocking { app.playlistStore.saveLineup(gate.playlist.id, next) }
        }
        writeJson(socket, 200, mapOf("ok" to true, "lineup" to next))
    }

    private fun renameGroups(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val find = text(body, "find")
        if (find.isBlank()) {
            writeJson(socket, 400, mapOf("error" to "Type the text to find first."))
            return
        }
        val replacement = if (body.has("replace") && body.get("replace").isJsonPrimitive) {
            body.get("replace").asString
        } else {
            ""
        }
        val result = app.catalogDb.replaceGroupLabels(gate.playlist.id, find, replacement)
        clearGuideCache()
        val lineup = filled(runBlocking { app.playlistStore.lineup(gate.playlist.id) })
        val names = lineup.groupNames.orEmpty().mapNotNull { (id, name) ->
            val next = com.olympus.iptv.data.GroupRename.replaceAll(name, find, replacement).trim()
            if (next.isEmpty()) null else id to next
        }.toMap()
        val next = lineup.copy(groupNames = names, revision = System.currentTimeMillis())
        runBlocking { app.playlistStore.saveLineup(gate.playlist.id, next) }
        writeJson(
            socket,
            200,
            mapOf(
                "ok" to true,
                "changed" to result.first,
                "examples" to result.second.map { mapOf("before" to it.before, "after" to it.after) },
                "lineup" to next
            )
        )
    }

    private fun strip(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val result = app.catalogDb.stripImageNames(gate.playlist.id)
        clearGuideCache()
        val lineup = filled(runBlocking { app.playlistStore.lineup(gate.playlist.id) })
        val groups = lineup.groupNames.orEmpty().mapValues { NameImages.strip(it.value) }
        val channels = lineup.channelNames.orEmpty().mapValues { NameImages.strip(it.value) }
        val next = lineup.copy(groupNames = groups, channelNames = channels, revision = System.currentTimeMillis())
        runBlocking { app.playlistStore.saveLineup(gate.playlist.id, next) }
        writeJson(
            socket,
            200,
            mapOf(
                "ok" to true,
                "changed" to result.first,
                "examples" to result.second.map { mapOf("before" to it.before, "after" to it.after) },
                "lineup" to next
            )
        )
    }

    private fun recordings(app: OlympusIptvApp, socket: Socket, email: String?, deviceId: String?) {
        val gate = gate(app, email, deviceId) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        writeJson(socket, 200, runBlocking { app.playlistStore.recordingBook(gate.playlist.id) })
    }

    private fun saveRecordings(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val previous = runBlocking { app.playlistStore.recordingBook(gate.playlist.id) }
        val incoming = body.get("recordings")?.let { gson.fromJson(it, RecordingBook::class.java) } ?: RecordingBook()
        val smb = incoming.smb ?: previous.smb
        val password = if (smb?.password.isNullOrBlank()) previous.smb?.password else smb?.password
        val next = incoming.copy(
            smb = (smb ?: SmbSettings()).copy(password = password),
            revision = incoming.revision ?: System.currentTimeMillis()
        )
        if ((next.revision ?: 0L) >= previous.revisionOrZero()) {
            runBlocking { app.playlistStore.saveRecordingBook(gate.playlist.id, next) }
        }
        val saved = runBlocking { app.playlistStore.recordingBook(gate.playlist.id) }
        writeJson(socket, 200, mapOf("ok" to true, "recordings" to saved))
    }

    private fun readRecording(app: OlympusIptvApp, socket: Socket, query: Map<String, String>) {
        val gate = gate(app, query["email"], query["deviceId"]) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val id = safePart(query["id"])
        val book = runBlocking { app.playlistStore.recordingBook(gate.playlist.id) }
        val item = book.itemsOrEmpty().firstOrNull { it.id == id }
        if (item == null || id == null) {
            writeJson(socket, 404, mapOf("error" to "Recording not found"))
            return
        }
        val name = item.fileName?.takeIf { it.isNotBlank() }
        val file = name?.let { RecordingFiles.file(app, it) }
        if (file == null || !file.isFile) {
            writeJson(socket, 404, mapOf("error" to "That recording is still on the TV. It can be downloaded after it finishes."))
            return
        }
        val download = (name).replace(Regex("[^\\w. -]"), "")
        writeFile(socket, file, "video/mp2t", download.ifBlank { "recording.ts" })
    }

    private fun deleteRecording(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val gate = gate(app, text(body, "email"), text(body, "deviceId")) ?: run {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val id = safePart(text(body, "id"))
        if (id != null) {
            val book = runBlocking { app.playlistStore.recordingBook(gate.playlist.id) }
            val name = book.itemsOrEmpty().firstOrNull { it.id == id }?.fileName
            if (!name.isNullOrBlank()) RecordingFiles.file(app, name).delete()
        }
        writeJson(socket, 200, mapOf("ok" to true))
    }

    private fun writeRecording(context: Context, socket: Socket, query: Map<String, String>, input: java.io.InputStream, length: Int) {
        val app = context.applicationContext as OlympusIptvApp
        val gate = gate(app, query["email"], query["deviceId"])
        val id = safePart(query["id"])
        if (gate == null || id == null) {
            writeJson(socket, 401, mapOf("error" to "Device not registered"))
            return
        }
        val file = RecordingFiles.file(app, "$id.ts")
        FileOutputStream(file).use { output ->
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, left))
                if (read < 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
        writeJson(socket, 200, mapOf("ok" to true))
    }

    private fun catalog(app: OlympusIptvApp, playlistId: String): Map<String, Any> {
        val snapshot = app.catalogDb.dashboardSnapshot(playlistId)
        catalogHold?.let { if (it.first === snapshot) return it.second }
        val groups = snapshot.groups.map { group ->
            mapOf(
                "id" to group.id,
                "name" to group.name,
                "kind" to group.kind,
                "count" to group.count,
                "countryId" to group.countryId
            )
        }
        val countries = snapshot.countries.map { country ->
            mapOf("id" to country.id, "label" to country.label, "count" to country.count)
        }
        val body = mapOf(
            "groups" to groups,
            "countries" to countries,
            "channelCount" to snapshot.channelCount
        )
        catalogHold = snapshot to body
        return body
    }

    private fun publicFavorites(
        app: OlympusIptvApp,
        playlistId: String,
        page: com.olympus.iptv.data.FavoritePage? = null
    ): Map<String, Any> {
        val loaded = page ?: runBlocking { app.playlistStore.favoritePage(playlistId) }
        val ids = loaded.ids
        val revision = loaded.revision
        val lineup = loaded.lineup
        val labels = LinkedHashMap<String, String>()
        val logos = LinkedHashMap<String, String>()
        val names = lineup.channelNames.orEmpty()
        val grouped = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (key in ids) {
            val split = key.indexOf(':')
            if (split <= 0) continue
            val bucket = when (key.substring(0, split)) {
                "vod" -> CatalogDb.VOD
                "series" -> CatalogDb.SERIES
                else -> CatalogDb.LIVE
            }
            grouped.getOrPut(bucket) { ArrayList() }.add(key to key.substring(split + 1))
        }
        for ((bucket, pairs) in grouped) {
            val found = app.catalogDb.readChannelsByIds(playlistId, bucket, pairs.map { it.second }).associateBy { it.id }
            for ((key, id) in pairs) {
                val channel = found[id] ?: continue
                labels[key] = names[channel.id] ?: channel.name
                val logo = channel.logoUrl.orEmpty()
                if (logo.isNotBlank()) {
                    logos[key] = logo
                    rememberLogo(logo)
                }
            }
        }
        return mapOf(
            "ids" to ids,
            "removed" to removed[playlistId].orEmpty(),
            "revision" to revision,
            "labels" to labels,
            "logos" to logos
        )
    }

    private fun guide(app: OlympusIptvApp, playlistId: String): List<AutoFavorites.GuideChannel> {
        val stamp = app.catalogDb.libraryStamp(playlistId)
        val held = guideHold
        if (held != null && held.playlistId == playlistId && held.stamp == stamp) return held.rows
        val rows = app.catalogDb.liveGuide(playlistId)
        guideHold = GuideHold(playlistId, stamp, rows)
        return rows
    }

    private fun serveLogo(app: OlympusIptvApp, socket: Socket, target: String) {
        if (!publicLogo(target)) {
            writeJson(socket, 404, mapOf("error" to "Logo unavailable"))
            return
        }
        val playlistId = session(app)?.id.orEmpty()
        val allowed = logoAllow[target] ?: app.catalogDb.hasLogo(playlistId, target).also { logoAllow[target] = it }
        if (!allowed) {
            writeJson(socket, 404, mapOf("error" to "Logo unavailable"))
            return
        }
        val hash = MessageDigest.getInstance("SHA-1").digest(target.toByteArray()).joinToString("") { "%02x".format(it) }
        logoMemCache.get(hash)?.let { (cachedType, cachedBytes) ->
            writeBytes(socket, 200, cachedType, cachedBytes, "public, max-age=86400, immutable", allowGzip = false)
            return
        }
        val file = File(app.cacheDir, "dashboard-logos/$hash")
        val typeFile = File(app.cacheDir, "dashboard-logos/$hash.type")
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > 7L * 24 * 60 * 60 * 1000) {
            val request = Request.Builder().url(target).header("User-Agent", "Olympus-IPTV").build()
            val fetched = runCatching {
                logos.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val type = response.header("Content-Type").orEmpty().substringBefore(";").ifBlank { "image/jpeg" }
                    if (!type.startsWith("image/")) return@use false
                    val declared = response.body?.contentLength() ?: -1L
                    if (declared > MAX_LOGO_BYTES) return@use false
                    val source = response.body?.source() ?: return@use false
                    val buffer = okio.Buffer()
                    var read = 0L
                    while (!source.exhausted()) {
                        val chunk = source.read(buffer, 64 * 1024)
                        if (chunk < 0) break
                        read += chunk
                        if (read > MAX_LOGO_BYTES) {
                            buffer.clear()
                            return@use false
                        }
                    }
                    val bytes = buffer.readByteArray()
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    typeFile.writeText(type)
                    logoMemCache.put(hash, type to bytes)
                    true
                }
            }.getOrDefault(false)
            if (!fetched || !file.isFile) {
                writeJson(socket, 404, mapOf("error" to "Logo unavailable"))
                return
            }
        }
        val type = if (typeFile.isFile) typeFile.readText() else "image/jpeg"
        val diskBytes = runCatching { file.readBytes() }.getOrNull()
        if (diskBytes != null) {
            logoMemCache.put(hash, type to diskBytes)
            writeBytes(socket, 200, type, diskBytes, "public, max-age=86400, immutable", allowGzip = false)
        } else {
            writeFile(socket, file, type, null)
        }
    }

    private fun rememberLogo(url: String?) {
        if (url.isNullOrBlank() || !publicLogo(url)) return
        if (logoAllow.size >= LOGO_ALLOW_CAP) {
            val drop = logoAllow.keys.take(logoAllow.size / 4)
            for (key in drop) logoAllow.remove(key)
        }
        logoAllow[url] = true
    }

    fun clearGuideCache() {
        guideHold = null
        catalogHold = null
    }

    private fun publicLogo(value: String): Boolean {
        val host = runCatching { java.net.URI(value).host?.lowercase(Locale.US) }.getOrNull() ?: return false
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        if (host == "localhost" || host.endsWith(".local") || host == "127.0.0.1") return false
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return true
            if (second in 16..31) return false
        }
        return true
    }

    private data class Gate(val playlist: SavedPlaylist, val deviceId: String)

    private fun gate(app: OlympusIptvApp, email: String?, deviceId: String?): Gate? {
        val session = session(app) ?: return null
        val saved = session.email?.trim()?.lowercase().orEmpty()
        val postedEmail = email?.trim()?.lowercase().orEmpty()
        val postedDevice = deviceId?.trim().orEmpty()
        if (saved.isBlank() || postedEmail != saved || postedDevice.isBlank()) return null
        val id = cachedDeviceId ?: runBlocking { app.playlistStore.deviceId() }.also { cachedDeviceId = it }
        if (postedDevice != id) return null
        return Gate(session, id)
    }

    private fun session(app: OlympusIptvApp): SavedPlaylist? = runBlocking { app.playlistStore.session.first() }

    private fun filled(config: LineupConfig): LineupConfig = LineupConfig(
        groupOrder = config.groupOrder ?: emptyList(),
        hiddenGroups = config.hiddenGroups ?: emptyList(),
        hiddenChannels = config.hiddenChannels ?: emptyList(),
        groupNames = config.groupNames ?: emptyMap(),
        channelNames = config.channelNames ?: emptyMap(),
        channelOrder = config.channelOrder ?: emptyMap(),
        hiddenCountries = config.hiddenCountries ?: emptyList(),
        includeInternational = config.includeInternational ?: false,
        namePrefixes = config.namePrefixes ?: emptyList(),
        nameSuffixes = config.nameSuffixes ?: emptyList(),
        nameEpg = config.nameEpg ?: false,
        autoCleanNames = config.autoCleanNames ?: true,
        groupFind = config.groupFind,
        groupReplace = config.groupReplace,
        groupPins = config.groupPins,
        revision = config.revision ?: 0L
    )

    private fun setupStatus(app: OlympusIptvApp, socket: Socket) {
        val current = session(app)
        val ready = current != null && !current.email.isNullOrBlank() && when (current.type) {
            PlaylistType.M3U -> !current.m3uUrl.isNullOrBlank()
            PlaylistType.XTREAM -> !current.xtreamHost.isNullOrBlank()
        }
        writeJson(socket, 200, mapOf("ready" to ready))
    }

    private fun setupAccount(app: OlympusIptvApp, socket: Socket, body: JsonObject) {
        val email = text(body, "email")
        val zip = text(body, "zip").filter { it.isDigit() }
        if (!email.contains("@") || zip.length != 5) {
            writeJson(socket, 400, mapOf("error" to "Email and a 5-digit ZIP are required"))
            return
        }
        val home = lookupZip(zip)
        if (home == null) {
            writeJson(socket, 400, mapOf("error" to "That ZIP code was not found"))
            return
        }
        val includeVod = flag(body, "includeVod", true)
        val includeInternational = flag(body, "includeInternational", false)
        val playlist = try {
            buildSetupPlaylist(body, email, zip, home, includeVod, includeInternational)
        } catch (error: Exception) {
            writeJson(socket, 400, mapOf("error" to (error.message ?: "Check the playlist details")))
            return
        }
        val ok = try {
            runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(20_000L) { PlaylistLoader().validate(playlist) }
            }
        } catch (error: Exception) {
            writeJson(socket, 400, mapOf("error" to (error.message ?: "Could not reach the playlist")))
            return
        }
        if (ok != true) {
            writeJson(
                socket,
                400,
                mapOf("error" to "The provider did not answer. Check the URL, username, and password.")
            )
            return
        }
        val deviceId = runBlocking {
            app.playlistStore.saveSession(playlist)
            val lineup = app.playlistStore.lineup(playlist.id)
            app.playlistStore.saveLineup(
                playlist.id,
                lineup.copy(
                    includeInternational = includeInternational,
                    revision = System.currentTimeMillis()
                )
            )
            app.playlistStore.deviceId()
        }
        clearGuideCache()
        CatalogIngest.start(app, playlist)
        writeJson(
            socket,
            200,
            mapOf(
                "ok" to true,
                "email" to email.trim().lowercase(Locale.US),
                "deviceId" to deviceId
            )
        )
    }

    private fun buildSetupPlaylist(
        body: JsonObject,
        email: String,
        zip: String,
        home: Triple<Double, Double, String>,
        includeVod: Boolean,
        includeInternational: Boolean
    ): SavedPlaylist {
        val mode = text(body, "mode")
        return if (mode == "m3u") {
            val url = text(body, "url")
            require(url.startsWith("http")) { "M3U URL required" }
            SavedPlaylist(
                id = PlaylistStore.newId(),
                name = email.substringBefore("@"),
                type = PlaylistType.M3U,
                m3uUrl = url,
                username = text(body, "username"),
                password = text(body, "password"),
                includeVod = includeVod,
                includeInternational = includeInternational,
                email = email,
                homeZip = zip,
                homeCountry = home.third,
                homeLat = home.first,
                homeLon = home.second
            )
        } else {
            val host = text(body, "host")
            val user = text(body, "username")
            val pass = text(body, "password")
            require(host.isNotBlank()) { "Host required" }
            require(user.isNotBlank()) { "Username required" }
            require(pass.isNotBlank()) { "Password required" }
            SavedPlaylist(
                id = PlaylistStore.newId(),
                name = user,
                type = PlaylistType.XTREAM,
                xtreamHost = XtreamClient.normalizeHost(host),
                xtreamUsername = user,
                xtreamPassword = pass,
                username = user,
                password = pass,
                includeVod = includeVod,
                includeInternational = includeInternational,
                email = email,
                homeZip = zip,
                homeCountry = home.third,
                homeLat = home.first,
                homeLon = home.second
            )
        }
    }

    private fun lookupZip(zip: String): Triple<Double, Double, String>? {
        val request = Request.Builder().url("https://api.zippopotam.us/us/$zip").build()
        return runCatching {
            logos.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                val places = json.getAsJsonArray("places") ?: return null
                if (places.size() == 0) return null
                val place = places[0].asJsonObject
                val lat = place.get("latitude")?.asString?.toDoubleOrNull() ?: return null
                val lon = place.get("longitude")?.asString?.toDoubleOrNull() ?: return null
                Triple(lat, lon, "us")
            }
        }.getOrNull()
    }

    private fun flag(body: JsonObject, key: String, fallback: Boolean): Boolean {
        if (!body.has(key) || !body.get(key).isJsonPrimitive) return fallback
        val value = body.get(key).asJsonPrimitive
        return if (value.isBoolean) value.asBoolean else fallback
    }

    private fun text(body: JsonObject, key: String): String =
        if (body.has(key) && body.get(key).isJsonPrimitive) body.get(key).asString.trim() else ""

    private fun json(body: ByteArray): JsonObject {
        if (body.isEmpty()) return JsonObject()
        return runCatching { JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject }.getOrDefault(JsonObject())
    }

    private fun privateClient(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        val host = address.hostAddress?.removePrefix("::ffff:")?.substringBefore('%') ?: return false
        if (host == "127.0.0.1" || host == "::1") return true
        if (ipv4Site(address) != null || host.startsWith("169.254.")) return true
        return address.isSiteLocalAddress
    }

    private fun safePart(value: String?): String? {
        val clean = value?.trim().orEmpty()
        if (clean.isBlank() || clean.contains('/') || clean.contains('\\') || clean.contains("..")) return null
        return clean
    }

    private fun pageBytes(context: Context): ByteArray {
        page?.let { return it }
        val bytes = context.assets.open("dashboard/index.html").use { it.readBytes() }
        page = bytes
        return bytes
    }

    private fun pageGzipBytes(context: Context): ByteArray? {
        pageGzip?.let { return it }
        val bytes = runCatching {
            context.assets.open("dashboard/portal-page.pack").use { it.readBytes() }
        }.getOrNull() ?: return null
        pageGzip = bytes
        return bytes
    }

    private fun writePortalPage(socket: Socket, context: Context) {
        if (requestGzip.get() == true) {
            val packed = pageGzipBytes(context)
            if (packed != null) {
                writePrepared(socket, 200, "text/html; charset=utf-8", packed, "no-store", gzip = true)
                return
            }
        }
        writeBytes(socket, 200, "text/html; charset=utf-8", pageBytes(context), "no-store")
    }

    private fun writePrepared(
        socket: Socket,
        code: Int,
        type: String,
        body: ByteArray,
        cache: String,
        gzip: Boolean
    ) {
        val encoding = if (gzip) "Content-Encoding: gzip\r\nVary: Accept-Encoding\r\n" else ""
        val output = BufferedOutputStream(socket.getOutputStream())
        val head = "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n$encoding" +
            "Cache-Control: $cache\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun logoBytes(context: Context): ByteArray {
        logo?.let { return it }
        val bytes = context.assets.open("dashboard/logo.png").use { it.readBytes() }
        logo = bytes
        return bytes
    }

    private fun decodeQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val values = HashMap<String, String>()
        for (part in raw.split("&")) {
            val split = part.indexOf('=')
            val key = URLDecoder.decode(if (split < 0) part else part.substring(0, split), "UTF-8")
            val value = URLDecoder.decode(if (split < 0) "" else part.substring(split + 1), "UTF-8")
            values[key] = value
        }
        return values
    }

    private fun readHeader(input: java.io.InputStream): String? {
        val out = ByteArrayOutputStream(512)
        var previous = 0
        var count = 0
        while (count < 16384) {
            val next = input.read()
            if (next < 0) return null
            out.write(next)
            count += 1
            if (previous == '\r'.code && next == '\n'.code && out.size() >= 4) {
                val bytes = out.toByteArray()
                val end = bytes.size
                if (bytes[end - 4] == '\r'.code.toByte() && bytes[end - 3] == '\n'.code.toByte() &&
                    bytes[end - 2] == '\r'.code.toByte() && bytes[end - 1] == '\n'.code.toByte()
                ) {
                    return String(bytes, Charsets.UTF_8)
                }
            }
            previous = next
        }
        return null
    }

    private fun readBytes(input: java.io.InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) bytes else bytes.copyOf(offset)
    }

    private fun writeJson(socket: Socket, code: Int, body: Any) {
        writeBytes(socket, code, "application/json; charset=utf-8", gson.toJson(body).toByteArray(Charsets.UTF_8), "no-store")
    }

    private fun writeBytes(
        socket: Socket,
        code: Int,
        type: String,
        body: ByteArray,
        cache: String,
        download: String? = null,
        allowGzip: Boolean = true
    ) {
        val compressible = allowGzip && (
            type.startsWith("text/") ||
                type.startsWith("application/json") ||
                type.startsWith("application/javascript")
            )
        val payload = if (compressible && requestGzip.get() == true && body.size >= 512) {
            gzipBytes(body)
        } else {
            null
        }
        val outBody = payload ?: body
        val encoding = if (payload != null) "Content-Encoding: gzip\r\nVary: Accept-Encoding\r\n" else ""
        val output = BufferedOutputStream(socket.getOutputStream())
        val disposition = if (download == null) "" else "Content-Disposition: attachment; filename=\"$download\"\r\n"
        val head = "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\nContent-Length: ${outBody.size}\r\n$encoding$disposition" +
            "Cache-Control: $cache\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(outBody)
        output.flush()
    }

    private fun gzipBytes(body: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream(body.size / 2)
        GZIPOutputStream(sink).use { gzip -> gzip.write(body) }
        return sink.toByteArray()
    }

    private fun writeFile(socket: Socket, file: File, type: String, download: String?) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val disposition = if (download == null) "" else "Content-Disposition: attachment; filename=\"$download\"\r\n"
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${file.length()}\r\n$disposition" +
            "Cache-Control: private, max-age=3600\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        file.inputStream().use { input -> input.copyTo(output) }
        output.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "Error"
    }
}
