package com.olympus.iptv.ui.browse

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.ui.browse.BrowseActivity.GuideMenu
import com.olympus.iptv.ui.browse.BrowseActivity.GuideSection
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.CARD_SPAN
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.PAGE_SIZE_TV
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.PAGE_SIZE_PHONE
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.PAGE_SIZE_HUGE
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.LARGE_LIVE_GROUP
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.HUGE_PREFETCH_REMAIN
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.DEFAULT_PREFETCH_REMAIN
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.MAX_EMPTY_PAGE_SKIPS
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.FOCUS_SETTLE_MS
import com.olympus.iptv.ui.browse.BrowseActivity.Companion.EPG_CACHE_CAP
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.olympus.iptv.BuildConfig
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.vpn.AppTunnel
import com.olympus.iptv.vpn.TunnelState
import com.olympus.iptv.vpn.VpnUi
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ChannelNames
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.ListOrder
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.data.ShowNames
import com.olympus.iptv.data.WatchEntry
import com.olympus.iptv.databinding.ActivityBrowseBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.CatchupUrls
import com.olympus.iptv.playlist.CountryIndex
import com.olympus.iptv.playlist.EpisodeCache
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.playlist.EpgRepository
import com.olympus.iptv.playlist.EpgUtils
import com.olympus.iptv.playlist.LineupApplier
import com.olympus.iptv.remind.RemindPrompt
import com.olympus.iptv.remind.ReminderStore
import com.olympus.iptv.playlist.GroupLock
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.playlist.LoadedPlaylist
import com.olympus.iptv.playlist.PinAlert
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.playlist.TitleRatings
import com.olympus.iptv.playlist.XtreamClient
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.olympus.iptv.record.RecordActivity
import com.olympus.iptv.record.RecordPrompt
import com.olympus.iptv.record.RecordingService
import com.olympus.iptv.ui.FocusMotion
import com.olympus.iptv.ui.Haptics
import com.olympus.iptv.ui.epg.EpgEditsFragment
import com.olympus.iptv.ui.epg.ParentalFragment
import com.olympus.iptv.ui.epg.SanitizeFragment
import com.olympus.iptv.ui.login.LoginActivity
import com.olympus.iptv.ui.player.PlayerActivity
import com.olympus.iptv.ui.player.SeriesPlay
import com.olympus.iptv.ui.search.SearchActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.NumberFormat

internal fun BrowseActivity.applyFavorites(ids: List<String>) {
        invalidateTopWatchedCache()
        favoriteOrder.clear()
        favoriteOrder.addAll(ids)
        favoriteIds.clear()
        favoriteIds.addAll(ids)
        warmFavoriteAndRecentEpg()
        updateGroupClock(force = true)
    }

internal fun BrowseActivity.updateGroupClock(force: Boolean = false) {
        if (!viewReady()) return
        if (section != GuideSection.TV || binding.groupColumn.visibility != View.VISIBLE) {
            binding.groupClock.visibility = View.GONE
            groupClockMinute = Long.MIN_VALUE
            return
        }
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        if (!force && minute == groupClockMinute && binding.groupClock.visibility == View.VISIBLE) return
        groupClockMinute = minute
        binding.groupClock.text = groupClockFormat.format(Date(now))
        binding.groupClock.visibility = View.VISIBLE
    }

internal fun BrowseActivity.loadSavedBuckets(playlistId: String, db: CatalogDb) {
        lifecycleScope.launch {
            coroutineScope {
                val movieCategories = async(Dispatchers.IO) { db.readCategoriesOnly(playlistId, CatalogDb.VOD) }
                val showCategories = async(Dispatchers.IO) { db.readCategoriesOnly(playlistId, CatalogDb.SERIES) }
                launch { replaceBucket(CatalogDb.VOD, movieCategories.await(), emptyList()) }
                launch { replaceBucket(CatalogDb.SERIES, showCategories.await(), emptyList()) }
            }
            loadingFromDb = false
            moviesPending = false
            showsPending = false
            if (!isDestroyed && binding.groupColumn.visibility == View.VISIBLE && section != GuideSection.TV) {
                render(restoreFocus = false)
            }
        }
    }

internal suspend fun BrowseActivity.replaceBucket(
        bucket: String,
        categories: List<com.olympus.iptv.data.Category>,
        channels: List<Channel>
    ) {
        if (isDestroyed) return
        libraryMutex.withLock {
            if (isDestroyed) return@withLock
            val base = source ?: LoadedPlaylist(emptyList(), emptyList())
            val next = when (bucket) {
                CatalogDb.VOD -> base.copy(
                    vodCategories = categories,
                    vodChannels = channels,
                    vodLoadFailed = false,
                    vodLoadError = null
                )
                else -> base.copy(
                    seriesCategories = categories,
                    seriesChannels = channels,
                    seriesLoadFailed = false,
                    seriesLoadError = null
                )
            }
            val config = activeConfig
            if (bucket == CatalogDb.VOD) {
                moviesPending = false
                vodByCategory = emptyMap()
                vodById = emptyMap()
                vodIndexing = false
            }
            if (bucket == CatalogDb.SERIES) {
                showsPending = false
                seriesByCategory = emptyMap()
                seriesById = emptyMap()
                seriesIndexing = false
            }
            showCatalog(next, config, openGuide = false)
        }
    }

internal fun BrowseActivity.libraryHasItems(catalog: LoadedPlaylist): Boolean {
        return catalog.liveChannels.isNotEmpty() ||
            catalog.vodChannels.isNotEmpty() ||
            catalog.seriesChannels.orEmpty().isNotEmpty()
    }

internal fun BrowseActivity.persistLibrary(db: CatalogDb, playlistId: String, catalog: LoadedPlaylist) {
        db.insertMissing(playlistId, CatalogDb.LIVE, catalog.liveCategories, catalog.liveChannels)
        db.insertMissing(playlistId, CatalogDb.VOD, catalog.vodCategories, catalog.vodChannels)
        db.insertMissing(
            playlistId,
            CatalogDb.SERIES,
            catalog.seriesCategories.orEmpty(),
            catalog.seriesChannels.orEmpty()
        )
    }

internal fun BrowseActivity.storeBatch(
        db: CatalogDb,
        playlistId: String,
        bucket: String,
        channels: List<Channel>,
        tag: Boolean,
        known: MutableSet<String>,
        into: MutableList<Channel>? = null
    ): Int {
        if (channels.isEmpty()) return 0
        val fresh = ArrayList<Channel>()
        for (channel in channels) {
            if (channel.id !in known) fresh.add(channel)
        }
        if (fresh.isEmpty()) return 0
        val rows = if (tag) CountryIndex.tagChannels(fresh) else fresh
        val added = db.insertMissing(playlistId, bucket, emptyList(), rows)
        for (channel in added.channels) known.add(channel.id)
        if (into != null) into.addAll(added.channels)
        return added.channels.size
    }

internal suspend inline fun <T> BrowseActivity.refreshResult(crossinline block: suspend () -> T): T? {
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

internal suspend fun <T> BrowseActivity.pipeBatches(
        write: (List<Channel>) -> Unit,
        read: suspend ((List<Channel>) -> Unit) -> T
    ): T = coroutineScope {
        val incoming = kotlinx.coroutines.channels.Channel<List<Channel>>(4)
        val writer = launch(Dispatchers.IO) {
            val prior = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                for (batch in incoming) write(batch)
            } finally {
                Process.setThreadPriority(prior)
            }
        }
        val result = try {
            read { batch ->
                while (!incoming.trySend(batch).isSuccess) {
                    Thread.sleep(2L)
                }
            }
        } finally {
            incoming.close()
        }
        writer.join()
        result
    }

internal fun BrowseActivity.refreshPlaylist(quiet: Boolean = false) {
        val saved = playlist ?: return
        val app = application as OlympusIptvApp
        if (app.catalogRefresh?.isActive == true) {
            if (touchPrimary && viewReady() && quiet) {
                binding.groupsRefresh.isRefreshing = true
            }
            return
        }
        val db = app.catalogDb
        if (!quiet) {
            binding.refreshLine.visibility = View.VISIBLE
            binding.refreshLine.text = getString(R.string.refreshing_playlist)
            tightenNavRail()
        } else {
            showRefreshStatus(getString(R.string.refresh_checking))
        }
        val movieCountCb: (Int) -> Unit = if (quiet) ({}) else ::onMovieCount
        val moviePercentCb: (Int) -> Unit = if (quiet) ({}) else ::onMoviePercent
        val showCountCb: (Int) -> Unit = if (quiet) ({}) else ::onShowCount
        val showPercentCb: (Int) -> Unit = if (quiet) ({}) else ::onShowPercent
        app.catalogRefresh = app.appScope.launch {
            val previous = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var added = 0
            var reached = false
            val newLive = ArrayList<Category>()
            val newVod = ArrayList<Category>()
            val newSeries = ArrayList<Category>()
            val foundLive = ArrayList<Channel>()
            val foundVod = ArrayList<Channel>()
            val foundSeries = ArrayList<Channel>()
            val keepLive = if (quiet) foundLive else null
            val keepVod = if (quiet) foundVod else null
            val keepSeries = if (quiet) foundSeries else null
            try {
            if (saved.type == PlaylistType.XTREAM) {
                coroutineScope {
                    var liveAdded = 0
                    val liveKnown = HashSet<String>()
                    val liveJob = async {
                        val prior = Process.getThreadPriority(Process.myTid())
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        try {
                            liveKnown.addAll(db.knownChannelIds(saved.id, CatalogDb.LIVE))
                            refreshResult {
                                pipeBatches(
                                    write = { batch ->
                                        liveAdded += storeBatch(db, saved.id, CatalogDb.LIVE, batch, true, liveKnown, keepLive)
                                    }
                                ) { send ->
                                    loader.loadLive(saved) { batch -> send(batch) }
                                }
                            }
                        } finally {
                            Process.setThreadPriority(prior)
                        }
                    }
                    var showAdded = 0
                    val showKnown = HashSet<String>()
                    val showJob = async {
                        val prior = Process.getThreadPriority(Process.myTid())
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        try {
                            showKnown.addAll(db.knownChannelIds(saved.id, CatalogDb.SERIES))
                            refreshResult {
                                pipeBatches(
                                    write = { batch ->
                                        showAdded += storeBatch(db, saved.id, CatalogDb.SERIES, batch, false, showKnown, keepSeries)
                                    }
                                ) { send ->
                                    loader.loadShows(saved, showCountCb, showPercentCb) { batch -> send(batch) }
                                }
                            }
                        } finally {
                            Process.setThreadPriority(prior)
                        }
                    }
                    var movieAdded = 0
                    val movieKnown = HashSet<String>()
                    val movieJob = async {
                        val prior = Process.getThreadPriority(Process.myTid())
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        try {
                            movieKnown.addAll(db.knownChannelIds(saved.id, CatalogDb.VOD))
                            refreshResult {
                                pipeBatches(
                                    write = { batch ->
                                        movieAdded += storeBatch(db, saved.id, CatalogDb.VOD, batch, true, movieKnown, keepVod)
                                    }
                                ) { send ->
                                    CountryIndex.annotate(
                                        loader.loadMovies(saved, movieCountCb, moviePercentCb) { batch -> send(batch) }
                                    )
                                }
                            }
                        } finally {
                            Process.setThreadPriority(prior)
                        }
                    }
                    liveJob.await()?.let { live ->
                        reached = true
                        val categories = db.insertMissing(saved.id, CatalogDb.LIVE, live.liveCategories, emptyList())
                        liveAdded += storeBatch(db, saved.id, CatalogDb.LIVE, live.liveChannels, true, liveKnown, keepLive)
                        added += liveAdded
                        if (quiet) newLive.addAll(categories.categories) else {
                            publishAdded(CatalogDb.Added(live.liveCategories, emptyList()), CatalogDb.LIVE)
                        }
                    }
                    showJob.await()?.let { shows ->
                        reached = true
                        if (!shows.seriesLoadFailed) {
                            val categories = db.insertMissing(
                                saved.id,
                                CatalogDb.SERIES,
                                shows.seriesCategories.orEmpty(),
                                emptyList()
                            )
                            showAdded += storeBatch(
                                db,
                                saved.id,
                                CatalogDb.SERIES,
                                shows.seriesChannels.orEmpty(),
                                false,
                                showKnown,
                                keepSeries
                            )
                            added += showAdded
                            if (quiet) newSeries.addAll(categories.categories) else {
                                publishAdded(
                                    CatalogDb.Added(shows.seriesCategories.orEmpty(), emptyList()),
                                    CatalogDb.SERIES
                                )
                            }
                        } else if (!quiet && !isDestroyed) {
                            showError = shows.seriesLoadError
                        }
                    }
                    var movies = movieJob.await()
                    if (movies != null && movies.vodLoadFailed && movies.vodChannels.isEmpty() && movieAdded == 0) {
                        if (!quiet) {
                            movieAttempt = 2
                            movieError = movies.vodLoadError
                        }
                        movieAdded = 0
                        movies = refreshResult {
                            pipeBatches(
                                write = { batch ->
                                    movieAdded += storeBatch(db, saved.id, CatalogDb.VOD, batch, true, movieKnown, keepVod)
                                }
                            ) { send ->
                                CountryIndex.annotate(
                                    loader.loadMovies(saved, movieCountCb, moviePercentCb) { batch -> send(batch) }
                                )
                            }
                        }
                    }
                    movies?.let { loadedMovies ->
                        reached = true
                        if (!loadedMovies.vodLoadFailed) {
                            val categories = db.insertMissing(
                                saved.id,
                                CatalogDb.VOD,
                                loadedMovies.vodCategories,
                                emptyList()
                            )
                            movieAdded += storeBatch(
                                db,
                                saved.id,
                                CatalogDb.VOD,
                                loadedMovies.vodChannels,
                                true,
                                movieKnown,
                                keepVod
                            )
                            added += movieAdded
                            if (quiet) newVod.addAll(categories.categories) else {
                                publishAdded(
                                    CatalogDb.Added(loadedMovies.vodCategories, emptyList()),
                                    CatalogDb.VOD
                                )
                            }
                            if (!quiet) movieError = null
                        } else if (!quiet && !isDestroyed) {
                            movieError = loadedMovies.vodLoadError ?: "The movie list did not come back"
                        }
                    }
                }
            } else {
                val fresh = runCatching { CountryIndex.annotate(loader.load(saved)) }.getOrNull()
                if (fresh != null) {
                    reached = true
                    val liveKnown = db.knownChannelIds(saved.id, CatalogDb.LIVE)
                    val movieKnown = db.knownChannelIds(saved.id, CatalogDb.VOD)
                    val liveRows = fresh.liveChannels.filter { it.id !in liveKnown }
                    val movieRows = fresh.vodChannels.filter { it.id !in movieKnown }
                    val live = db.insertMissing(saved.id, CatalogDb.LIVE, fresh.liveCategories, liveRows)
                    val movies = db.insertMissing(saved.id, CatalogDb.VOD, fresh.vodCategories, movieRows)
                    added += live.channels.size + movies.channels.size
                    if (quiet) {
                        newLive.addAll(live.categories)
                        newVod.addAll(movies.categories)
                        foundLive.addAll(live.channels)
                        foundVod.addAll(movies.channels)
                    } else {
                        publishAdded(live, CatalogDb.LIVE)
                        publishAdded(movies, CatalogDb.VOD)
                    }
                }
            }
            if (reached) app.playlistStore.saveLastRefreshAt(saved.id, System.currentTimeMillis())
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                if (quiet) {
                    absorbNewCategories(newLive, newVod, newSeries, foundLive, foundVod, foundSeries)
                    showRefreshStatus(
                        if (added == 0) getString(R.string.refresh_quiet_done)
                        else getString(R.string.refresh_quiet_added, added)
                    )
                    binding.groupsRefresh.isRefreshing = false
                    return@withContext
                }
                moviesPending = false
                showsPending = false
                showLoading(false)
                binding.groupsRefresh.isRefreshing = false
                binding.refreshLine.visibility = View.VISIBLE
                binding.refreshLine.text = if (added == 0) {
                    getString(R.string.refresh_current)
                } else {
                    getString(R.string.refresh_added, added)
                }
                tightenNavRail()
                if (loaded == null) {
                    showGuide()
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = movieError ?: getString(R.string.error_load)
                } else if (binding.groupColumn.visibility == View.VISIBLE) {
                    render(restoreFocus = false)
                }
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (isDestroyed) return@withContext
                    binding.groupsRefresh.isRefreshing = false
                    showRefreshStatus(getString(R.string.refresh_offline_hint))
                    Toast.makeText(
                        screen(),
                        R.string.refresh_offline_hint,
                        Toast.LENGTH_LONG
                    ).show()
                    if (!quiet) {
                        binding.refreshLine.visibility = View.VISIBLE
                        binding.refreshLine.text = getString(R.string.refresh_offline_hint)
                    }
                }
            } finally {
                Process.setThreadPriority(previous)
            }
        }
    }

