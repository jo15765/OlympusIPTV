package com.olympus.iptv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.PlaylistStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.portal.DashboardHost
import com.olympus.iptv.playlist.EpgRepository
import com.olympus.iptv.playlist.LoadedPlaylist
import com.olympus.iptv.playlist.FreshPlayable
import com.olympus.iptv.playlist.Watchmode
import com.olympus.iptv.playlist.WatchmodeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class OlympusIptvApp : Application() {
    private val appErrors = CoroutineExceptionHandler { _, error ->
        AppLog.error("App", "Background work failed", error)
        if (error is OutOfMemoryError ||
            generateSequence(error.cause) { it.cause }.any { it is OutOfMemoryError }
        ) {
            runCatching { catalogDb.releaseSearchExport() }
            runCatching { com.olympus.iptv.portal.DashboardHost.clearGuideCache() }
            guideChannels = emptyList()
            guideSource = null
            warmPlayer?.release()
            warmPlayer = null
            warmChannelId = null
            runCatching { System.gc() }
        }
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + appErrors)
    @Volatile var catalogRefresh: Job? = null
    @Volatile var catalogLiveReady: CompletableDeferred<Boolean>? = null

    @Volatile var autoResumeUsed = false

    lateinit var playlistStore: PlaylistStore
        private set

    val catalogDb: CatalogDb by lazy { CatalogDb(this) }
    val epgRepository = EpgRepository()

    var guidePlaylistId: String? = null
    var guideChannels: List<Channel> = emptyList()
    var playbackQueue: List<Channel> = emptyList()
    var guideSource: LoadedPlaylist? = null
    /** Last channel handed to the player — drives the phone now-playing strip. */
    @Volatile var lastPlayed: Channel? = null
    @Volatile var lastPlayedPlaylistId: String? = null
    @Volatile var stagedLineup: Pair<String, LineupConfig>? = null
    private val lineupListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun watchLineup(listener: (String) -> Unit) {
        lineupListeners.add(listener)
    }

    fun unwatchLineup(listener: (String) -> Unit) {
        lineupListeners.remove(listener)
    }
    @Volatile var freshShown = false
    @Volatile var freshHold: FreshHold? = null
    @Volatile var freshJob: Job? = null
    @Volatile var freshFailure: WatchmodeException.Kind? = null

    data class FreshHold(
        val playlistId: String,
        val stamp: Long,
        val week: String,
        val cards: List<CatalogDb.FreshTitle>
    )

    fun freshCards(playlistId: String): List<CatalogDb.FreshTitle>? {
        val week = Watchmode.weekKey()
        freshHold?.takeIf { it.playlistId == playlistId && it.week == week }?.let { return it.cards }
        return readFreshFile(playlistId, week)?.also { freshHold = it }?.cards
    }

    fun warmFresh(playlistId: String) {
        if (playlistId.isBlank()) return
        if (freshJob?.isActive == true) return
        freshJob = appScope.launch { prepareFresh(playlistId) }
    }

    private suspend fun prepareFresh(playlistId: String) {
        val cacheFile = File(filesDir, "watchmode-week.json")
        val week = Watchmode.weekKey()
        val stamp = runCatching { catalogDb.libraryStamp(playlistId) }.getOrDefault(0L)
        val held = freshHold
        if (held != null && held.playlistId == playlistId && held.week == week &&
            held.stamp == stamp && !Watchmode.needsRefresh(cacheFile)
        ) {
            return
        }
        val disk = readFreshFile(playlistId, week)
        if (disk != null && disk.stamp == stamp && !Watchmode.needsRefresh(cacheFile)) {
            // Same week + library: keep playable-filtered cards; skip Xtream episode re-checks.
            freshHold = disk
            freshFailure = null
            return
        }
        if (disk != null) freshHold = disk
        try {
            val premieres = if (Watchmode.needsRefresh(cacheFile)) {
                Watchmode.refresh(cacheFile)
            } else {
                Watchmode.load(cacheFile)
            }
            val catalog = catalogDb.titlesForPremieres(playlistId, Watchmode.lookupPrefixes(premieres))
            val matched = if (catalog.isEmpty()) emptyList() else Watchmode.match(premieres, catalog)
            val account = runCatching { playlistStore.account(playlistId) }.getOrNull()
            val cards = if (account == null || matched.isEmpty()) {
                emptyList()
            } else {
                FreshPlayable.filter(filesDir, account, catalogDb, matched)
            }
            val next = FreshHold(
                playlistId,
                runCatching { catalogDb.libraryStamp(playlistId) }.getOrDefault(stamp),
                week,
                cards
            )
            freshHold = next
            freshFailure = null
            writeFreshFile(next)
        } catch (error: WatchmodeException) {
            freshFailure = error.kind
        } catch (error: OutOfMemoryError) {
            runCatching { catalogDb.releaseSearchExport() }
            runCatching { System.gc() }
            freshFailure = WatchmodeException.Kind.FAILED
        } catch (_: Exception) {
            freshFailure = WatchmodeException.Kind.FAILED
        }
    }

    private fun readFreshFile(playlistId: String, week: String): FreshHold? {
        val file = File(filesDir, "fresh-cards.json")
        if (!file.exists()) return null
        val type = object : TypeToken<FreshHold>() {}.type
        val hold = runCatching { Gson().fromJson<FreshHold>(file.readText(), type) }.getOrNull() ?: return null
        if (hold.playlistId != playlistId || hold.week != week) return null
        return hold
    }

    private fun writeFreshFile(hold: FreshHold) {
        runCatching { File(filesDir, "fresh-cards.json").writeText(Gson().toJson(hold)) }
    }
    @Volatile var warmPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    @Volatile var warmChannelId: String? = null

    @Volatile private var warmed = false

    private val overscan = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is com.olympus.iptv.ui.player.PlayerActivity) return
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            content.post { com.olympus.iptv.ui.InsetsPad.ensureOverscan(content) }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Disk and catalog warm-up runs after the first frame, never inside onCreate. */
    fun warmAfterFirstFrame() {
        if (warmed) return
        warmed = true
        appScope.launch {
            // The guide's first queries own the database. Ratings, event windows,
            // and the dashboard snapshot wait until that grid is on screen.
            delay(12_000L)
            runCatching { com.olympus.iptv.playlist.TitleRatings.init(this@OlympusIptvApp, catalogDb) }
            runCatching { playlistStore.videoDecoder() }
            runCatching { catalogDb.catchUpEventWindows() }
        }
    }


    @Volatile private var cloudSyncJob: Job? = null

    fun startCloudRealtimeSync() {
        cloudSyncJob?.cancel()
        cloudSyncJob = appScope.launch {
            var activeEmail = ""
            while (isActive) {
                val saved = runCatching { playlistStore.session.first() }.getOrNull()
                val email = saved?.email?.trim().orEmpty()
                if (saved != null && email.isNotBlank()) {
                    activeEmail = email
                    val deviceId = runCatching { playlistStore.deviceId() }.getOrDefault("")
                    if (deviceId.isNotBlank()) {
                        val client = com.olympus.iptv.playlist.LineupClient(this@OlympusIptvApp)
                        val sseJob = client.subscribeSyncEvents(this, email, deviceId) { _ ->
                            pullCloudChanges()
                        }
                        sseJob.join()
                    }
                }
                delay(if (activeEmail.isBlank()) 4_000L else 2_000L)
            }
        }
    }

    @Volatile private var cloudLoginRevision = -1L
    private val cloudPull = Mutex()
    private val cloudStamps = HashMap<String, Long>()

    /** Reads cloud revision numbers every 10 seconds and applies a section only when it changed. */
    private fun watchCloudLogin() {
        appScope.launch {
            while (isActive) {
                runCatching { pullCloudChanges() }
                delay(10_000L)
            }
        }
    }

    private suspend fun pullCloudChanges() {
        cloudPull.withLock {
            val deviceId = runCatching { playlistStore.deviceId() }.getOrDefault("")
            if (deviceId.isBlank()) return
            val client = com.olympus.iptv.playlist.LineupClient(this)
            val stamp = client.pullSyncStamp(deviceId) ?: return
            fun number(key: String): Long {
                val value = stamp.get(key) ?: return 0L
                if (value.isJsonNull || !value.isJsonPrimitive) return 0L
                return value.asLong
            }
            val loginRev = number("login")
            if (cloudStamps["login"] != loginRev) {
                if (loginRev == 0L || claimCloudLogin()) cloudStamps["login"] = loginRev
            }
            val saved = runCatching { playlistStore.session.first() }.getOrNull() ?: return
            val email = saved.email?.trim().orEmpty()
            if (email.isBlank()) return
            val lineupRev = number("lineup")
            if (cloudStamps["lineup"] != lineupRev) {
                val local = playlistStore.lineup(saved.id)
                val remote = runCatching { client.remoteLineup(email, deviceId) }.getOrNull()
                if (remote != null) {
                    if ((remote.revision ?: 0L) > (local.revision ?: 0L)) {
                        playlistStore.saveLineup(saved.id, remote)
                        val prefixes = remote.namePrefixes.orEmpty()
                        val suffixes = remote.nameSuffixes.orEmpty()
                        if (prefixes != local.namePrefixes.orEmpty() || suffixes != local.nameSuffixes.orEmpty()) {
                            runCatching { catalogDb.rewriteNames(saved.id, prefixes, suffixes) }
                        }
                    }
                    cloudStamps["lineup"] = lineupRev
                }
            }
            val favoritesRev = number("favorites")
            if (cloudStamps["favorites"] != favoritesRev) {
                val localRev = playlistStore.favoritesRevision(saved.id)
                val remote = runCatching { client.pullFavorites(email, deviceId) }.getOrNull()
                if (remote != null) {
                    if (remote.second > localRev) {
                        playlistStore.saveFavorites(saved.id, remote.first, remote.second)
                    }
                    cloudStamps["favorites"] = favoritesRev
                }
            }
            val recordingsRev = number("recordings")
            if (cloudStamps["recordings"] != recordingsRev) {
                val localBook = playlistStore.recordingBook(saved.id)
                val remoteBook = runCatching { client.pullRecordings(email, deviceId) }.getOrNull()
                if (remoteBook != null) {
                    if ((remoteBook.revision ?: 0L) > (localBook.revision ?: 0L)) {
                        playlistStore.saveRecordingBook(saved.id, remoteBook)
                        com.olympus.iptv.record.RecordingService.start(this)
                    }
                    cloudStamps["recordings"] = recordingsRev
                }
            }
            val remindersRev = number("reminders")
            if (remindersRev == 0L && cloudStamps["reminders"] == null) {
                cloudStamps["reminders"] = 0L
            } else if (cloudStamps["reminders"] != remindersRev) {
                val remote = runCatching { client.fetchRemoteReminders(email, deviceId) }.getOrNull()
                if (remote != null) {
                    com.olympus.iptv.remind.applyCloudReminders(this, saved.id, remote)
                    cloudStamps["reminders"] = remindersRev
                }
            }
            val watchRev = number("watch")
            if (watchRev == 0L && cloudStamps["watch"] == null) {
                cloudStamps["watch"] = 0L
            } else if (cloudStamps["watch"] != watchRev) {
                val remoteWatch = runCatching { client.fetchWatchHistory(email, deviceId) }.getOrNull()
                if (remoteWatch != null) {
                    val localWatch = playlistStore.recent(saved.id)
                    for (entry in remoteWatch) {
                        val cid = entry.channelId?.takeIf { it.isNotBlank() } ?: continue
                        val localMatch = localWatch.firstOrNull { it.channelId == cid }
                        if (localMatch == null || (entry.updatedAt ?: 0L) > (localMatch.updatedAt ?: 0L)) {
                            playlistStore.rememberWatch(saved.id, entry)
                        }
                    }
                    cloudStamps["watch"] = watchRev
                }
            }
        }
    }

    private suspend fun claimCloudLogin(): Boolean {
        val deviceId = runCatching { playlistStore.deviceId() }.getOrDefault("")
        if (deviceId.isBlank()) return false
        val json = com.olympus.iptv.playlist.LineupClient(this).pullProviderLogin(deviceId) ?: return false
        val remote = com.olympus.iptv.playlist.CloudLogin.parse(json) ?: return false
        val existing = runCatching { playlistStore.session.first() }.getOrNull()
        if (com.olympus.iptv.playlist.CloudLogin.same(existing, remote)) {
            cloudLoginRevision = remote.revision
            return true
        }
        if (remote.revision > 0L && remote.revision == cloudLoginRevision) return true
        val saved = com.olympus.iptv.playlist.CloudLogin.apply(this, remote)
        cloudLoginRevision = remote.revision
        catalogRefresh?.cancel()
        com.olympus.iptv.playlist.CatalogIngest.start(this, saved)
        return true
    }

    override fun onCreate() {
        super.onCreate()
        com.olympus.iptv.playlist.HttpClients.install(this)
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build()
            )
        }
        MemoryTrim.install(this)
        appScope.launch { com.olympus.iptv.data.AvSyncStore.warm(this@OlympusIptvApp) }
        registerActivityLifecycleCallbacks(overscan)
        AppLog.install(this)
        playlistStore = PlaylistStore(this)
        android.os.Looper.myQueue().addIdleHandler {
            warmAfterFirstFrame()
            false
        }
        playlistStore.onLineupSaved = { id, config ->
            stagedLineup = id to config
            android.os.Handler(mainLooper).post {
                lineupListeners.forEach { listener -> listener(id) }
            }
        }
        playlistStore.onFavoritesSaved = { id ->
            android.os.Handler(mainLooper).post {
                lineupListeners.forEach { listener -> listener(id) }
            }
        }
        appScope.launch {
            com.olympus.iptv.remind.ReminderClock.start(this@OlympusIptvApp)
            com.olympus.iptv.playlist.CatalogSyncWorker.schedule(this@OlympusIptvApp)
        }
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity is com.olympus.iptv.remind.ReminderActivity) return
                com.olympus.iptv.remind.ReminderClock.arm(activity)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        epgRepository.attach(catalogDb, appScope)
        startCloudRealtimeSync()
        watchCloudLogin()
        appScope.launch {
            DashboardHost.start(this@OlympusIptvApp)
            delay(45_000L)
            while (isActive) {
                runCatching { catalogDb.maintainDaily() }
                delay(6L * 60L * 60L * 1000L)
            }
        }
        com.olympus.iptv.vpn.AppTunnel.restore(this)
        val touch = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)
        val lowRam = com.olympus.iptv.ui.DeviceForm.isLowRam(this)
        com.olympus.iptv.ui.BackdropGlow.motion = true
        val imageBytes = if (lowRam) 16L * 1024 * 1024 else 64L * 1024 * 1024
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient { com.olympus.iptv.playlist.HttpClients.shared }
                .crossfade(false)
                .bitmapConfig(
                    if (lowRam) android.graphics.Bitmap.Config.RGB_565
                    else android.graphics.Bitmap.Config.ARGB_8888
                )
                .respectCacheHeaders(false)
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizeBytes(imageBytes.toInt())
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("logos"))
                        .maxSizeBytes(if (touch) 24L * 1024 * 1024 else 48L * 1024 * 1024)
                        .build()
                }
                .build()
        )
    }
}
