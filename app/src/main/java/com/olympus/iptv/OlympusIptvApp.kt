package com.olympus.iptv

import android.app.Application
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
import com.olympus.iptv.playlist.Watchmode
import com.olympus.iptv.playlist.WatchmodeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.io.File

class OlympusIptvApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var catalogRefresh: Job? = null
    @Volatile var catalogLiveReady: CompletableDeferred<Boolean>? = null

    lateinit var playlistStore: PlaylistStore
        private set

    val catalogDb: CatalogDb by lazy { CatalogDb(this) }
    val epgRepository = EpgRepository()

    var guidePlaylistId: String? = null
    var guideChannels: List<Channel> = emptyList()
    var playbackQueue: List<Channel> = emptyList()
    var guideSource: LoadedPlaylist? = null
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

    private fun prepareFresh(playlistId: String) {
        val cacheFile = File(filesDir, "watchmode-week.json")
        val week = Watchmode.weekKey()
        val stamp = catalogDb.libraryStamp(playlistId)
        val held = freshHold
        if (held != null && held.playlistId == playlistId && held.week == week &&
            held.stamp == stamp && !Watchmode.needsRefresh(cacheFile)
        ) {
            return
        }
        val disk = readFreshFile(playlistId, week)
        if (disk != null && disk.stamp == stamp && !Watchmode.needsRefresh(cacheFile)) {
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
            if (catalog.isEmpty()) return
            val cards = Watchmode.match(premieres, catalog)
            val next = FreshHold(playlistId, catalogDb.libraryStamp(playlistId), week, cards)
            freshHold = next
            freshFailure = null
            writeFreshFile(next)
        } catch (error: WatchmodeException) {
            freshFailure = error.kind
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

    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
        playlistStore = PlaylistStore(this)
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
        com.olympus.iptv.remind.ReminderClock.start(this)
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
        DashboardHost.start(this)
        com.olympus.iptv.vpn.AppTunnel.restore(this)
        // Heavy catalog warm-up runs after Browse paints (see BrowseActivity.showCatalog).
        // Starting it here contended with the first group load and made returning launches feel slow.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .crossfade(false)
                .respectCacheHeaders(false)
                .memoryCache {
                    MemoryCache.Builder(this).maxSizePercent(0.08).build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("logos"))
                        .maxSizeBytes(48L * 1024 * 1024)
                        .build()
                }
                .build()
        )
    }
}