internal fun BrowseActivity.ensureRefreshLoop() {
        if (refreshLoop?.isActive == true) return
        val saved = playlist ?: return
        val app = application as OlympusIptvApp
        refreshLoop = app.appScope.launch {
            val store = app.playlistStore
            if (app.catalogRefresh?.isActive != true &&
                store.lastRefreshAt(saved.id) == 0L &&
                app.catalogDb.hasLibrary(saved.id)
            ) {
                store.saveLastRefreshAt(saved.id, System.currentTimeMillis())
            }
            while (isActive) {
                val minutes = store.refreshIntervalMinutes().coerceAtLeast(1)
                val due = store.lastRefreshAt(saved.id) + minutes * 60_000L
                val wait = due - System.currentTimeMillis()
                if (wait > 0L) {
                    delay(wait.coerceAtMost(60_000L))
                    continue
                }
                val running = app.catalogRefresh
                if (running?.isActive == true) {
                    running.join()
                    continue
                }
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) refreshPlaylist(quiet = true)
                }
                app.catalogRefresh?.join()
                if (store.lastRefreshAt(saved.id) + minutes * 60_000L <= System.currentTimeMillis()) {
                    delay(60_000L)
                }
            }
        }
    }

internal fun BrowseActivity.showVpnPane() {
        epgPrefetchJob?.cancel()
        binding.btnAdmin.isSelected = true
        binding.btnAbout.isSelected = false
        binding.tabLive.isSelected = false
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        binding.groupColumn.visibility = View.GONE
        binding.contentDivider.visibility = View.GONE
        binding.contentColumn.visibility = View.GONE
        binding.programPane.visibility = View.GONE
        binding.blankPane.visibility = View.GONE
        panes.hideAllSettingPanes()
        panes.ensureVpn().root.visibility = View.VISIBLE
        hidePreviewChrome()
        vpnUi.onShow()
    }

internal fun BrowseActivity.showRefreshPane() {
        epgPrefetchJob?.cancel()
        binding.btnAdmin.isSelected = true
        binding.btnAbout.isSelected = false
        binding.tabLive.isSelected = false
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        binding.groupColumn.visibility = View.GONE
        binding.contentDivider.visibility = View.GONE
        binding.contentColumn.visibility = View.GONE
        binding.programPane.visibility = View.GONE
        binding.blankPane.visibility = View.GONE
        panes.hideAllSettingPanes()
        panes.ensureRefresh().root.visibility = View.VISIBLE
        hidePreviewChrome()
        showRefreshInterval(refreshMinutes)
        panes.refreshNowCard.post { panes.refreshNowCard.requestFocus() }
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val minutes = withContext(Dispatchers.IO) { app.playlistStore.refreshIntervalMinutes() }
            refreshMinutes = minutes
            if (!isDestroyed && panes.isRefreshVisible()) showRefreshInterval(minutes)
        }
    }

internal fun BrowseActivity.showRefreshStatus(text: String) {
        if (!viewReady() || isDestroyed) return
        pendingRefreshStatus = text
        panes.refreshOrNull?.refreshStatus?.text = text
    }

internal fun BrowseActivity.showRefreshInterval(minutes: Int) {
        panes.refreshOrNull?.refreshIntervalValue?.text = getString(R.string.refresh_every, intervalLabel(minutes))
    }

internal fun BrowseActivity.cycleRefreshInterval() {
        val choices = PlaylistStore.REFRESH_INTERVALS
        val index = choices.indexOf(refreshMinutes).let { if (it < 0) 1 else it }
        val next = choices[(index + 1) % choices.size]
        refreshMinutes = next
        showRefreshInterval(next)
        val app = application as OlympusIptvApp
        app.appScope.launch { app.playlistStore.saveRefreshIntervalMinutes(next) }
    }

internal fun BrowseActivity.queueNewChannels(live: List<Channel>, vod: List<Channel>, series: List<Channel>) {
        if (live.isNotEmpty()) pendingLive.addAll(live)
        if (vod.isNotEmpty()) pendingVod.addAll(vod)
        if (series.isNotEmpty()) pendingSeries.addAll(series)
        deliverPendingChannels()
    }

internal fun BrowseActivity.deliverPendingChannels() {
        if (isDestroyed) return
        if (binding.groupColumn.visibility != View.VISIBLE) return
        val categoryId = selectedCategoryId ?: return
        if (categoryId == PlaylistLoader.FAVORITES_ID ||
            categoryId == PlaylistLoader.RECENT_ID ||
            categoryId == PlaylistLoader.CONTINUE_ID ||
            categoryId == PlaylistLoader.TOP_WATCHED_ID
        ) return
        val incoming = when (section) {
            GuideSection.TV -> {
                val rows = if (categoryId == PlaylistLoader.ALL_ID) {
                    pendingLive.toList()
                } else {
                    pendingLive.filter { it.categoryId == categoryId }
                }
                pendingLive.clear()
                rows
            }
            GuideSection.MOVIES -> {
                val rows = pendingVod.filter { it.categoryId == categoryId }
                pendingVod.clear()
                rows
            }
            GuideSection.SHOWS -> {
                val rows = pendingSeries.filter { it.categoryId == categoryId }
                pendingSeries.clear()
                rows
            }
        }
        if (incoming.isEmpty()) return
        val seen = visibleChannels.mapTo(HashSet()) { it.id }
        val named = prepareGroup(incoming.filter { it.id !in seen }, activeConfig)
        if (named.isEmpty()) return
        channelAdapter.append(named)
        visibleChannels.addAll(named)
        binding.errorText.visibility = View.GONE
        binding.libraryStatus.visibility = View.GONE
    }

internal fun BrowseActivity.flushGroupRefresh() {
        if (isDestroyed) return
        if (groupsNeedRefresh && source != null && binding.groupColumn.visibility == View.VISIBLE) {
            groupsNeedRefresh = false
            render(restoreFocus = false, reloadChannels = false)
        }
        if (binding.groupColumn.visibility == View.VISIBLE) deliverPendingChannels()
    }

internal fun BrowseActivity.onMovieCount(count: Int) {
        movieCount = count
        val now = SystemClock.uptimeMillis()
        if (now - lastMoviePost < 400 && count % 500 != 0) return
        lastMoviePost = now
        runOnUiThread { publishLibraryStatus() }
    }

internal fun BrowseActivity.onMoviePercent(percent: Int) {
        moviePercent = percent
        runOnUiThread { publishLibraryStatus() }
    }

internal fun BrowseActivity.onShowCount(count: Int) {
        showCount = count
        val now = SystemClock.uptimeMillis()
        if (now - lastShowPost < 400 && count % 500 != 0) return
        lastShowPost = now
        runOnUiThread { publishLibraryStatus() }
    }

internal fun BrowseActivity.onShowPercent(percent: Int) {
        showPercent = percent
        runOnUiThread { publishLibraryStatus() }
    }

internal fun BrowseActivity.publishLibraryStatus() {
        if (!viewReady() || isDestroyed) return
        binding.libraryStatusText.text = when (section) {
            GuideSection.MOVIES -> movieStatus()
            GuideSection.SHOWS -> showStatus()
            else -> movieStatus()
        }
    }

internal fun BrowseActivity.movieStatus(): String {
        val error = movieError
        if (loadingFromDb && moviesPending) return getString(R.string.loading_saved_movies)
        if (moviesPending && movieAttempt > 1 && movieCount == 0) {
            return getString(R.string.loading_movies_retry, movieAttempt, error.orEmpty())
        }
        if (!moviesPending && !error.isNullOrBlank() && movieCount == 0) {
            return getString(R.string.movies_failed, error)
        }
        val percent = moviePercent
        val count = countFormat.format(movieCount)
        return when {
            percent >= 0 && movieCount > 0 -> getString(R.string.loading_movies_percent, percent, count)
            movieCount > 0 -> getString(R.string.loading_movies, count)
            percent >= 0 -> getString(R.string.loading_movies_percent, percent, count)
            else -> getString(R.string.loading_movies_wait)
        }
    }

internal fun BrowseActivity.showStatus(): String {
        if (loadingFromDb && showsPending) return getString(R.string.loading_saved_shows)
        val percent = showPercent
        val count = countFormat.format(showCount)
        return when {
            percent >= 0 && showCount > 0 -> getString(R.string.loading_shows_percent, percent, count)
            showCount > 0 -> getString(R.string.loading_shows, count)
            percent >= 0 -> getString(R.string.loading_shows_percent, percent, count)
            else -> getString(R.string.loading_shows_wait)
        }
    }

internal fun BrowseActivity.showCatalog(fetched: LoadedPlaylist, config: LineupConfig, openGuide: Boolean) {
        source = fetched
        loaded = fetched
        activeConfig = config
        (application as OlympusIptvApp).guideSource = fetched
        val app = application as OlympusIptvApp
        if (fetched.liveChannels.isNotEmpty() && fetched.liveChannels.size <= 200) {
            app.guideChannels = fetched.liveChannels
        } else if (app.guideChannels.isEmpty() || fetched.liveChannels.isNotEmpty()) {
            app.guideChannels = emptyList()
        }
        com.olympus.iptv.portal.DashboardHost.clearGuideCache()
        publishGuide()
        app.appScope.launch(Dispatchers.IO) { app.catalogDb.maintainAfterRefresh() }
        if (epgRepo.useNameEpg(config.nameEpg == true)) epgCache.clear()
        val savedId = playlist?.id
        if (!savedId.isNullOrBlank()) {
            val appJobs = application as OlympusIptvApp
            binding.root.post {
                if (isDestroyed) return@post
                // Phones: never warm dashboard/Watchmode on cold open — New / web trigger that later.
                // TV: still defer, and skip when the catalog stamp is unchanged.
                if (touchPrimary) {
                    appJobs.appScope.launch {
                        delay(6_000L)
                        if (isDestroyed) return@launch
                        runCatching { appJobs.catalogDb.cleanGroupImages(savedId) }
                    }
                } else {
                    val warmDelay = 2_000L
                    appJobs.appScope.launch {
                        delay(warmDelay)
                        if (isDestroyed) return@launch
                        val stamp = runCatching { appJobs.catalogDb.libraryStamp(savedId) }.getOrNull() ?: 0L
                        val stampKey = stamp.toString()
                        val last = runCatching { appJobs.playlistStore.catalogStamp(savedId) }.getOrNull().orEmpty()
                        val catalogFresh = stamp > 0L && stampKey == last
                        if (!catalogFresh) {
                            runCatching { appJobs.catalogDb.cleanGroupImages(savedId) }
                            delay(750L)
                            if (isDestroyed) return@launch
                            runCatching { appJobs.catalogDb.warmDashboard(savedId) }
                            if (stamp > 0L) {
                                runCatching { appJobs.playlistStore.saveCatalogStamp(savedId, stampKey) }
                            }
                        }
                        delay(1_000L)
                        if (isDestroyed) return@launch
                        val cacheFile = java.io.File(appJobs.filesDir, "watchmode-week.json")
                        val needsFresh = !catalogFresh ||
                            com.olympus.iptv.playlist.Watchmode.needsRefresh(cacheFile) ||
                            appJobs.freshCards(savedId).isNullOrEmpty()
                        if (needsFresh) appJobs.warmFresh(savedId)
                    }
                }
            }
        }
        epgRepo.useXmltv(fetched.xmltvByChannel.orEmpty())
        renderedSection = null
        if (binding.groupColumn.visibility == View.VISIBLE) {
            render(restoreFocus = landOnGroups)
            return
        }
        if (!openGuide ||
            panes.isDeviceVisible() ||
            panes.isAboutVisible() ||
            panes.isAdminVisible() ||
            binding.blankPane.visibility == View.VISIBLE ||
            panes.isRefreshVisible() ||
            panes.isVpnVisible()
        ) {
            return
        }
        selectedCategoryId = PlaylistLoader.FAVORITES_ID
        section = GuideSection.TV
        kind = ContentKind.LIVE
        showGuide()
        binding.tabLive.isSelected = true
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        armGroupFocus()
        render(restoreFocus = true)
        reloadRecent()
    }

internal fun BrowseActivity.indexChannels(channels: List<Channel>): Pair<Map<String, List<Channel>>, Map<String, Channel>> {
        val byCategory = HashMap<String, MutableList<Channel>>()
        val byId = HashMap<String, Channel>(channels.size)
        for (channel in channels) {
            byId[channel.id] = channel
            val key = channel.categoryId ?: channel.groupTitle ?: continue
            byCategory.getOrPut(key) { ArrayList() }.add(channel)
        }
        return byCategory to byId
    }

internal fun BrowseActivity.showDashboardAddress() {
        showDashboardUrl(LineupClient.peekDashboardUrl() ?: com.olympus.iptv.portal.DashboardHost.url())
        lifecycleScope.launch {
            val url = runCatching { LineupClient(screen()).dashboardAddress() }.getOrNull()
            if (url.isNullOrBlank() || isDestroyed) return@launch
            showDashboardUrl(url)
        }
    }

internal fun BrowseActivity.focusHomeContinue() {
        if (binding.homeContinue.visibility != View.VISIBLE) {
            landOnContinue = false
            focusGroups()
            return
        }
        landOnContinue = false
        landOnGroups = false
        binding.homeContinuePlay.post {
            if (isDestroyed || binding.homeContinue.visibility != View.VISIBLE) return@post
            if (!binding.homeContinuePlay.requestFocus()) binding.homeContinue.requestFocus()
        }
    }

internal fun BrowseActivity.showDashboardUrl(url: String) {
        dashboardUrlText = url
        val urlValue = panes.deviceOrNull?.deviceUrlValue ?: return
        urlValue.text = url
        urlValue.setTextColor(getColor(R.color.accent))
    }

internal fun BrowseActivity.confirmRemovePlaylist() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.device_remove_playlist_title)
            .setMessage(R.string.device_remove_playlist_message)
            .setPositiveButton(R.string.device_remove_playlist_confirm) { _, _ -> removePlaylistAndRestart() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

internal fun BrowseActivity.removePlaylistAndRestart() {
        val app = application as OlympusIptvApp
        panes.deviceRemovePlaylist.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { AppTunnel.disconnect(screen()) }
                runCatching { stopService(Intent(screen(), RecordingService::class.java)) }
                app.playlistStore.resetDevice()
                app.catalogDb.wipeAll()
                ReminderStore.clear(screen())
                EpisodeCache.clear(filesDir)
                runCatching { File(getExternalFilesDir(null), "recordings").deleteRecursively() }
                runCatching { File(filesDir, "watchmode-week.json").delete() }
                runCatching { File(filesDir, "fresh-cards.json").delete() }
            }
            startActivity(
                Intent(screen(), LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            overridePendingTransition(0, 0)
        }
    }

internal fun BrowseActivity.showBlank() {
        epgPrefetchJob?.cancel()
        binding.tabLive.isSelected = false
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        binding.btnAdmin.isSelected = false
        binding.btnAbout.isSelected = false
        binding.groupColumn.visibility = View.GONE
        binding.contentDivider.visibility = View.GONE
        binding.contentColumn.visibility = View.GONE
        binding.programPane.visibility = View.GONE
        panes.hideAllSettingPanes()
        binding.blankPane.visibility = View.VISIBLE
        hidePreviewChrome()
        updateGroupClock()
    }

internal fun BrowseActivity.paintLastGroupChip() {
        if (!viewReady()) return
        val id = lastGroupId
        val name = lastGroupName
        if (!touchPrimary || id.isNullOrBlank() || name.isNullOrBlank() ||
            id == PlaylistLoader.FAVORITES_ID ||
            binding.groupColumn.visibility != View.VISIBLE
        ) {
            binding.btnLastGroup.visibility = View.GONE
            return
        }
        binding.btnLastGroup.text = getString(R.string.last_group_chip, name)
        binding.btnLastGroup.visibility = View.VISIBLE
    }

internal fun BrowseActivity.openLastGroup() {
        val id = lastGroupId ?: return
        val category = categoryAdapter.categories().firstOrNull { it.id == id }
            ?: Category(id = id, name = lastGroupName.orEmpty())
        Haptics.tick(binding.btnLastGroup)
        selectCategory(category, immediate = true)
    }

internal fun BrowseActivity.showLetterJump() {
        LetterJump.show(this, visibleChannels) { index ->
            binding.channelList.scrollToPosition(index)
            binding.channelList.post {
                if (!isDestroyed) focusRowAt(binding.channelList, index, R.id.playTarget)
            }
        }
    }

internal fun BrowseActivity.paintNowPlayingBar() {
        if (!viewReady()) return
        val app = application as OlympusIptvApp
        val channel = app.lastPlayed
        val match = channel != null && app.lastPlayedPlaylistId == playlist?.id
        if (!match || channel == null) {
            binding.nowPlayingBar.visibility = View.GONE
            return
        }
        val live = channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()
        binding.nowPlayingLabel.text = if (live) {
            getString(R.string.now_playing_live_badge)
        } else {
            getString(R.string.now_playing)
        }
        binding.nowPlayingTitle.text = channel.name
        binding.nowPlayingResume.text = if (live) {
            getString(R.string.now_playing_fullscreen)
        } else {
            getString(R.string.now_playing_resume)
        }
        // Phone strip always; TV also shows when corner PiP is active so users can return.
        val show = if (touchPrimary) {
            binding.groupColumn.visibility == View.VISIBLE
        } else {
            binding.previewCard.visibility == View.VISIBLE || binding.groupColumn.visibility == View.VISIBLE
        }
        binding.nowPlayingBar.visibility = if (show) View.VISIBLE else View.GONE
        channelAdapter.setPlaying(if (match && channel.kind == ContentKind.LIVE) channel.id else null)
    }

internal fun BrowseActivity.resumeNowPlaying() {
        val app = application as OlympusIptvApp
        val channel = app.lastPlayed ?: return
        if (app.lastPlayedPlaylistId != playlist?.id) return
        Haptics.tick(binding.nowPlayingResume)
        play(channel, queueOverride = listOf(channel), zapMode = PlayerActivity.ZAP_LIST, rememberReturn = true)
    }

internal fun BrowseActivity.paintFreshCount() {
        if (!viewReady()) return
        val saved = playlist?.id
        val count = if (saved.isNullOrBlank()) 0 else {
            (application as com.olympus.iptv.OlympusIptvApp).freshCards(saved)?.size ?: 0
        }
        binding.freshCount.text = count.toString()
        binding.freshCount.visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

internal fun BrowseActivity.maybeShowBackupTip() {
        if (!touchPrimary) return
        val prefs = getSharedPreferences("olympus_tips", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("backup_tip_shown", false)) return
        prefs.edit().putBoolean("backup_tip_shown", true).apply()
        Toast.makeText(this, R.string.backup_tip, Toast.LENGTH_LONG).show()
    }

internal fun BrowseActivity.showVpnStatus(state: TunnelState) {
        val detail = when (state) {
            is TunnelState.Off -> getString(R.string.admin_vpn_detail)
            is TunnelState.Connecting -> getString(R.string.vpn_connecting)
            is TunnelState.On -> getString(R.string.vpn_connected, state.label)
            is TunnelState.Reconnecting -> getString(R.string.vpn_reconnecting, state.label)
            is TunnelState.Failed -> state.message
        }
        pendingVpnDetail = detail
        panes.adminOrNull?.cardAdminVpnDetail?.text = detail
        val chip = when (state) {
            is TunnelState.On -> getString(R.string.vpn_chip, state.label)
            is TunnelState.Reconnecting -> getString(R.string.vpn_chip_retry)
            else -> ""
        }
        binding.vpnChip.text = chip
        binding.vpnChip.visibility = if (chip.isEmpty()) View.GONE else View.VISIBLE
    }




internal fun BrowseActivity.restoreHeldChannel() {
        val id = holdChannelId ?: return
        val index = visibleChannels.indexOfFirst { it.id == id }
        if (index < 0) {
            holdChannelId = null
            return
        }
        holdChannelId = null
        binding.channelList.scrollToPosition(index)
        binding.channelList.post {
            if (isDestroyed) return@post
            if (guideMenu == GuideMenu.PROGRAMS) focusProgram(index, currentProgramColumn(index))
            else focusRowAt(binding.channelList, index, R.id.playTarget)
        }
    }

internal fun BrowseActivity.reloadRenamedGroups() {
        val saved = playlist ?: return
        val data = source ?: return
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                val stamp = app.catalogDb.libraryStamp(saved.id)
                if (stamp == seenLibraryStamp) return@withContext null
                stamp to app.catalogDb.categoryNames(saved.id)
            } ?: return@launch
            if (isDestroyed) return@launch
            seenLibraryStamp = update.first
            val names = update.second
            fun cats(bucket: String, list: List<com.olympus.iptv.data.Category>) = list.map { category ->
                val name = names["$bucket\u0000${category.id}"]
                if (name.isNullOrBlank() || name == category.name) category else category.copy(name = name)
            }
            fun chans(bucket: String, list: List<com.olympus.iptv.data.Channel>) = list.map { channel ->
                val id = channel.categoryId ?: return@map channel
                val name = names["$bucket\u0000$id"]
                if (name.isNullOrBlank() || name == channel.groupTitle) channel else channel.copy(groupTitle = name)
            }
            val next = data.copy(
                liveCategories = cats(com.olympus.iptv.playlist.CatalogDb.LIVE, data.liveCategories),
                liveChannels = chans(com.olympus.iptv.playlist.CatalogDb.LIVE, data.liveChannels),
                vodCategories = cats(com.olympus.iptv.playlist.CatalogDb.VOD, data.vodCategories),
                vodChannels = chans(com.olympus.iptv.playlist.CatalogDb.VOD, data.vodChannels),
                seriesCategories = data.seriesCategories?.let { cats(com.olympus.iptv.playlist.CatalogDb.SERIES, it) },
                seriesChannels = data.seriesChannels?.let { chans(com.olympus.iptv.playlist.CatalogDb.SERIES, it) }
            )
            if (next == data) return@launch
            source = next
            loaded = next
            app.guideSource = next
            liveByCategory = emptyMap()
            vodByCategory = emptyMap()
            seriesByCategory = emptyMap()
            groupedSection = null
            if (binding.groupColumn.visibility == View.VISIBLE) render(restoreFocus = false)
        }
    }

internal fun BrowseActivity.reloadRecent() {
        val saved = playlist ?: return
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) { app.playlistStore.recent(saved.id) }
            val stamp = next.joinToString { "${it.channelId}:${it.positionMs}:${it.updatedAt}" }
            if (stamp == recentStamp) return@launch
            recentStamp = stamp
            recentEntries = next
            invalidateTopWatchedCache()
            warmFavoriteAndRecentEpg()
            if (source != null) {
                val groupsOpen = binding.groupColumn.visibility == View.VISIBLE
                val keepRecent = selectedCategoryId == PlaylistLoader.RECENT_ID ||
                    selectedCategoryId == PlaylistLoader.TOP_WATCHED_ID ||
                    selectedCategoryId == PlaylistLoader.CONTINUE_ID
                render(restoreFocus = false, reloadChannels = groupsOpen || keepRecent)
                if (keepRecent && binding.contentColumn.visibility == View.VISIBLE) showChannels()
            }
        }
    }

internal fun BrowseActivity.withRecent(rest: List<Category>): List<Category> {
        val recent = Category(PlaylistLoader.RECENT_ID, getString(R.string.recent_watched), kind)
        return listOf(recent) + withTopWatched(rest)
    }

internal fun BrowseActivity.hasContinue(sectionNow: GuideSection): Boolean {
        if (sectionNow == GuideSection.TV) return false
        val wanted = sectionKey(sectionNow)
        return recentEntries.any { entrySection(it) == wanted && it.canResume() }
    }

internal fun BrowseActivity.withContinue(rest: List<Category>): List<Category> {
        if (!hasContinue(section)) return withRecent(rest)
        val head = Category(PlaylistLoader.CONTINUE_ID, getString(R.string.continue_group), kind)
        return listOf(head) + withRecent(rest)
    }

internal fun BrowseActivity.continueText(channel: Channel): String? {
        val spot = resumeEntry(channel) ?: return null
        val minutes = ((spot.positionMs ?: 0L) / 60_000L).toInt().coerceAtLeast(1)
        val episode = spot.name?.takeIf {
            it.isNotBlank() && it != channel.name && channel.streamUrl.isBlank()
        }
        return if (episode == null) {
            getString(R.string.continue_watching, minutes)
        } else {
            getString(R.string.continue_episode, episode, minutes)
        }
    }

internal fun BrowseActivity.continueProgress(channel: Channel): Int? {
        val spot = resumeEntry(channel) ?: return null
        val position = spot.positionMs ?: return null
        val duration = spot.durationMs ?: return null
        if (duration <= 0L) return null
        return ((position * 1000L) / duration).toInt().coerceIn(1, 1000)
    }

internal fun BrowseActivity.posterRatingLabel(channel: Channel): String? {
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) return null
        val preferTv = !channel.seriesId.isNullOrBlank()
        val peek = com.olympus.iptv.playlist.TitleRatings.peek(channel.name, preferTv) ?: return null
        val score = peek.ratings.firstOrNull()?.scoreLabel ?: return null
        val number = score.substringBefore('/').substringBefore('%').trim()
        if (number.isBlank()) return null
        return "★ $number"
    }

internal fun BrowseActivity.posterMetaLine(channel: Channel): String? {
        val year = Regex("""\b(19|20)\d{2}\b""").find(channel.name)?.value
        val resume = continueText(channel)
        return listOfNotNull(year, resume).joinToString(" · ").ifBlank { null }
    }

internal fun BrowseActivity.recentChannels(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
        val saved = playlist ?: return emptyList()
        val db = (application as OlympusIptvApp).catalogDb
        val wanted = sectionKey(sectionNow)
        val seen = HashSet<String>()
        val rows = ArrayList<Channel>()
        for (entry in recentEntries) {
            if (entrySection(entry) != wanted) continue
            val id = if (wanted == "series") entry.seriesId ?: entry.channelId else entry.channelId
            if (id.isNullOrBlank() || !seen.add(id)) continue
            val row = db.readChannel(saved.id, libraryBucket(sectionNow), id) ?: continue
            val tagged = if (wanted == "series" && row.seriesId.isNullOrBlank()) row.copy(seriesId = id) else row
            rows.add(tagged)
            if (rows.size == 20) break
        }
        return prepareGroup(rows, config)
    }

internal fun BrowseActivity.continueChannels(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
        val saved = playlist ?: return emptyList()
        val db = (application as OlympusIptvApp).catalogDb
        val wanted = sectionKey(sectionNow)
        val seen = HashSet<String>()
        val rows = ArrayList<Channel>()
        for (entry in recentEntries) {
            if (entrySection(entry) != wanted || !entry.canResume()) continue
            if (wanted == "series") {
                val seriesId = entry.seriesId ?: entry.channelId ?: continue
                if (!seen.add(seriesId)) continue
                val show = db.readChannel(saved.id, CatalogDb.SERIES, seriesId)
                val episodeId = entry.channelId
                val cached = if (episodeId.isNullOrBlank()) null else {
                    EpisodeCache.peek(filesDir, saved.id, seriesId)?.firstOrNull { it.id == episodeId }
                }
                val row = if (cached != null) {
                    cached.copy(
                        seriesId = seriesId,
                        name = EpisodeSequence.heading(show?.name.orEmpty(), cached),
                        logoUrl = cached.logoUrl ?: show?.logoUrl
                    )
                } else {
                    show ?: continue
                }
                val tagged = if (row.seriesId.isNullOrBlank()) row.copy(seriesId = seriesId) else row
                rows.add(tagged)
            } else {
                val id = entry.channelId ?: continue
                if (!seen.add(id)) continue
                val row = db.readChannel(saved.id, libraryBucket(sectionNow), id) ?: continue
                rows.add(row)
            }
            if (rows.size == 20) break
        }
        return prepareGroup(rows, config)
    }

internal fun BrowseActivity.reloadFavorites() {
        val saved = playlist ?: return
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) { app.playlistStore.favorites(saved.id) }
            com.olympus.iptv.playlist.HostWarmup.launch(app, saved.id, ids)
            if (ids == favoriteOrder) return@launch
            applyFavorites(ids)
            if (selectedCategoryId == PlaylistLoader.FAVORITES_ID &&
                binding.groupColumn.visibility == View.VISIBLE
            ) {
                showChannels()
            }
        }
    }




internal fun BrowseActivity.favoriteMoveDirection(event: KeyEvent): Int? {
        val moved = event.isCtrlPressed || event.isAltPressed || event.isShiftPressed
        return when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP -> if (moved) -1 else null
            KeyEvent.KEYCODE_DPAD_DOWN -> if (moved) 1 else null
            else -> null
        }
    }

internal fun BrowseActivity.channelHasMore(): Boolean = !pageExhausted

internal fun BrowseActivity.channelStep(): Int {
        val grid = binding.channelList.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
        return if (grid != null && section != GuideSection.TV) grid.spanCount else 1
    }

internal fun BrowseActivity.applyChannelLayout() {
        val posters = section != GuideSection.TV
        if (channelAdapter.posterMode != posters) binding.channelList.recycledViewPool.clear()
        channelAdapter.posterMode = posters
        binding.contentHint.visibility = if (posters) View.VISIBLE else View.GONE
        val manager = binding.channelList.layoutManager
        // Phones: one poster column scrolls smoother than a 2-up grid on mid-range devices.
        val posterSpan = if (touchPrimary) 1 else 4
        if (posters && manager !is androidx.recyclerview.widget.GridLayoutManager) {
            binding.channelList.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, posterSpan)
        } else if (posters && manager is androidx.recyclerview.widget.GridLayoutManager && manager.spanCount != posterSpan) {
            manager.spanCount = posterSpan
        } else if (!posters && manager is androidx.recyclerview.widget.GridLayoutManager) {
            binding.channelList.layoutManager = LinearLayoutManager(this)
        }
    }

internal fun BrowseActivity.wireHomeContinue() {
        binding.homeContinue.setOnClickListener {
            Haptics.tick(binding.homeContinue)
            openHomeContinue()
        }
        binding.homeContinuePlay.setOnClickListener {
            Haptics.tick(binding.homeContinuePlay)
            openHomeContinue()
        }
        binding.homeContinue.setOnLongClickListener {
            openContinueGroup()
            true
        }
        binding.homeContinuePlay.setOnLongClickListener {
            openContinueGroup()
            true
        }
        if (!touchPrimary) {
            binding.homeContinue.setOnFocusChangeListener { _, hasFocus ->
                FocusMotion.animate(binding.homeContinue, hasFocus)
            }
        }
        binding.homeContinue.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    openHomeContinue()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    binding.homeContinuePlay.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusGroups()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.shortcutRow.visibility == View.VISIBLE) {
                        binding.btnFavoritesSetup.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        binding.homeContinuePlay.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    openHomeContinue()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    binding.homeContinue.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusGroups()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.shortcutRow.visibility == View.VISIBLE) {
                        binding.btnFavoritesSetup.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

internal fun BrowseActivity.wireHomeContinueFocusChain() {
        val continueVisible = binding.homeContinue.visibility == View.VISIBLE
        val downId = if (GuideFeel.chipDownTarget(continueVisible) == "homeContinue") {
            R.id.homeContinue
        } else {
            R.id.categoryList
        }
        listOf(
            binding.btnFavoritesSetup,
            binding.btnQueue,
            binding.btnFresh,
            binding.btnRemind,
            binding.btnParental
        ).forEach { chip ->
            chip.nextFocusDownId = downId
        }
        if (!continueVisible) return
        binding.homeContinue.nextFocusDownId = R.id.categoryList
        binding.homeContinuePlay.nextFocusDownId = R.id.categoryList
        binding.homeContinue.nextFocusRightId = R.id.homeContinuePlay
        binding.homeContinuePlay.nextFocusLeftId = R.id.homeContinue
        if (binding.shortcutRow.visibility == View.VISIBLE) {
            binding.homeContinue.nextFocusUpId = R.id.btnFavoritesSetup
            binding.homeContinuePlay.nextFocusUpId = R.id.btnFavoritesSetup
        } else {
            binding.homeContinue.nextFocusUpId = View.NO_ID
            binding.homeContinuePlay.nextFocusUpId = View.NO_ID
        }
        if (touchPrimary) {
            binding.homeContinue.isFocusableInTouchMode = false
            binding.homeContinuePlay.isFocusableInTouchMode = false
        }
    }

internal fun BrowseActivity.paintHomeContinue() {
        if (!viewReady()) return
        if (binding.groupColumn.visibility != View.VISIBLE) {
            binding.homeContinue.visibility = View.GONE
            wireHomeContinueFocusChain()
            return
        }
        val config = activeConfig
        when (section) {
            GuideSection.TV -> {
                val rows = recentChannels(GuideSection.TV, config)
                val first = rows.firstOrNull()
                if (first == null) {
                    binding.homeContinue.visibility = View.GONE
                    wireHomeContinueFocusChain()
                    return
                }
                binding.homeContinueTitle.text = first.name
                binding.homeContinueMeta.text = getString(R.string.recent_card)
                binding.homeContinue.visibility = View.VISIBLE
                binding.homeContinue.tag = first
            }
            GuideSection.MOVIES, GuideSection.SHOWS -> {
                val rows = continueChannels(section, config)
                val first = rows.firstOrNull()
                if (first == null) {
                    binding.homeContinue.visibility = View.GONE
                    wireHomeContinueFocusChain()
                    return
                }
                binding.homeContinueTitle.text = first.name
                binding.homeContinueMeta.text = continueText(first) ?: getString(R.string.continue_card)
                binding.homeContinue.visibility = View.VISIBLE
                binding.homeContinue.tag = first
            }
        }
        wireHomeContinueFocusChain()
        if (GuideFeel.landOnContinueStrip(section == GuideSection.TV, binding.homeContinue.visibility == View.VISIBLE) &&
            landOnContinue
        ) {
            focusHomeContinue()
        } else if (landOnContinue) {
            landOnContinue = false
        }
    }

internal fun BrowseActivity.openHomeContinue() {
        val channel = binding.homeContinue.tag as? Channel ?: return
        when (section) {
            GuideSection.TV -> {
                selectedCategoryId = PlaylistLoader.RECENT_ID
                selectedCategoryName = getString(R.string.recent_watched)
                categoryAdapter.setSelected(PlaylistLoader.RECENT_ID)
                play(channel, queueOverride = listOf(channel), zapMode = PlayerActivity.ZAP_LIST)
            }
            GuideSection.MOVIES, GuideSection.SHOWS -> {
                selectedCategoryId = PlaylistLoader.CONTINUE_ID
                selectedCategoryName = getString(R.string.continue_group)
                categoryAdapter.setSelected(PlaylistLoader.CONTINUE_ID)
                play(channel)
            }
        }
    }

internal fun BrowseActivity.openContinueGroup() {
        when (section) {
            GuideSection.TV -> {
                val category = Category(PlaylistLoader.RECENT_ID, getString(R.string.recent_watched), kind)
                selectCategory(category, immediate = true)
            }
            GuideSection.MOVIES, GuideSection.SHOWS -> {
                if (!hasContinue(section)) return
                val category = Category(PlaylistLoader.CONTINUE_ID, getString(R.string.continue_group), kind)
                selectCategory(category, immediate = true)
            }
        }
    }

internal fun BrowseActivity.markChannelsReady() {
        if (!landOnOpened) return
        channelsStamp = openStamp
        openFocusTries = 0
        focusOpenedGuide()
    }

internal fun BrowseActivity.titleFocusHeld(): Boolean {
        val focused = currentFocus ?: return false
        if (!focusIsIn(binding.channelList)) return false
        val row = binding.channelList.getChildAdapterPosition(
            binding.channelList.findContainingItemView(focused) ?: return false
        )
        return row == 0
    }

internal fun BrowseActivity.focusFirstTitle() {
        binding.channelList.post {
            if (!landOnOpened) return@post
            focusRowAt(binding.channelList, 0, R.id.playTarget)
            binding.channelList.post {
                if (titleFocusHeld()) {
                    landOnOpened = false
                    openFocusTries = 0
                } else {
                    retryOpenedFocus()
                }
            }
        }
    }

internal fun BrowseActivity.fitChannelColumn() {
        if (guideMenu != GuideMenu.CHANNELS && guideMenu != GuideMenu.PROGRAMS) return
        if (binding.programPane.visibility != View.VISIBLE) return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val number = if (section == GuideSection.TV) (32f + 6f) * density else 0f
        val around = (6f + 6f + 24f + 8f + 4f) * density + number
        val twoLines = widestNamePx() * 0.55f + around
        val parent = binding.guideRow.width.takeIf { it > 0 } ?: metrics.widthPixels
        val floor = (300 * density).toInt()
        val ceiling = (380 * density).toInt().coerceAtMost((parent * 0.42f).toInt().coerceAtLeast(floor))
        val capped = twoLines.toInt().coerceIn(floor, ceiling)
        val params = binding.contentColumn.layoutParams as LinearLayout.LayoutParams
        if (params.width == capped) return
        params.width = capped
        params.weight = 0f
        binding.contentColumn.layoutParams = params
    }

internal fun BrowseActivity.focusChannelPane() {
        val manager = binding.channelList.layoutManager as? LinearLayoutManager
        val index = manager?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        focusRowAt(binding.channelList, index, R.id.playTarget)
    }

internal fun BrowseActivity.rowSelectKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

internal fun BrowseActivity.showPhoneRowSheet(
        title: String,
        labels: List<String>,
        actions: List<Int>,
        runAction: (Int) -> Unit
    ) {
        val sheet = BottomSheetDialog(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(getColor(R.color.bg_elevated))
        }
        column.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        })
        labels.forEachIndexed { index, label ->
            column.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                minHeight = (52 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_nav)
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (14 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                )
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = (6 * resources.displayMetrics.density).toInt()
                layoutParams = params
                setOnClickListener {
                    Haptics.tick(this)
                    sheet.dismiss()
                    runAction(actions[index])
                }
            })
        }
        sheet.setContentView(column)
        sheet.show()
    }

internal fun BrowseActivity.focusedChannelRow(): Int {
        val focused = currentFocus ?: return 0
        val position = binding.channelList.findContainingViewHolder(focused)?.bindingAdapterPosition ?: return 0
        return position.takeIf { it >= 0 } ?: 0
    }

internal fun BrowseActivity.revealPendingChannel() {
        val index = pendingChannelFocus
        if (index < 0) return
        val focusId = pendingChannelFocusId
        pendingChannelFocus = -1
        pendingChannelFocusId = View.NO_ID
        val count = channelAdapter.itemCount
        if (count == 0) return
        focusRowAt(binding.channelList, if (index < count) index else 0, focusId)
    }

internal fun BrowseActivity.openFavoritesSetup() {
        val saved = playlist ?: return
        val ready = loaded != null && !moviesPending && !showsPending
        startActivity(
            Intent(this, com.olympus.iptv.ui.login.FavoritesSetupActivity::class.java)
                .putExtra(com.olympus.iptv.ui.login.FavoritesSetupActivity.EXTRA_PLAYLIST_ID, saved.id)
                .putExtra(com.olympus.iptv.ui.login.FavoritesSetupActivity.EXTRA_CATALOG_READY, ready)
                .putExtra(com.olympus.iptv.ui.login.FavoritesSetupActivity.EXTRA_FROM_GUIDE, true)
        )
    }

internal fun BrowseActivity.armGroupFocus() {
        landOnGroups = true
        groupFocusTries = 0
        focusGroupIndex = -1
        if (focusIsIn(binding.navRail)) currentFocus?.clearFocus()
    }

internal fun BrowseActivity.focusGroups() {
        if (landOnOpened) return
        if (categoryAdapter.itemCount == 0) {
            if (landOnGroups) retryGroupFocus()
            return
        }
        val index = when {
            landOnGroups -> categoryAdapter.selectedPosition()
                .coerceIn(0, categoryAdapter.itemCount - 1)
            focusGroupIndex >= 0 -> focusGroupIndex
                .coerceIn(0, categoryAdapter.itemCount - 1)
                .also { focusGroupIndex = -1 }
            else -> categoryAdapter.selectedPosition()
                .coerceIn(0, categoryAdapter.itemCount - 1)
        }
        binding.categoryList.scrollToPosition(index)
        binding.categoryList.post {
            if (landOnOpened) return@post
            val target = binding.categoryList.findViewHolderForAdapterPosition(index)?.itemView
            if (target != null) {
                target.requestFocus()
                if (landOnGroups) {
                    focusHandler.post {
                        if (!landOnGroups) return@post
                        val held = binding.categoryList.findContainingItemView(currentFocus ?: return@post)
                        val pos = if (held == null) -1 else binding.categoryList.getChildAdapterPosition(held)
                        if (pos == index) {
                            landOnGroups = false
                            groupFocusTries = 0
                            focusGroupIndex = -1
                        } else {
                            retryGroupFocus()
                        }
                    }
                }
            } else if (landOnGroups) {
                retryGroupFocus()
            } else {
                binding.categoryList.post {
                    binding.categoryList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                }
            }
        }
    }

internal fun BrowseActivity.retryGroupFocus() {
        if (!landOnGroups || groupFocusTries >= 40) {
            landOnGroups = false
            groupFocusTries = 0
            focusGroupIndex = -1
            return
        }
        groupFocusTries += 1
        focusHandler.postDelayed({ focusGroups() }, 40L)
    }

internal fun BrowseActivity.isLiveBadgeGroup(category: Category): Boolean {
        if (section != GuideSection.TV) return false
        val name = category.name
        return name.contains("espn", ignoreCase = true) ||
            name.contains("dazn", ignoreCase = true) ||
            name.contains("sport", ignoreCase = true) ||
            name.contains("event", ignoreCase = true)
    }

internal fun BrowseActivity.refreshGroupCounts() {
        val saved = playlist ?: return
        val bucket = when (section) {
            GuideSection.TV -> CatalogDb.LIVE
            GuideSection.MOVIES -> CatalogDb.VOD
            GuideSection.SHOWS -> if (source?.seriesCategories.isNullOrEmpty()) CatalogDb.VOD else CatalogDb.SERIES
        }
        val token = ++groupCountToken
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val stamp = withContext(Dispatchers.IO) { app.catalogDb.libraryStamp(saved.id) }
            if (token != groupCountToken || isDestroyed) return@launch
            val key = saved.id + "\u0000" + bucket + "\u0000" + stamp
            val cached = groupCountCache[key]
            if (cached != null) {
                val changed = groupCounts != cached
                if (!changed) return@launch
                groupCounts = cached
                categoryAdapter.refreshDetails()
                if (hideEmptyGroups) render(restoreFocus = false, reloadChannels = false)
                return@launch
            }
            val counts = withContext(Dispatchers.IO) { app.catalogDb.categoryCounts(saved.id, bucket) }
            if (token != groupCountToken || isDestroyed) return@launch
            if (groupCountCache.size > 9) groupCountCache.clear()
            groupCountCache[key] = counts
            val changed = groupCounts != counts
            if (!changed) return@launch
            groupCounts = counts
            categoryAdapter.refreshDetails()
            if (hideEmptyGroups) render(restoreFocus = false, reloadChannels = false)
        }
    }

internal fun BrowseActivity.render(restoreFocus: Boolean = false, reloadChannels: Boolean = true) {
        val data = source ?: return
        val favorites = Category(
            id = PlaylistLoader.FAVORITES_ID,
            name = getString(R.string.favorites),
            kind = kind
        )
        val config = activeConfig
        val categories = when (section) {
            GuideSection.TV -> {
                val base = withoutEmptyGroups(visibleGroups(data.liveCategories, config))
                val withAll = if (base.none { it.id == PlaylistLoader.ALL_ID }) {
                    listOf(Category(PlaylistLoader.ALL_ID, getString(R.string.all_channels))) + base
                } else {
                    base
                }
                val spotlight = Category(PlaylistLoader.FOR_YOU_ID, getString(R.string.for_you), kind)
                listOf(spotlight) + withRecent(listOf(favorites) + withAll)
            }
            GuideSection.MOVIES -> withContinue(
                listOf(favorites) + withoutEmptyGroups(visibleGroups(data.vodCategories, config))
            )
            GuideSection.SHOWS -> {
                val shows = data.seriesCategories.orEmpty().ifEmpty {
                    data.vodCategories.filter { PlaylistLoader.isSeriesName(it.name) }
                }
                withContinue(listOf(favorites) + withoutEmptyGroups(visibleGroups(shows, config)))
            }
        }
        if (section != GuideSection.TV &&
            selectedCategoryId == PlaylistLoader.FAVORITES_ID &&
            favoriteIds.isEmpty() &&
            categories.size > 1
        ) {
            selectedCategoryId = categories[1].id
        }
        if (selectedCategoryId == null || categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = categories.firstOrNull()?.id
        }
        categoryAdapter.submit(categories, selectedCategoryId)
        refreshGroupCounts()
        if (binding.groupPills.visibility == View.VISIBLE) syncGroupPills()
        paintHomeContinue()
        if (reloadChannels && binding.contentColumn.visibility == View.VISIBLE) showChannels()
        renderedSection = section
        if (restoreFocus) focusGroups()
    }

internal fun BrowseActivity.showChannels() {
        val catalog = source ?: return
        val query = ++channelQuery
        val categoryId = selectedCategoryId
        val sectionNow = section
        val config = activeConfig
        channelJob?.cancel()
        val waiting = when (sectionNow) {
            GuideSection.MOVIES -> moviesPending && catalog.vodCategories.isEmpty()
            GuideSection.SHOWS -> showsPending && catalog.seriesCategories.isNullOrEmpty()
            GuideSection.TV -> false
        }
        if (waiting) {
            showChannelWait(if (sectionNow == GuideSection.SHOWS) showStatus() else movieStatus())
            return
        }
        if (categoryId == PlaylistLoader.FOR_YOU_ID) {
            binding.libraryStatus.visibility = View.GONE
            channelJob = lifecycleScope.launch {
                val channels = withContext(Dispatchers.IO) { forYouChannels(config) }
                if (!stillShowing(query, sectionNow, categoryId)) return@launch
                channels.firstOrNull()?.logoUrl?.let { glowFrom(it) }
                publishChannels(channels, scrollTop = true)
            }
            return
        }
        if (categoryId == PlaylistLoader.RECENT_ID ||
            categoryId == PlaylistLoader.CONTINUE_ID ||
            categoryId == PlaylistLoader.TOP_WATCHED_ID
        ) {
            // Keep prior rows visible; swap when the list is ready so open feels instant.
            binding.libraryStatus.visibility = View.GONE
            channelJob = lifecycleScope.launch {
                val channels = withContext(Dispatchers.IO) {
                    when (categoryId) {
                        PlaylistLoader.CONTINUE_ID -> continueChannels(sectionNow, config)
                        PlaylistLoader.TOP_WATCHED_ID -> topWatchedFavorites(sectionNow, config)
                        else -> recentChannels(sectionNow, config)
                    }
                }
                if (!stillShowing(query, sectionNow, categoryId)) return@launch
                publishChannels(channels, scrollTop = true)
            }
            return
        }
        if (categoryId == PlaylistLoader.FAVORITES_ID) {
            if (favoriteIds.isEmpty()) {
                publishChannels(emptyList(), scrollTop = true)
                return
            }
            binding.libraryStatus.visibility = View.GONE
            channelJob = lifecycleScope.launch {
                val channels = withContext(Dispatchers.IO) { libraryFavorites(sectionNow, config) }
                if (!stillShowing(query, sectionNow, categoryId)) return@launch
                publishChannels(channels, scrollTop = true)
            }
            return
        }
        if (categoryId != null) {
            loadGroupPage(reset = true)
            return
        }
    }

internal fun BrowseActivity.stillShowing(query: Int, sectionNow: GuideSection, categoryId: String?): Boolean {
        val open = binding.groupColumn.visibility == View.VISIBLE ||
            binding.contentColumn.visibility == View.VISIBLE
        return query == channelQuery &&
            !isDestroyed &&
            open &&
            section == sectionNow &&
            selectedCategoryId == categoryId
    }

internal fun BrowseActivity.libraryBucket(sectionNow: GuideSection): String = when (sectionNow) {
        GuideSection.TV -> CatalogDb.LIVE
        GuideSection.MOVIES -> CatalogDb.VOD
        GuideSection.SHOWS -> CatalogDb.SERIES
    }

internal fun BrowseActivity.libraryGroup(sectionNow: GuideSection, categoryId: String, config: LineupConfig): List<Channel> {
        val saved = playlist ?: return emptyList()
        val rows = (application as OlympusIptvApp).catalogDb.readCategoryChannels(
            saved.id,
            libraryBucket(sectionNow),
            categoryId
        )
        return prepareGroup(rows, config)
    }

internal fun BrowseActivity.forYouChannels(config: LineupConfig): List<Channel> {
        val recent = recentChannels(GuideSection.TV, config)
        val favorites = libraryFavorites(GuideSection.TV, config)
        val sports = (recent + favorites).filter { channel ->
            val title = epgCache[channel.id]?.now?.title ?: channel.name
            com.olympus.iptv.ui.browse.SportsEvent.isMatchup(title)
        }
        val ordered = LinkedHashSet<Channel>()
        sports.forEach { ordered.add(it) }
        recent.take(8).forEach { ordered.add(it) }
        favorites.take(16).forEach { ordered.add(it) }
        return ordered.toList()
    }

internal fun BrowseActivity.libraryFavorites(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
        val saved = playlist ?: return emptyList()
        val section = favoriteSection(sectionNow)
        val keys = favoriteOrder.filter { Favorites.inSection(it, section) }
        val ids = keys.map { it.substringAfter(':') }
        val rows = (application as OlympusIptvApp).catalogDb.readChannelsByIds(
            saved.id,
            libraryBucket(sectionNow),
            ids
        ).map { channel ->
            if (section == Favorites.SERIES && channel.seriesId.isNullOrBlank()) channel.copy(seriesId = channel.id) else channel
        }
        return prepareGroup(Favorites.ordered(keys, rows.associateBy { it.id }, favoriteIds), config)
    }


internal fun BrowseActivity.topWatchedFavorites(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
        ensureTopWatchedCache()
        val wanted = sectionKey(sectionNow)
        val orderedIds = topWatchedIdsBySection[wanted].orEmpty()
        val favorites = libraryFavorites(sectionNow, config)
        if (favorites.isEmpty()) return emptyList()
        if (orderedIds.isEmpty()) return favorites.take(10)
        val byId = favorites.associateBy { it.id }
        val ranked = orderedIds.mapNotNull { byId[it] }
        return ranked.ifEmpty { favorites.take(10) }
    }

internal fun BrowseActivity.showChannelWait(text: String) {
        pageCursor = null
        pageExhausted = false
        pageLoading = false
        visibleChannels.clear()
        channelAdapter.submit(emptyList(), null)
        binding.channelList.scrollToPosition(0)
        binding.libraryStatus.visibility = View.VISIBLE
        binding.libraryStatusText.text = text
        binding.errorText.visibility = View.GONE
    }

internal fun BrowseActivity.publishChannels(channels: List<Channel>, scrollTop: Boolean) {
        (application as OlympusIptvApp).appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            com.olympus.iptv.data.NameStrip.prepare(channels.map { it.name })
        }
        visibleChannels.clear()
        visibleChannels.addAll(channels)
        channelAdapter.submit(
            channels,
            groupLabel(selectedCategoryId),
            sequentialNumbers = selectedCategoryId == PlaylistLoader.FAVORITES_ID
        )
        if (scrollTop) binding.channelList.scrollToPosition(0)
        finishChannelPane(channels)
        syncProgramGuide()
        markChannelsReady()
        restoreHeldChannel()
        if (restorePlaybackFocus) {
            restorePlaybackFocus = false
            binding.channelList.post { restoreAfterPlayer() }
        }
        if (binding.previewCard.visibility == View.VISIBLE &&
            !focusIsIn(binding.channelList) &&
            !focusIsIn(binding.programPane)
        ) {
            channels.firstOrNull { it.kind == ContentKind.LIVE && it.streamUrl.isNotBlank() }?.let { queuePreview(it) }
        }
    }

internal fun BrowseActivity.rememberGroupScroll() {
        val categoryId = selectedCategoryId ?: return
        val layout = binding.channelList.layoutManager as? LinearLayoutManager ?: return
        val first = layout.findFirstVisibleItemPosition()
        if (first >= 0) groupScrollOffset[categoryId] = first
        // Drop huge in-memory pages when returning to the groups list.
        if (visibleChannels.size > 400) {
            visibleChannels.clear()
            channelAdapter.submit(emptyList(), null)
            pageCursor = null
            pageExhausted = false
        }
        paintNowPlayingBar()
        paintLastGroupChip()
    }

internal fun BrowseActivity.showChannelSkeleton(show: Boolean) {
        if (!viewReady()) return
        binding.channelSkeleton.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.libraryStatus.visibility = View.GONE
            binding.errorText.visibility = View.GONE
        }
    }

internal fun BrowseActivity.maybePrefetchGroupPage(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        if (pageExhausted || pageLoading) return
        val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val last = layout.findLastVisibleItemPosition()
        if (last == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val remain = pagePrefetchRemain(selectedCategoryId)
        if (last >= channelAdapter.itemCount - remain) loadGroupPage(reset = false)
    }

internal fun BrowseActivity.loadGroupPage(reset: Boolean) {
        if (reset) {
            pageCursor = null
            pageExhausted = false
            pageLoading = false
            // First page paints as soon as DB returns — no blank “updating” flash.
            binding.libraryStatus.visibility = View.GONE
            binding.errorText.visibility = View.GONE
            if (visibleChannels.isEmpty()) {
                channelAdapter.submit(emptyList(), groupLabel(selectedCategoryId), sequentialNumbers = false)
                showChannelSkeleton(true)
            }
        }
        if (pageExhausted || pageLoading) return
        val saved = playlist ?: return
        val sectionNow = section
        val categoryId = selectedCategoryId ?: return
        pageLoading = true
        if (reset) emptyPageSkips = 0
        channelJob?.cancel()
        val query = channelQuery
        val config = activeConfig
        val cursor = pageCursor
        val bucket = libraryBucket(sectionNow)
        val hiddenChannels = config.hiddenChannels.orEmpty().toSet()
        val hiddenGroups = config.hiddenGroups.orEmpty().toSet()
        val hiddenCountries = config.hiddenCountries.orEmpty().toSet()
        val pageBucket = if (categoryId == PlaylistLoader.ALL_ID) CatalogDb.LIVE else bucket
        val pageCategory = if (categoryId == PlaylistLoader.ALL_ID) null else categoryId
        val pageLimit = pageSizeFor(categoryId)
        val groupName = selectedCategoryName.trim()
        channelJob = lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                val db = (application as OlympusIptvApp).catalogDb
                if (pageCategory != null) {
                    // Opened group: page that category directly so card count and list stay aligned.
                    var opened = db.readGroupPage(
                        saved.id,
                        pageBucket,
                        pageCategory,
                        pageLimit,
                        cursor,
                        hiddenChannels
                    )
                    // If id matched the card count but rows use a different key, retry by group title.
                    if (opened.channels.isEmpty() &&
                        cursor == null &&
                        groupName.isNotBlank() &&
                        !groupName.equals(pageCategory, ignoreCase = true)
                    ) {
                        opened = db.readGroupPage(
                            saved.id,
                            pageBucket,
                            groupName,
                            pageLimit,
                            null,
                            hiddenChannels
                        )
                    }
                    opened
                } else {
                    db.readVisiblePage(
                        saved.id,
                        pageBucket,
                        null,
                        pageLimit,
                        cursor,
                        hiddenChannels,
                        hiddenGroups,
                        hiddenCountries,
                        config.includeInternational
                    )
                }
            }
            val named = prepareGroup(page.channels, config)
            if (!stillShowing(query, sectionNow, categoryId)) {
                pageLoading = false
                return@launch
            }
            if (page.cursor != null) pageCursor = page.cursor
            if (page.exhausted) pageExhausted = true
            pageLoading = false
            if (cursor == null) {
                if (named.isEmpty() && !page.exhausted) {
                    // Keep skeleton while skipping empty filtered pages — do not flash "No channels".
                    emptyPageSkips += 1
                    if (emptyPageSkips <= MAX_EMPTY_PAGE_SKIPS) {
                        binding.channelList.post { loadGroupPage(reset = false) }
                    } else {
                        pageExhausted = true
                        visibleChannels.clear()
                        showChannelSkeleton(false)
                        channelAdapter.submit(emptyList(), groupLabel(categoryId), sequentialNumbers = false)
                        finishChannelPane(emptyList())
                        markChannelsReady()
                    }
                    return@launch
                }
                visibleChannels.clear()
                visibleChannels.addAll(named)
                showChannelSkeleton(false)
                channelAdapter.submit(named, groupLabel(categoryId), sequentialNumbers = false)
                refreshUnhealthy()
                val restoreId = returnChannelId ?: holdChannelId
                val restoreRow = if (!restoreId.isNullOrBlank()) {
                    named.indexOfFirst { it.id == restoreId }
                } else {
                    -1
                }
                val remembered = groupScrollOffset[categoryId] ?: 0
                when {
                    restoreRow >= 0 -> binding.channelList.scrollToPosition(restoreRow)
                    remembered in named.indices -> binding.channelList.scrollToPosition(remembered)
                    else -> binding.channelList.scrollToPosition(0)
                }
                finishChannelPane(named)
                markChannelsReady()
                binding.channelList.post { maybePrefetchGroupPage(binding.channelList) }
            } else if (named.isNotEmpty()) {
                if (visibleChannels.isEmpty()) {
                    visibleChannels.addAll(named)
                    showChannelSkeleton(false)
                    channelAdapter.submit(named, groupLabel(categoryId), sequentialNumbers = false)
                    binding.channelList.scrollToPosition(0)
                    finishChannelPane(named)
                    markChannelsReady()
                } else {
                    val seen = visibleChannels.mapTo(HashSet()) { it.id }
                    val fresh = named.filter { it.id !in seen }
                    if (fresh.isNotEmpty()) {
                        channelAdapter.append(fresh)
                        visibleChannels.addAll(fresh)
                        scheduleEpisodePrefetch()
                    }
                }
                revealPendingChannel()
                binding.channelList.post { maybePrefetchGroupPage(binding.channelList) }
            }
            if (pageExhausted) deliverPendingChannels()
            // Skip empty filtered pages without tight recursion — cap so a bad group cannot spin forever.
            if (named.isEmpty() && !pageExhausted) {
                emptyPageSkips += 1
                if (emptyPageSkips <= MAX_EMPTY_PAGE_SKIPS) {
                    binding.channelList.post { loadGroupPage(reset = false) }
                } else {
                    pageExhausted = true
                    if (visibleChannels.isEmpty()) {
                        showChannelSkeleton(false)
                        finishChannelPane(emptyList())
                    }
                    revealPendingChannel()
                }
            } else {
                emptyPageSkips = 0
                if (named.isEmpty()) {
                    if (visibleChannels.isEmpty() && pageExhausted) finishChannelPane(emptyList())
                    revealPendingChannel()
                }
            }
            if (section == GuideSection.TV && binding.programPane.visibility == View.VISIBLE) {
                syncProgramGuide()
            } else if (landOnOpened) {
                focusOpenedGuide()
            }
            if (cursor == null && named.isNotEmpty()) restoreHeldChannel()
        }
    }

internal fun BrowseActivity.finishChannelPane(channels: List<Channel>) {
        binding.libraryStatus.visibility = View.GONE
        showChannelSkeleton(false)
        if (channels.isEmpty()) {
            binding.errorText.visibility = View.VISIBLE
            val favoritesEmpty = selectedCategoryId == PlaylistLoader.FAVORITES_ID
            val continueEmpty = selectedCategoryId == PlaylistLoader.CONTINUE_ID
            binding.errorText.text = when {
                section == GuideSection.MOVIES && !movieError.isNullOrBlank() && source?.vodChannels.isNullOrEmpty() ->
                    getString(R.string.movies_failed, movieError)
                section == GuideSection.SHOWS && !showError.isNullOrBlank() && source?.seriesChannels.isNullOrEmpty() ->
                    showError
                favoritesEmpty -> getString(R.string.empty_favorites_cta)
                continueEmpty -> getString(R.string.empty_continue_cta)
                else -> getString(R.string.no_channels)
            }
            val emptyCta = touchPrimary && (favoritesEmpty || continueEmpty)
            if (emptyCta) {
                binding.errorText.setOnClickListener {
                    when {
                        favoritesEmpty -> openFavoritesSetup()
                        continueEmpty -> openSection(
                            if (section == GuideSection.SHOWS) GuideSection.SHOWS else GuideSection.MOVIES
                        )
                        else -> Unit
                    }
                }
            } else {
                binding.errorText.setOnClickListener(null)
            }
            binding.errorText.isClickable = emptyCta
            binding.errorText.isFocusable = false
            binding.errorText.isFocusableInTouchMode = false
        } else {
            binding.errorText.visibility = View.GONE
            binding.errorText.setOnClickListener(null)
            binding.errorText.isClickable = false
            binding.errorText.isFocusable = false
            schedulePrefetch(channels)
            scheduleEpisodePrefetch()
            showChannelDetail(channels.first())
        }
    }

internal fun BrowseActivity.groupLabel(categoryId: String?): String? {
        if (categoryId == null ||
            categoryId == PlaylistLoader.ALL_ID ||
            categoryId == PlaylistLoader.FAVORITES_ID
        ) {
            return null
        }
        return activeConfig.groupNames?.get(categoryId)
    }

internal fun BrowseActivity.moveFavorite(channel: Channel, direction: Int) {
        if (selectedCategoryId != PlaylistLoader.FAVORITES_ID) return
        val key = Favorites.storedKey(channel, favoriteOrder)
        if (!ListOrder.swap(favoriteOrder, key, direction)) return
        val savedId = playlist?.id ?: return
        val snapshot = favoriteOrder.toList()
        val app = application as OlympusIptvApp
        app.appScope.launch {
            val base = app.playlistStore.favoritesRevision(savedId)
            runCatching { app.playlistStore.replaceFavorites(savedId, snapshot) }
            pushFavorites(app, savedId, base)
        }
        val channels = Favorites.ordered(snapshot, idIndex(section), favoriteIds)
        publishChannels(channels, scrollTop = false)
        val index = channels.indexOfFirst { it.id == channel.id }
        if (index < 0) return
        binding.channelList.scrollToPosition(index)
        binding.channelList.post {
            binding.channelList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }


internal fun BrowseActivity.ensureGroupCountries() {
        if (groupCountriesReady || groupCountriesQueued) return
        val id = playlist?.id ?: return
        groupCountriesQueued = true
        binding.categoryList.post {
            if (isDestroyed || groupCountriesReady) return@post
            groupCountriesReady = true
            val app = application as OlympusIptvApp
            lifecycleScope.launch {
                val map = withContext(Dispatchers.IO) {
                    app.catalogDb.liveCountryByCategory(id)
                }
                if (isDestroyed) return@launch
                groupCountries = map
                groupCountriesLoaded = true
                groupedSection = null
                if (binding.groupColumn.visibility == View.VISIBLE && loaded != null) {
                    render(restoreFocus = false, reloadChannels = false)
                }
            }
        }
    }

internal fun BrowseActivity.visibleGroups(categories: List<Category>, config: LineupConfig): List<Category> {
        ensureGroupCountries()
        if (groupedSection == section &&
            groupedCategories === categories &&
            groupedConfig === config &&
            groupedCountries === groupCountries
        ) {
            return groupedResult
        }
        val groups = LineupApplier.groups(categories, config, groupCountries, groupCountriesLoaded)
        groupedSection = section
        groupedCategories = categories
        groupedConfig = config
        groupedCountries = groupCountries
        groupedResult = groups
        return groups
    }

internal fun BrowseActivity.withoutEmptyGroups(categories: List<Category>): List<Category> {
        if (!hideEmptyGroups || groupCounts.isEmpty()) return categories
        return categories.filter { category ->
            category.id == PlaylistLoader.ALL_ID ||
                category.id == PlaylistLoader.FAVORITES_ID ||
                category.id == PlaylistLoader.RECENT_ID ||
                category.id == PlaylistLoader.FOR_YOU_ID ||
                category.id == PlaylistLoader.CONTINUE_ID ||
                category.id == PlaylistLoader.TOP_WATCHED_ID ||
                (groupCounts[category.id] ?: 0) > 0
        }
    }

internal fun BrowseActivity.prepareGroup(channels: List<Channel>, config: LineupConfig): List<Channel> {
        val hidden = config.hiddenChannels.orEmpty()
        val names = config.channelNames.orEmpty()
        val prefixes = config.namePrefixes.orEmpty()
        val suffixes = config.nameSuffixes.orEmpty()
        if (hidden.isEmpty() && names.isEmpty() && prefixes.isEmpty() && suffixes.isEmpty()) return channels
        val hiddenSet = hidden.toSet()
        val out = ArrayList<Channel>(channels.size)
        for (channel in channels) {
            if (channel.id in hiddenSet) continue
            val name = ChannelNames.present(channel, config)
            out.add(if (name == channel.name) channel else channel.copy(name = name))
        }
        return out
    }

internal fun BrowseActivity.selectCategory(category: Category, immediate: Boolean) {
        categoryAdapter.setSelected(category.id)
        selectedCategoryName = category.name
        styleGuideHeader()
        if (binding.groupPills.visibility == View.VISIBLE) syncGroupPills()
        categoryLoad?.let { focusHandler.removeCallbacks(it) }
        categoryLoad = null
        if (!immediate) return
        if (GroupLock.locked(activeConfig, category.id)) {
            showGroupPin(category, open = true)
            return
        }
        selectedCategoryId = category.id
        if (category.id != PlaylistLoader.FAVORITES_ID &&
            category.id != PlaylistLoader.CONTINUE_ID &&
            category.id != PlaylistLoader.RECENT_ID &&
            category.id != PlaylistLoader.FOR_YOU_ID &&
            category.id != PlaylistLoader.TOP_WATCHED_ID &&
            category.id != PlaylistLoader.ALL_ID
        ) {
            lastGroupId = category.id
            lastGroupName = category.name
            paintLastGroupChip()
        }
        showChannels()
        openFullGuide()
    }


internal fun BrowseActivity.scheduleEpisodePrefetch() {
        if (section != GuideSection.SHOWS) return
        binding.channelList.post {
            if (section != GuideSection.SHOWS) return@post
            val layout = binding.channelList.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
                ?: return@post
            val first = layout.findFirstVisibleItemPosition()
            val last = layout.findLastVisibleItemPosition()
            if (first < 0 || last < first) return@post
            val end = (last + layout.spanCount).coerceAtMost(visibleChannels.lastIndex)
            val ids = ArrayList<String>(end - first + 1)
            for (index in first..end) {
                val channel = visibleChannels.getOrNull(index) ?: continue
                if (channel.streamUrl.isNotBlank()) continue
                val seriesId = channel.seriesId?.takeIf { it.isNotBlank() } ?: continue
                ids.add(seriesId)
            }
            prefetchEpisodeLists(ids.distinct())
        }
    }

internal fun BrowseActivity.prefetchEpisodeLists(seriesIds: List<String>) {
        val saved = playlist ?: return
        if (seriesIds.isEmpty()) return
        if (seriesIds == episodePrefetchIds && episodePrefetchJob?.isActive == true) return
        episodePrefetchJob?.cancel()
        episodePrefetchIds = seriesIds
        episodePrefetchJob = lifecycleScope.launch {
            delay(220)
            if (!isActive || section != GuideSection.SHOWS || playlist?.id != saved.id) return@launch
            val pending = withContext(Dispatchers.IO) {
                seriesIds.filter { !EpisodeCache.contains(filesDir, saved.id, it) }
            }
            if (pending.isEmpty() || !isActive) return@launch
            pending.chunked(2).forEach { batch ->
                if (!isActive || section != GuideSection.SHOWS || playlist?.id != saved.id) return@launch
                coroutineScope {
                    batch.map { seriesId ->
                        async(Dispatchers.IO) {
                            if (EpisodeCache.contains(filesDir, saved.id, seriesId)) return@async
                            val fresh = EpisodeSequence.fetch(saved, seriesId)
                            if (fresh.isNotEmpty()) EpisodeCache.store(filesDir, saved.id, seriesId, fresh)
                        }
                    }.awaitAll()
                }
            }
        }
    }

internal fun BrowseActivity.warmFavoriteAndRecentEpg() {
        val saved = playlist ?: return
        lifecycleScope.launch {
            val channels = withContext(Dispatchers.IO) {
                val db = (application as OlympusIptvApp).catalogDb
                val out = ArrayList<Channel>(24)
                val seen = HashSet<String>()
                for (key in favoriteIds) {
                    val id = when {
                        key.startsWith("live:") -> key.removePrefix("live:")
                        ':' in key -> continue
                        else -> key
                    }
                    if (!seen.add(id)) continue
                    val row = db.readChannel(saved.id, CatalogDb.LIVE, id) ?: continue
                    if (row.kind == ContentKind.LIVE && row.seriesId.isNullOrBlank()) out.add(row)
                    if (out.size >= 12) break
                }
                for (entry in recentEntries) {
                    if (entrySection(entry) != "live") continue
                    val id = entry.channelId ?: continue
                    if (!seen.add(id)) continue
                    val row = db.readChannel(saved.id, CatalogDb.LIVE, id) ?: continue
                    if (row.kind == ContentKind.LIVE && row.seriesId.isNullOrBlank()) out.add(row)
                    if (out.size >= 20) break
                }
                out
            }
            if (channels.isEmpty() || isDestroyed) return@launch
            prefetchEpg(channels)
        }
    }

internal fun BrowseActivity.schedulePrefetch(channels: List<Channel>) {
        epgPrefetchWait?.let { focusHandler.removeCallbacks(it) }
        val limit = if (touchPrimary) 3 else 8
        val snapshot = channels.filter { it.kind == ContentKind.LIVE }.take(limit)
        if (snapshot.isEmpty()) return
        val query = channelQuery
        val run = Runnable {
            epgPrefetchWait = null
            if (query != channelQuery) return@Runnable
            prefetchEpg(snapshot)
        }
        epgPrefetchWait = run
        focusHandler.postDelayed(run, if (touchPrimary) 220L else FOCUS_SETTLE_MS)
    }

internal fun BrowseActivity.glowFrom(url: String?) {
        if (url.isNullOrBlank()) return
        val request = coil.request.ImageRequest.Builder(this)
            .data(url)
            .size(com.olympus.iptv.ui.BackdropGlow.THUMB_W, com.olympus.iptv.ui.BackdropGlow.THUMB_H)
            .target { drawable ->
                val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@target
                com.olympus.iptv.ui.BackdropGlow.apply(bitmap, binding.guideShell)
            }
            .build()
        coil.Coil.imageLoader(this).enqueue(request)
    }

internal fun BrowseActivity.showChannelDetail(channel: Channel) {
        focusedChannel = channel
        queuePreview(channel)
        epgWait?.let { focusHandler.removeCallbacks(it) }
        epgWait = null
        if (channel.kind != ContentKind.LIVE) {
            loadTitleLine(channel)
            glowFrom(channel.logoUrl)
            return
        }
        if (epgCache.containsKey(channel.id)) {
            channelAdapter.notifyEpgChanged(channel.id)
            return
        }
        val run = Runnable {
            epgWait = null
            if (focusedChannel?.id != channel.id || epgCache.containsKey(channel.id)) return@Runnable
            detailJob?.cancel()
            val saved = playlist ?: return@Runnable
            detailJob = lifecycleScope.launch {
                val nowNext = runCatching { epgRepo.nowNext(saved, channel) }.getOrDefault(EpgNowNext())
                if (focusedChannel?.id != channel.id) return@launch
                rememberEpg(channel.id, nowNext)
                channelAdapter.notifyEpgChanged(channel.id)
                queuePreview(channel)
            }
        }
        epgWait = run
        focusHandler.postDelayed(run, FOCUS_SETTLE_MS)
    }

internal fun BrowseActivity.loadTitleLine(channel: Channel) {
        val saved = playlist ?: return
        detailJob?.cancel()
        val run = object : Runnable {
            override fun run() {
                if (focusedChannel?.id != channel.id) return
                // Wait until the list is idle so rapid scrolling doesn't spam TMDB.
                if (channelAdapter.isScrolling()) {
                    focusHandler.postDelayed(this, FOCUS_SETTLE_MS)
                    return
                }
                detailJob = lifecycleScope.launch {
                    val series = section == GuideSection.SHOWS || !channel.seriesId.isNullOrBlank()
                    val id = channel.seriesId?.takeIf { it.isNotBlank() } ?: channel.id
                    val xtream = withContext(Dispatchers.IO) {
                        runCatching { XtreamClient().titleLine(saved, series, id) }.getOrDefault("")
                    }
                    if (focusedChannel?.id != channel.id) return@launch
                    val cached = TitleRatings.peek(channel.name, series)
                    if (cached != null) {
                        val line = listOf(xtream, cached.scoreLine(2)).filter { it.isNotBlank() }.joinToString(" · ")
                        if (line.isNotBlank()) channelAdapter.setDetail(channel.id, line)
                        return@launch
                    }
                    if (channelAdapter.isScrolling() || focusedChannel?.id != channel.id) {
                        if (xtream.isNotBlank()) channelAdapter.setDetail(channel.id, xtream)
                        return@launch
                    }
                    val scores = withContext(Dispatchers.IO) {
                        TitleRatings.loadScores(channel.name, preferTv = series)
                    }
                    if (focusedChannel?.id != channel.id) return@launch
                    val line = listOf(xtream, scores.scoreLine(2)).filter { it.isNotBlank() }.joinToString(" · ")
                    if (line.isNotBlank()) channelAdapter.setDetail(channel.id, line)
                }
            }
        }
        focusHandler.postDelayed(run, if (touchPrimary) 280L else FOCUS_SETTLE_MS)
    }

internal fun BrowseActivity.openVodRatings(channel: Channel) {
        VodDetailSheet.showLoading(this)
        lifecycleScope.launch {
            val preferTv = section == GuideSection.SHOWS || !channel.seriesId.isNullOrBlank()
            val limit = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.tmdbItemsLimit()
            }
            val bundle = withContext(Dispatchers.IO) {
                TitleRatings.loadFull(channel.name, preferTv = preferTv, tmdbLimit = limit)
            }
            if (isDestroyed) return@launch
            VodDetailSheet.show(
                context = screen(),
                bundle = bundle,
                onTrailer = {
                    openYoutubeTrailer(channel.name, preferTv)
                },
                onPlay = { play(channel) }
            )
        }
    }

internal fun BrowseActivity.pushFavorites(app: OlympusIptvApp, playlistId: String, baseRevision: Long) {
        app.appScope.launch {
            val email = app.playlistStore.session.first()?.email?.trim().orEmpty()
            if (email.isBlank()) return@launch
            val ids = app.playlistStore.favorites(playlistId)
            val revision = app.playlistStore.favoritesRevision(playlistId)
            LineupClient(app).pushFavorites(email, app.playlistStore.deviceId(), ids, revision, baseRevision)
        }
    }

internal fun BrowseActivity.restoreAfterPlayer() {
        val channelReturn = PlayerActivity.takeChannelReturn()
        if (section == GuideSection.TV) {
            val menu = when {
                touchPrimary -> GuideMenu.CHANNELS
                channelReturn -> GuideMenu.CHANNELS
                returnOnProgram -> GuideMenu.PROGRAMS
                returnMenu == GuideMenu.CHANNELS || returnMenu == GuideMenu.PROGRAMS -> returnMenu
                else -> GuideMenu.PROGRAMS
            }
            if (guideMenu != menu) showMenu(menu)
            val row = visibleChannels.indexOfFirst { it.id == returnChannelId }.let { if (it < 0) 0 else it }
            if (menu == GuideMenu.PROGRAMS && !touchPrimary) focusCurrentProgram(row) else focusReturnChannel(row)
            return
        }
        if (guideMenu != GuideMenu.CHANNELS) showMenu(GuideMenu.CHANNELS)
        val row = visibleChannels.indexOfFirst { it.id == returnChannelId }
        focusReturnChannel(row)
    }

    /** Keep the last live stream in the corner preview after leaving full-screen playback. */

internal fun BrowseActivity.focusReturnChannel(row: Int) {
        if (row < 0) {
            focusChannelPane()
            return
        }
        val layout = binding.channelList.layoutManager as? LinearLayoutManager
        if (layout != null) {
            layout.scrollToPositionWithOffset(row, (12 * resources.displayMetrics.density).toInt())
        } else {
            binding.channelList.scrollToPosition(row)
        }
        binding.channelList.post {
            if (isDestroyed) return@post
            focusRowAt(binding.channelList, row, R.id.playTarget)
            binding.channelList.post {
                if (!isDestroyed) focusRowAt(binding.channelList, row, R.id.playTarget)
            }
        }
    }

internal fun BrowseActivity.playContinue(channel: Channel) {
        val saved = playlist ?: return
        val seriesId = channel.seriesId ?: return
        if (openingPlayer) return
        openingPlayer = true
        val episodeId = channel.streamUrl.takeIf { it.isNotBlank() }?.let { channel.id }
            ?: recentEntries.firstOrNull { it.seriesId == seriesId && it.canResume() }?.channelId
        lifecycleScope.launch {
            val episodes = EpisodeSequence.load(filesDir, saved, seriesId, (application as OlympusIptvApp).appScope)
            val ordered = EpisodeSequence.ordered(episodes)
            val episode = ordered.firstOrNull { it.id == episodeId } ?: ordered.firstOrNull()
            if (episode == null || episode.streamUrl.isBlank()) {
                openingPlayer = false
                startActivity(EpisodesActivity.intent(screen(), saved.id, channel))
                return@launch
            }
            SeriesPlay.open(screen(), saved.id, ordered, episode)
        }
    }

internal fun BrowseActivity.playCatchup(channel: Channel, program: EpgProgram) {
        val saved = playlist ?: return
        if (openingPlayer) return
        lifecycleScope.launch {
            val hydrated = withContext(Dispatchers.IO) {
                val db = (application as OlympusIptvApp).catalogDb
                db.readChannel(saved.id, CatalogDb.LIVE, channel.id) ?: channel
            }
            val url = CatchupUrls.url(saved, hydrated, program)
            if (url.isNullOrBlank()) {
                Toast.makeText(screen(), R.string.catchup_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            returnMenu = guideMenu
            returnChannelId = channel.id
            returnOnProgram = true
            stopPreview()
            val playback = channel.copy(
                streamUrl = url,
                kind = ContentKind.VOD,
                name = program.title.ifBlank { channel.name }
            )
            val app = application as OlympusIptvApp
            app.playbackQueue = listOf(playback)
            openingPlayer = true
            startActivity(
                PlayerActivity.intent(
                    screen(),
                    channel = playback,
                    channelMeta = getString(R.string.catchup_meta, channel.name),
                    channelNext = program.timeRangeLabel(),
                    startIndex = 0,
                    playlistId = saved.id,
                    zapMode = PlayerActivity.ZAP_LIST,
                    categoryId = selectedCategoryId
                )
            )
        }
    }

internal fun BrowseActivity.play(
        channel: Channel,
        queueOverride: List<Channel>? = null,
        zapMode: String? = null,
        rememberReturn: Boolean = true,
        enterPip: Boolean = false
    ) {
        if (rememberReturn) {
            returnMenu = guideMenu
            returnChannelId = channel.id
            returnOnProgram = section == GuideSection.TV &&
                (guideMenu == GuideMenu.PROGRAMS || focusIsIn(binding.programPane))
            rememberGroupScroll()
            selectedCategoryId?.let { groupScrollOffset[it] =
                visibleChannels.indexOfFirst { row -> row.id == channel.id }.coerceAtLeast(0)
            }
        }
        if (selectedCategoryId == PlaylistLoader.CONTINUE_ID && !channel.seriesId.isNullOrBlank()) {
            stopPreview()
            playContinue(channel)
            return
        }
        if (!channel.seriesId.isNullOrBlank() && channel.streamUrl.isBlank()) {
            stopPreview()
            val saved = playlist ?: return
            startActivity(EpisodesActivity.intent(this, saved.id, channel))
            return
        }
        handOffPreview(channel)
        val mode = zapMode ?: when (selectedCategoryId) {
            PlaylistLoader.ALL_ID -> PlayerActivity.ZAP_ALL
            PlaylistLoader.FAVORITES_ID,
            PlaylistLoader.TOP_WATCHED_ID,
            PlaylistLoader.RECENT_ID,
            PlaylistLoader.CONTINUE_ID -> PlayerActivity.ZAP_LIST
            else -> PlayerActivity.ZAP_GROUP
        }
        val sourceQueue = queueOverride ?: visibleChannels
        if (sourceQueue.isEmpty() && mode == PlayerActivity.ZAP_LIST) return
        if (openingPlayer) return
        epgPrefetchJob?.cancel()
        detailJob?.cancel()
        val playback = if (mode == PlayerActivity.ZAP_LIST) sourceQueue else listOf(channel)
        val keepAddresses = playback.any { it.streamUrl.isNotBlank() }
        val epg = epgCache[channel.id]
        val app = application as OlympusIptvApp
        app.playbackQueue = if (keepAddresses) playback else playback.map { it.copy(streamUrl = "") }
        val startIndex = app.playbackQueue.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        openingPlayer = true
        startActivity(
            PlayerActivity.intent(
                this,
                channel = channel,
                channelMeta = epg?.nowLine() ?: channel.groupTitle.orEmpty(),
                channelNext = epg?.nextLine().orEmpty(),
                startIndex = startIndex,
                playlistId = playlist?.id,
                zapMode = mode,
                categoryId = selectedCategoryId,
                enterPip = enterPip && touchPrimary
            )
        )
    }

internal fun BrowseActivity.showLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.categoryList.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.channelList.visibility = if (loading) View.INVISIBLE else View.VISIBLE
    }

internal fun BrowseActivity.pageSizeFor(categoryId: String?): Int {
        val count = groupCounts[categoryId] ?: 0
        return when {
            count >= LARGE_LIVE_GROUP -> PAGE_SIZE_HUGE
            touchPrimary -> PAGE_SIZE_PHONE
            else -> PAGE_SIZE_TV
        }
    }

internal fun BrowseActivity.pagePrefetchRemain(categoryId: String?): Int {
        val count = groupCounts[categoryId] ?: 0
        return if (count >= LARGE_LIVE_GROUP) HUGE_PREFETCH_REMAIN else DEFAULT_PREFETCH_REMAIN
    }
