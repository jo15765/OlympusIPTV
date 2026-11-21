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

internal fun BrowseActivity.refreshUnhealthy() {
        val savedId = playlist?.id ?: return
        lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).catalogDb.deadChannelIds(savedId)
            }
            if (isDestroyed) return@launch
            unhealthyIds.clear()
            unhealthyIds.addAll(ids)
            if (channelAdapter.itemCount > 0) {
                channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount)
            }
        }
    }

internal fun BrowseActivity.rememberEpg(channelId: String, epg: EpgNowNext) {
        epgCache[channelId] = epg
        if (section == GuideSection.TV && groupSampleIds.values.any { channelId in it }) {
            categoryAdapter.refreshDetails()
        }
        if (epgCache.size <= EPG_CACHE_CAP) return
        val drop = epgCache.size - (EPG_CACHE_CAP * 3 / 4)
        val keys = epgCache.keys.take(drop)
        for (key in keys) epgCache.remove(key)
    }



    /** Wires listeners and replays state that arrived while a pane was still a ViewStub. */

internal fun BrowseActivity.wireLazyPanes() {
        panes.onAdminInflated = { admin ->
            if (touchPrimary) {
                listOf(
                    admin.cardAdminRefresh, admin.cardAdminWeb, admin.cardAdminEpg, admin.cardAdminVpn,
                    admin.cardAdminAbout, admin.cardAdminFavorites, admin.cardAdminSetup
                ).forEach { card -> card.isFocusableInTouchMode = false }
            }
            admin.cardAdminRefresh.setOnClickListener {
                if (playlist == null) return@setOnClickListener
                showRefreshPane()
            }
            admin.cardAdminWeb.setOnClickListener { showDevicePane() }
            admin.cardAdminEpg.setOnClickListener {
                if (source == null) return@setOnClickListener
                openEpgEdits()
            }
            admin.cardAdminVpn.setOnClickListener { showVpnPane() }
            admin.cardAdminAbout.setOnClickListener { showAboutPane() }
            admin.cardAdminFavorites.setOnClickListener { openFavoritesSetup() }
            admin.cardAdminSetup.setOnClickListener { showAdminTools() }
            admin.adminToolsBack.setOnClickListener { showAdminPane(admin.cardAdminSetup) }
            admin.btnMagicCleanup.setOnClickListener { runMagicCleanup() }
            paintProviderHealth()
            admin.adminQuietToggle.setOnClickListener { toggleAdminQuietHours() }
            admin.adminQuietSchedule.setOnClickListener { cycleAdminQuietSchedule() }
            admin.adminBackupLink.setOnClickListener { openBackupShortcut() }
            pendingVpnDetail?.let { admin.cardAdminVpnDetail.text = it }
        }
        panes.onDeviceInflated = { device ->
            device.deviceBack.setOnClickListener { showAdminPane(panes.cardAdminWeb) }
            device.deviceRemovePlaylist.setOnClickListener { confirmRemovePlaylist() }
            device.deviceAutoResume.setOnClickListener { toggleAutoResume() }
            paintAutoResume()
            pendingDeviceId?.let { device.deviceIdValue.text = it }
            dashboardUrlText?.let {
                device.deviceUrlValue.text = it
                device.deviceUrlValue.setTextColor(getColor(R.color.accent))
            }
        }
        panes.onVpnInflated = { vpn ->
            vpnUi = VpnUi(this, vpn) { vpnConsent.launch(it) }
            vpn.vpnBack.setOnClickListener { showAdminPane(panes.cardAdminVpn) }
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) vpnUi.start()
        }
        panes.onRefreshInflated = { refresh ->
            if (touchPrimary) {
                refresh.refreshNowCard.isFocusableInTouchMode = false
                refresh.refreshIntervalCard.isFocusableInTouchMode = false
            }
            refresh.refreshBack.setOnClickListener { showAdminPane(panes.cardAdminRefresh) }
            refresh.refreshNowCard.setOnClickListener { refreshPlaylist(quiet = true) }
            refresh.refreshIntervalCard.setOnClickListener { cycleRefreshInterval() }
            refresh.refreshIntervalValue.text = getString(R.string.refresh_every, intervalLabel(refreshMinutes))
            pendingRefreshStatus?.let { refresh.refreshStatus.text = it }
        }
    }


internal fun BrowseActivity.hostOnCreate(savedInstanceState: Bundle?) {
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this) &&
            requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        try {
        programAdapter = ProgramGuideAdapter(
            context = this,
            onPlay = { channel, program -> playFromGuide(channel, program) },
            onScroll = { scrollProgramGuide(it) },
            onFocus = { channel, program ->
                showChannelDetail(channel)
                paintProgramSynopsis(channel, program)
            },
            onHold = { channel, _ -> showRowOptions(channel) }
        )
        binding = ActivityBrowseBinding.inflate(layoutInflater)
        panes = BrowseLazyPanes(binding)
        wireLazyPanes()
        setContentView(binding.root)
        binding.root.post {
            com.olympus.iptv.ui.RemoteTip.show(this, getString(R.string.remote_tip_browse), binding.root as? android.view.ViewGroup)
        }
        binding.root.post {
            reportFullyDrawn()
            (application as OlympusIptvApp).warmAfterFirstFrame()
        }
        browseToolHost = BrowseToolHost(this, binding.toolOverlay)
        supportFragmentManager.setFragmentResultListener(EpgEditsFragment.RESULT_KEY, this) { _, _ ->
            showBlank()
            applySavedLineup()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.findFragmentById(R.id.toolHost) == null) {
                binding.toolOverlay.visibility = View.GONE
            }
        }
        touchPrimary = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)
        if (touchPrimary) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.guideShell,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
            setupMobileChrome()
            enlargeTouchChips()
            categoryAdapter.phoneListMode = true
        }
        wireHomeContinue()
        paintQueueChip()
        previewCtl = BrowsePreviewController(
            activity = this,
            binding = binding,
            touchPrimary = touchPrimary,
            epgNowTitle = { epgCache[it]?.now?.title },
            focusAllowsPreview = {
                focusIsIn(binding.channelList) || focusIsIn(binding.programPane)
            },
            focusedChannelId = { focusedChannel?.id }
        )
        epgCtl = BrowseEpgController(
            activity = this,
            binding = binding,
            programAdapter = programAdapter,
            halfHourMs = halfHourMs,
            epgCache = { epgCache }
        )

        val categorySpan = com.olympus.iptv.ui.DeviceForm.contentColumns(this)
        binding.categoryList.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, categorySpan)
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter
        binding.channelList.adapter = channelAdapter
        binding.programList.layoutManager = LinearLayoutManager(this)
        binding.programList.adapter = programAdapter
        binding.programList.setHasFixedSize(true)
        binding.programList.recycledViewPool.setMaxRecycledViews(ProgramGuideAdapter.VIEW_PROGRAM, if (touchPrimary) 12 else 24)
        binding.programList.itemAnimator = null
        binding.categoryList.itemAnimator = null
        binding.channelList.itemAnimator = null
        binding.categoryList.setHasFixedSize(true)
        binding.channelList.setHasFixedSize(true)
        binding.categoryList.setItemViewCacheSize(if (touchPrimary) 8 else 12)
        binding.channelList.setItemViewCacheSize(if (touchPrimary) 12 else 24)
        (binding.categoryList.layoutManager as androidx.recyclerview.widget.GridLayoutManager)
            .initialPrefetchItemCount = categorySpan * 2
        (binding.channelList.layoutManager as LinearLayoutManager).initialPrefetchItemCount =
            if (touchPrimary) 4 else 8
        binding.channelList.recycledViewPool.setMaxRecycledViews(0, if (touchPrimary) 16 else 32)
        binding.channelList.recycledViewPool.setMaxRecycledViews(1, if (touchPrimary) 8 else 16)
        setupPipSwipeOpen()
        lifecycleScope.launch {
            pipGestures = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.pipSwipeHome()
            }
        }
        binding.channelList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                val idle = newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
                channelAdapter.setScrolling(!idle)
                if (idle) {
                    alignGuide(recyclerView, binding.programList)
                    val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val last = layout.findLastVisibleItemPosition()
                    channelAdapter.refreshLogos(layout.findFirstVisibleItemPosition(), last)
                    val prefetchCount = if (touchPrimary) 4 else 12
                    channelAdapter.prefetch(recyclerView.context, last + 1, prefetchCount, 42)
                    if (!touchPrimary) scheduleEpisodePrefetch()
                    loadVisibleGuidePrograms()
                }
            }

            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) scheduleGuideAlign(recyclerView, binding.programList)
                if (dy <= 0 || selectedCategoryId == null || selectedCategoryId == PlaylistLoader.FAVORITES_ID) return
                maybePrefetchGroupPage(recyclerView)
            }
        })
        binding.programList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    alignGuide(recyclerView, binding.channelList)
                    loadVisibleGuidePrograms()
                }
            }

            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) scheduleGuideAlign(recyclerView, binding.channelList)
            }
        })
        focusHandler.postDelayed(object : Runnable {
            override fun run() {
                if (binding.programPane.visibility == View.VISIBLE) {
                    positionNowLine(programAdapter.scrollX)
                    if (binding.guideDate.visibility == View.VISIBLE) tickGuideHeader()
                }
                focusHandler.postDelayed(this, 30_000)
            }
        }, 30_000)

        binding.tabLive.setOnClickListener { openSection(GuideSection.TV) }
        binding.tabVod.setOnClickListener { openSection(GuideSection.MOVIES) }
        binding.tabShows.setOnClickListener { openSection(GuideSection.SHOWS) }
        binding.btnQueue.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(LibraryActivity.intent(this, saved.id, tab = 0))
        }
        binding.btnFresh.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(LibraryActivity.intent(this, saved.id, tab = 1))
        }
        binding.btnLibrary.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(LibraryActivity.intent(this, saved.id))
        }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnRecord.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(RecordActivity.intent(this, saved.id))
        }
        binding.btnRemind.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(LibraryActivity.intent(this, saved.id, tab = 2))
        }
        binding.guideNow.setOnClickListener { jumpGuideToNow() }
        binding.btnParental.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            showTool(ParentalFragment.newInstance(saved.id))
        }
        binding.btnAdmin.setOnClickListener {
            if (panes.isAdminVisible()) showBlank() else showAdminPane()
        }
        binding.btnLastGroup.setOnClickListener { openLastGroup() }
        binding.btnAbout.setOnClickListener {
            if (panes.isAboutVisible()) showBlank() else showAboutPane()
        }
        binding.btnFavoritesSetup.setOnClickListener { openFavoritesSetup() }
        binding.tabLive.isSelected = true
        binding.navRail.post { tightenNavRail() }
        showLoading(true)

        lifecycleScope.launch {
            val account = resolveAccount() ?: return@launch
            bootstrap(account)
        }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            com.olympus.iptv.AppLog.error("Guide", "The guide failed to open", error)
            val message = android.widget.TextView(this)
            message.setTextColor(getColor(R.color.text_primary))
            message.text = getString(R.string.favorites_setup_guide_failed)
            message.setPadding(48, 48, 48, 48)
            setContentView(message)
        }
    }

internal suspend fun BrowseActivity.resolveAccount(): SavedPlaylist? {
        val app = application as OlympusIptvApp
        val (saved, favoritesReady) = withContext(Dispatchers.IO) { app.playlistStore.opening() }
        if (saved == null || saved.email.isNullOrBlank()) {
            startActivity(Intent(this, com.olympus.iptv.ui.login.LoginActivity::class.java))
            finish()
            overridePendingTransition(0, 0)
            return null
        }
        val ready = favoritesReady ?: withContext(Dispatchers.IO) {
            val already = app.catalogDb.hasLibrary(saved.id)
            if (already) app.playlistStore.setFavoritesSetupDone(true)
            already
        }
        if (!ready) {
            startActivity(
                Intent(this, com.olympus.iptv.ui.login.FavoritesSetupActivity::class.java)
                    .putExtra(com.olympus.iptv.ui.login.FavoritesSetupActivity.EXTRA_PLAYLIST_ID, saved.id)
            )
            finish()
            overridePendingTransition(0, 0)
            return null
        }
        return saved
    }

internal suspend fun BrowseActivity.bootstrap(savedIn: SavedPlaylist) {
        val app = application as OlympusIptvApp
        val store = app.playlistStore
        var saved = savedIn
        try {
        if (!saved.includeVod) {
            saved = saved.copy(includeVod = true)
            val fixed = saved
            lifecycleScope.launch(Dispatchers.IO) { store.saveSession(fixed) }
        }
        playlist = saved
        tuneFromLauncher(saved.id)
        maybeAutoResume(saved.id)
        groupCountriesReady = false
        groupCountriesQueued = false
        groupCountriesLoaded = false
        groupCountries = emptyMap()
        binding.tabVod.visibility = View.VISIBLE
        binding.tabShows.visibility = View.VISIBLE
        section = GuideSection.TV
        kind = ContentKind.LIVE
        selectedCategoryId = PlaylistLoader.FAVORITES_ID
        showGuide()
        binding.tabLive.isSelected = true
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        armGroupFocus()
        val db = app.catalogDb
        coroutineScope {
            val favoritesJob = async(Dispatchers.IO) { store.favorites(saved.id) }
            val deviceJob = async(Dispatchers.IO) { store.deviceId() }
            val lineupJob = async(Dispatchers.IO) { store.lineup(saved.id) }
            val lastSectionJob = async(Dispatchers.IO) { store.lastGuideSection() }
            val hideEmptyJob = async(Dispatchers.IO) {
                store.hideEmptyGroups().also { hideEmptyGroups = it }
            }
            val densityJob = async(Dispatchers.IO) {
                store.guideDensity().also { guideDensity = it }
            }
            val libraryJob = async(Dispatchers.IO) { db.hasLibrary(saved.id) }
            val liveJob = async(Dispatchers.IO) { db.readCategoriesOnly(saved.id, CatalogDb.LIVE) }
            launch {
                val deviceId = deviceJob.await()
                if (isDestroyed) return@launch
                pendingDeviceId = deviceId
                panes.deviceOrNull?.deviceIdValue?.text = deviceId
            }
            val liveCategories = liveJob.await()
            val catalogJob = app.catalogRefresh
            if (liveCategories.isNotEmpty()) {
                moviesPending = true
                showsPending = true
                loadingFromDb = true
                showCatalog(
                    LoadedPlaylist(liveCategories = liveCategories, liveChannels = emptyList()),
                    LineupConfig(),
                    openGuide = true
                )
                showLoading(false)
                val local = lineupJob.await()
                applyFavorites(favoritesJob.await())
                activeConfig = local
                if (binding.groupColumn.visibility == View.VISIBLE) render(restoreFocus = landOnGroups)
                loadSavedBuckets(saved.id, db)
                if (catalogJob?.isActive == true) {
                    launch {
                        catalogJob.join()
                        if (!isDestroyed) loadSavedBuckets(saved.id, db)
                    }
                }
            } else {
                val hasSaved = libraryJob.await()
                if (!hasSaved && catalogJob?.isActive == true) {
                    val local = lineupJob.await()
                    applyFavorites(favoritesJob.await())
                    showLoading(true)
                    app.catalogLiveReady?.await()
                    showLoading(false)
                    if (withContext(Dispatchers.IO) { db.hasLibrary(saved.id) }) {
                        val categories = withContext(Dispatchers.IO) {
                            db.readCategoriesOnly(saved.id, CatalogDb.LIVE)
                        }
                        showCatalog(
                            LoadedPlaylist(liveCategories = categories, liveChannels = emptyList()),
                            local,
                            openGuide = true
                        )
                        loadSavedBuckets(saved.id, db)
                        launch {
                            catalogJob.join()
                            if (!isDestroyed) loadSavedBuckets(saved.id, db)
                        }
                    } else {
                        moviesPending = true
                        showsPending = true
                        refreshPlaylist()
                    }
                } else if (!hasSaved) {
                    val local = lineupJob.await()
                    applyFavorites(favoritesJob.await())
                    moviesPending = true
                    showsPending = true
                    showLoading(true)
                    refreshPlaylist()
                } else {
                    moviesPending = true
                    showsPending = true
                    loadingFromDb = true
                    showCatalog(
                        LoadedPlaylist(liveCategories = emptyList(), liveChannels = emptyList()),
                        LineupConfig(),
                        openGuide = true
                    )
                    showLoading(false)
                    val local = lineupJob.await()
                    applyFavorites(favoritesJob.await())
                    activeConfig = local
                    if (binding.groupColumn.visibility == View.VISIBLE) render(restoreFocus = landOnGroups)
                    loadSavedBuckets(saved.id, db)
                    if (catalogJob?.isActive == true) {
                        launch {
                            catalogJob.join()
                            if (!isDestroyed) loadSavedBuckets(saved.id, db)
                        }
                    }
                }
            }
            val lastKey = lastSectionJob.await()
            if (!isDestroyed && !intent.getBooleanExtra(BrowseActivity.EXTRA_SKIP_RESUME, false)) {
                when (GuideFeel.sectionFromKey(lastKey)) {
                    "vod" -> setSection(GuideSection.MOVIES)
                    "series" -> setSection(GuideSection.SHOWS)
                }
            }
        }
        ensureRefreshLoop()
        showDashboardAddress()
        syncSharedLineup()
        runCatching { RecordingService.start(this) }
            .onFailure { com.olympus.iptv.AppLog.error("Guide", "Recording service did not start", it) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            com.olympus.iptv.AppLog.error("Guide", "The guide failed to open", error)
            if (!isDestroyed) {
                android.widget.Toast.makeText(this, R.string.favorites_setup_guide_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

internal fun BrowseActivity.syncSharedLineup() {
        val saved = playlist ?: return
        val email = saved.email?.trim().orEmpty()
        if (email.isBlank()) return
        val app = application as OlympusIptvApp
        app.appScope.launch {
            val client = LineupClient(screen())
            val deviceId: String
            val local: LineupConfig
            coroutineScope {
                val deviceJob = async { app.playlistStore.deviceId() }
                val lineupJob = async { app.playlistStore.lineup(saved.id) }
                deviceId = deviceJob.await()
                local = lineupJob.await()
            }
            val merged = runCatching { client.reconcile(email, deviceId, local) }.getOrNull()
            if (merged == null) {
                publishSharedCatalog(app, client, saved.id, email, deviceId)
                return@launch
            }
            if (merged.revision != local.revision) app.playlistStore.saveLineup(saved.id, merged)
            val prefixes = merged.namePrefixes.orEmpty()
            val suffixes = merged.nameSuffixes.orEmpty()
            val synced = coroutineScope {
                val namesJob = async {
                    if ((prefixes.isNotEmpty() || suffixes.isNotEmpty()) &&
                        (prefixes != local.namePrefixes.orEmpty() || suffixes != local.nameSuffixes.orEmpty())
                    ) {
                        runCatching { app.catalogDb.rewriteNames(saved.id, prefixes, suffixes) }
                    }
                }
                val favoritesJob = async {
                    val localFavorites = app.playlistStore.favorites(saved.id)
                    val localRevision = app.playlistStore.favoritesRevision(saved.id)
                    localFavorites to localRevision to runCatching {
                        client.syncFavorites(email, deviceId, localFavorites, localRevision)
                    }.getOrNull()
                }
                namesJob.await()
                val (currentFavorites, remoteFavorites) = favoritesJob.await()
                val (localFavorites, localRevision) = currentFavorites
                if (remoteFavorites != null && remoteFavorites != (localFavorites to localRevision)) {
                    remoteFavorites
                } else {
                    null
                }
            }
            if (synced != null) {
                app.playlistStore.saveFavorites(saved.id, synced.first, synced.second)
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) {
                        applyFavorites(synced.first)
                        if (selectedCategoryId == PlaylistLoader.FAVORITES_ID &&
                            binding.groupColumn.visibility == View.VISIBLE
                        ) {
                            showChannels()
                        }
                    }
                }
            }
            if (!sameLineupEdits(merged, local) && app.stagedLineup?.first != saved.id) {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) {
                        activeConfig = merged
                        epgRepo.useNameEpg(merged.nameEpg == true)
                        renderedSection = null
                        if (binding.groupColumn.visibility == View.VISIBLE && loaded != null) {
                            render(restoreFocus = false)
                        }
                    }
                }
            }
            publishSharedCatalog(app, client, saved.id, email, deviceId)
        }
    }

internal suspend fun BrowseActivity.publishSharedCatalog(
        app: OlympusIptvApp,
        client: LineupClient,
        playlistId: String,
        email: String,
        deviceId: String
    ) {
        val stamp = app.catalogDb.libraryStamp(playlistId).toString()
        val savedStamp = app.playlistStore.catalogStamp(playlistId)
        val savedCountries = app.playlistStore.countryStamp(playlistId)
        if (stamp != savedStamp) {
            app.playlistStore.saveCatalogStamp(playlistId, stamp)
        }
        app.catalogDb.releaseSearchExport()
        val countries = withContext(Dispatchers.IO) { app.catalogDb.dashboardSnapshot(playlistId).countries }
        val countryStamp = countries.joinToString("|") { "${it.id}:${it.count}" }
        if (countryStamp != savedCountries) {
            runCatching { client.publishCountries(email, deviceId, countries) }
            app.playlistStore.saveCountryStamp(playlistId, countryStamp)
        }
    }

internal fun BrowseActivity.sameLineupEdits(left: LineupConfig, right: LineupConfig): Boolean {
        return left.hiddenCountries.orEmpty() == right.hiddenCountries.orEmpty() &&
            left.hiddenGroups.orEmpty() == right.hiddenGroups.orEmpty() &&
            left.hiddenChannels.orEmpty() == right.hiddenChannels.orEmpty() &&
            left.groupNames.orEmpty() == right.groupNames.orEmpty() &&
            left.channelNames.orEmpty() == right.channelNames.orEmpty() &&
            left.groupOrder.orEmpty() == right.groupOrder.orEmpty() &&
            left.channelOrder.orEmpty() == right.channelOrder.orEmpty() &&
            left.namePrefixes.orEmpty() == right.namePrefixes.orEmpty() &&
            left.nameSuffixes.orEmpty() == right.nameSuffixes.orEmpty() &&
            (left.nameEpg == true) == (right.nameEpg == true) &&
            left.groupFind.orEmpty() == right.groupFind.orEmpty() &&
            left.groupReplace.orEmpty() == right.groupReplace.orEmpty() &&
            left.groupPins.orEmpty() == right.groupPins.orEmpty() &&
            (left.includeInternational == true) == (right.includeInternational == true)
    }

internal fun BrowseActivity.intervalLabel(minutes: Int): String = when (minutes) {
        30 -> getString(R.string.refresh_30)
        60 -> getString(R.string.refresh_60)
        120 -> getString(R.string.refresh_120)
        360 -> getString(R.string.refresh_360)
        720 -> getString(R.string.refresh_720)
        1440 -> getString(R.string.refresh_1440)
        else -> getString(R.string.refresh_60)
    }

internal fun BrowseActivity.absorbNewCategories(
        live: List<Category>,
        vod: List<Category>,
        series: List<Category>,
        liveChannels: List<Channel> = emptyList(),
        vodChannels: List<Channel> = emptyList(),
        seriesChannels: List<Channel> = emptyList()
    ) {
        val base = source
        var changed = false
        if (base != null && (live.isNotEmpty() || vod.isNotEmpty() || series.isNotEmpty())) {
            val next = base.copy(
                liveCategories = mergeCategories(base.liveCategories, live),
                vodCategories = mergeCategories(base.vodCategories, vod),
                seriesCategories = mergeCategories(base.seriesCategories.orEmpty(), series)
            )
            changed = next.liveCategories.size != base.liveCategories.size ||
                next.vodCategories.size != base.vodCategories.size ||
                next.seriesCategories.orEmpty().size != base.seriesCategories.orEmpty().size
            if (changed) {
                source = next
                loaded = next
                (application as OlympusIptvApp).guideSource = next
            }
        }
        val hold = binding.groupColumn.visibility != View.VISIBLE
        if (hold) {
            if (changed || liveChannels.isNotEmpty() || vodChannels.isNotEmpty() || seriesChannels.isNotEmpty()) {
                groupsNeedRefresh = changed
                pendingLive.addAll(liveChannels)
                pendingVod.addAll(vodChannels)
                pendingSeries.addAll(seriesChannels)
            }
            return
        }
        if (changed) render(restoreFocus = false, reloadChannels = false)
        queueNewChannels(liveChannels, vodChannels, seriesChannels)
    }

internal fun BrowseActivity.publishAdded(delta: CatalogDb.Added, bucket: String) {
        if (delta.channels.isEmpty() && delta.categories.isEmpty()) return
        if (isDestroyed) return
        lifecycleScope.launch {
            libraryMutex.withLock {
                if (isDestroyed) return@withLock
                val base = source ?: LoadedPlaylist(emptyList(), emptyList())
                val next = when (bucket) {
                    CatalogDb.LIVE -> base.copy(
                        liveCategories = mergeCategories(base.liveCategories, delta.categories),
                        liveChannels = emptyList()
                    )
                    CatalogDb.VOD -> base.copy(
                        vodCategories = mergeCategories(base.vodCategories, delta.categories),
                        vodChannels = emptyList(),
                        vodLoadFailed = false,
                        vodLoadError = null
                    )
                    else -> base.copy(
                        seriesCategories = mergeCategories(base.seriesCategories.orEmpty(), delta.categories),
                        seriesChannels = emptyList(),
                        seriesLoadFailed = false,
                        seriesLoadError = null
                    )
                }
                val config = (application as OlympusIptvApp).playlistStore.lineup(playlist?.id ?: return@withLock)
                val openGuide = loaded == null &&
                    !panes.isDeviceVisible() &&
                    !panes.isAboutVisible() &&
                    !panes.isAdminVisible() &&
                    binding.blankPane.visibility != View.VISIBLE &&
                    !panes.isRefreshVisible() &&
                    !panes.isVpnVisible()
                if (bucket == CatalogDb.VOD) moviesPending = false
                if (bucket == CatalogDb.SERIES) showsPending = false
                showCatalog(next, config, openGuide = openGuide && next.liveChannels.isNotEmpty())
                showLoading(false)
            }
        }
    }

internal fun BrowseActivity.mergeCategories(current: List<com.olympus.iptv.data.Category>, extra: List<com.olympus.iptv.data.Category>): List<com.olympus.iptv.data.Category> {
        if (extra.isEmpty()) return current
        val known = current.map { it.id }.toSet()
        return current + extra.filter { it.id !in known }
    }

internal fun BrowseActivity.publishGuide() {
        val app = application as OlympusIptvApp
        app.guidePlaylistId = playlist?.id
        binding.btnSearch.isEnabled = true
        binding.btnAdmin.isEnabled = true
    }

internal fun BrowseActivity.offerRecording(channel: Channel) {
        if (channel.kind != ContentKind.LIVE || !channel.seriesId.isNullOrBlank()) return
        val saved = playlist ?: return
        val known = epgCache[channel.id]
        if (known != null) {
            RecordPrompt.show(this, saved.id, channel, known.now, known.next)
            return
        }
        lifecycleScope.launch {
            val epg = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).epgRepository.nowNext(saved, channel)
            }
            if (isDestroyed) return@launch
            rememberEpg(channel.id, epg)
            RecordPrompt.show(screen(), saved.id, channel, epg.now, epg.next)
        }
    }

internal fun BrowseActivity.openSearch() {
        if (source == null) return
        startActivity(Intent(this, SearchActivity::class.java))
    }

internal fun BrowseActivity.generationFor(sectionNow: GuideSection): Int = when (sectionNow) {
        GuideSection.TV -> liveIndexGeneration
        GuideSection.MOVIES -> vodIndexGeneration
        GuideSection.SHOWS -> seriesIndexGeneration
    }

internal fun BrowseActivity.applySavedLineup() {
        val saved = playlist ?: return
        val app = application as OlympusIptvApp
        epgCache.clear()
        val staged = app.stagedLineup?.takeIf { it.first == saved.id }?.second
        channelJob?.cancel()
        channelQuery++
        binding.lineupStatus.visibility = View.GONE
            if (staged != null) {
            activeConfig = staged
            epgRepo.useNameEpg(staged.nameEpg == true)
            renderedSection = null
            lifecycleScope.launch(Dispatchers.IO) {
                val stored = app.playlistStore.lineup(saved.id)
                if ((stored.revision ?: 0L) <= (staged.revision ?: 0L)) return@launch
                app.stagedLineup = saved.id to stored
                withContext(Dispatchers.Main) {
                    if (isDestroyed) return@withContext
                    activeConfig = stored
                    epgRepo.useNameEpg(stored.nameEpg == true)
                    renderedSection = null
                    if (loaded != null) render(restoreFocus = false)
                }
            }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val config = app.playlistStore.lineup(saved.id)
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                activeConfig = config
                epgRepo.useNameEpg(config.nameEpg == true)
                renderedSection = null
            }
        }
    }

internal fun BrowseActivity.openEpgEdits() {
        val saved = playlist ?: return
        showTool(EpgEditsFragment.newInstance(saved.id, kind, saved.includeVod))
    }

internal fun BrowseActivity.rememberReturnScreen() {
        if (binding.groupColumn.visibility == View.VISIBLE) returnToGuide = true
        else if (binding.blankPane.visibility == View.VISIBLE) returnToGuide = false
    }

internal fun BrowseActivity.backFromOverlay() {
        val rail = when {
            panes.isAboutVisible() -> binding.btnAbout
            panes.isAdminVisible() -> binding.btnAdmin
            else -> sectionButton()
        }
        showMenu(GuideMenu.SETTINGS)
        if (returnToGuide && loaded != null) showGuide() else showBlank()
        rail.post { rail.requestFocus() }
    }

internal fun BrowseActivity.goBackOneScreen() {
        if (browseToolHost.isToolVisible()) {
            when (val tool = supportFragmentManager.findFragmentById(R.id.toolHost)) {
                is EpgEditsFragment -> tool.closeFromBrowseBack()
                is ParentalFragment, is SanitizeFragment -> {
                    val back = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                    if (tool is ParentalFragment) {
                        if (!tool.dispatchKeyEvent(back)) browseToolHost.hideTool()
                    } else if (tool is SanitizeFragment) {
                        if (!tool.dispatchKeyEvent(back)) browseToolHost.hideTool()
                    }
                }
                else -> browseToolHost.hideTool()
            }
            return
        }
        if (panes.isVpnVisible()) {
            showAdminPane(panes.cardAdminVpn)
            return
        }
        if (panes.isRefreshVisible()) {
            showAdminPane(panes.cardAdminRefresh)
            return
        }
        if (panes.isDeviceVisible()) {
            showAdminPane(panes.cardAdminWeb)
            return
        }
        if (panes.adminOrNull?.adminTools?.visibility == View.VISIBLE) {
            showAdminPane(panes.ensureAdmin().cardAdminSetup)
            return
        }
        if (panes.isAdminVisible() ||
            panes.isAboutVisible() ||
            binding.blankPane.visibility == View.VISIBLE
        ) {
            backFromOverlay()
            return
        }
        if (binding.programPane.visibility == View.VISIBLE && focusIsIn(binding.programPane)) {
            landOnOpened = false
            showMenu(GuideMenu.GROUPS)
            focusGroups()
            return
        }
        if (focusIsIn(binding.channelList)) {
            landOnOpened = false
            showMenu(GuideMenu.GROUPS)
            focusGroups()
            return
        }
        if (focusIsIn(binding.groupColumn)) {
            showMenu(GuideMenu.SETTINGS)
            sectionButton().requestFocus()
            binding.navRail.post { flushGroupRefresh() }
        }
    }

internal fun BrowseActivity.showGuide() {
        binding.btnAdmin.isSelected = false
        binding.btnAbout.isSelected = false
        panes.hideAllSettingPanes()
        binding.blankPane.visibility = View.GONE
        binding.groupColumn.visibility = View.VISIBLE
        applyGuideColumns()
        binding.tabLive.isSelected = section == GuideSection.TV
        binding.tabVod.isSelected = section == GuideSection.MOVIES
        binding.tabShows.isSelected = section == GuideSection.SHOWS
        updateGroupClock(force = true)
    }

internal fun BrowseActivity.paintParentalChip(locked: Int = activeConfig.groupPins.orEmpty().size) {
        if (!viewReady()) return
        binding.btnParental.text = if (locked > 0) {
            getString(R.string.parental_chip_count, locked)
        } else {
            getString(R.string.parental_chip)
        }
    }


internal fun BrowseActivity.hostOnStop() {
        rememberGuideUi()
        (application as OlympusIptvApp).unwatchLineup(lineupWatch)
        AppTunnel.removeListener(vpnWatch)
        if (vpnReady()) vpnUi.stop()
        focusHandler.removeCallbacks(clockTick)
        runCatching { unregisterReceiver(systemClock) }
        if (previewReady()) previewCtl.release()
    }

internal fun BrowseActivity.hostOnDestroy() {
        com.olympus.iptv.portal.DashboardHost.removeUrlListener(dashboardUrls)
        runCatching { browseToolHost.release() }
        if (previewReady()) previewCtl.release()
        refreshLoop?.cancel()
        refreshLoop = null
    }


internal fun BrowseActivity.adoptRemoteLineup() {
        val saved = playlist ?: return
        if (loaded == null || isDestroyed) return
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) { app.playlistStore.lineup(saved.id) }
            val stamp = withContext(Dispatchers.IO) { app.catalogDb.libraryStamp(saved.id) }
            if (isDestroyed || playlist?.id != saved.id) return@launch
            val revisionChanged = (stored.revision ?: 0L) != (activeConfig.revision ?: 0L)
            val stampChanged = stamp != seenLibraryStamp
            if (!revisionChanged && !stampChanged) {
                holdChannelId = null
                return@launch
            }
            if (revisionChanged) {
                app.stagedLineup = saved.id to stored
                activeConfig = stored
                epgRepo.useNameEpg(stored.nameEpg == true)
                renderedSection = null
            }
            val cards = binding.groupColumn.visibility == View.VISIBLE
            val guide = binding.contentColumn.visibility == View.VISIBLE
            if (guide) holdChannelId = holdChannelId ?: visibleFocusId()
            if (stampChanged) reloadRenamedGroups()
            if (!stampChanged && cards && loaded != null) render(restoreFocus = false, reloadChannels = guide && !cards)
            if (guide && !cards) showChannels()
        }
    }

internal fun BrowseActivity.hostOnStart() {
        (application as OlympusIptvApp).watchLineup(lineupWatch)
        if (!openingPlayer) adoptRemoteLineup()
        if (vpnReady()) vpnUi.start()
        AppTunnel.addListener(vpnWatch)
        showVpnStatus(AppTunnel.state)
        offerVpnConsent()
        com.olympus.iptv.portal.DashboardHost.addUrlListener(dashboardUrls)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemClock, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemClock, filter)
        }
        updateGroupClock(force = true)
        val delayMs = 60_000L - (System.currentTimeMillis() % 60_000L)
        focusHandler.postDelayed(clockTick, delayMs.coerceIn(1_000L, 60_000L))
    }



internal fun BrowseActivity.hostOnResume() {
        reloadRenamedGroups()
        val returningFromPlayer = openingPlayer
        openingPlayer = false
        reloadFavorites()
        reloadRecent()
        flushGroupRefresh()
        GroupLock.purgeExpired()
        paintNowPlayingBar()
        paintFreshCount()
        GuideCoach.maybe(this)
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val prefs = withContext(Dispatchers.IO) { store.devicePrefs() }
            pipGestures = withContext(Dispatchers.IO) { store.pipSwipeHome() }
            if (isDestroyed) return@launch
            var redraw = false
            if (prefs.hideEmptyGroups != hideEmptyGroups) {
                hideEmptyGroups = prefs.hideEmptyGroups
                redraw = true
            }
            if (prefs.guideDensity != guideDensity) {
                guideDensity = prefs.guideDensity
                syncProgramRowHeight()
            }
            if (redraw && source != null) render(restoreFocus = false, reloadChannels = false)
            updateGroupClock()
        }
        paintQueueChip()
        if (returningFromPlayer) {
            playerClosedAt = android.os.SystemClock.uptimeMillis()
            restorePlaybackFocus = true
            holdChannelId = returnChannelId
            binding.channelList.post {
                restoreAfterPlayer()
                restoreGuideUiIfPossible()
                maybeShowGuidePip()
                paintNowPlayingBar()
                adoptRemoteLineup()
            }
            return
        }
        restoreGuideUiIfPossible()
        val stagedPins = (application as OlympusIptvApp).stagedLineup?.takeIf { it.first == playlist?.id }?.second
        if (stagedPins != null && stagedPins.groupPins != activeConfig.groupPins) {
            activeConfig = stagedPins
            categoryAdapter.refreshDetails()
        }
        if (loaded != null && currentFocus == null && binding.groupColumn.visibility == View.VISIBLE && !landOnOpened) {
            focusGroups()
        }
    }

internal fun BrowseActivity.withTopWatched(rest: List<Category>): List<Category> {
        if (favoriteIds.isEmpty() || recentEntries.isEmpty()) return rest
        val top = Category(PlaylistLoader.TOP_WATCHED_ID, getString(R.string.top_watched), kind)
        return listOf(top) + rest
    }

internal fun BrowseActivity.resumeEntry(channel: Channel): WatchEntry? =
        if (!channel.seriesId.isNullOrBlank() && channel.streamUrl.isBlank()) {
            recentEntries.firstOrNull { it.seriesId == channel.seriesId && it.canResume() }
        } else {
            recentEntries.firstOrNull { it.channelId == channel.id && it.canResume() }
        }

internal fun BrowseActivity.hostDispatchKey(event: KeyEvent): Boolean {
        if (GuideCoach.onKey(this, event)) return true
        if (browseToolHost.isToolVisible()) {
            when (val tool = supportFragmentManager.findFragmentById(R.id.toolHost)) {
                is ParentalFragment -> if (tool.dispatchKeyEvent(event)) return true
                is SanitizeFragment -> if (tool.dispatchKeyEvent(event)) return true
            }
        }
        if (onGroupPinKey(event)) return true
        if (onDialKey(event)) return true
        if ((event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) &&
            focusIsIn(binding.channelList) &&
            visibleChannels.size > 40
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && lowRamDevice) {
                com.olympus.iptv.ui.BackdropGlow.noteScroll()
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                focusHandler.removeCallbacks(letterJumpHold)
                focusHandler.postDelayed(letterJumpHold, 1_200L)
            }
            if (event.action == KeyEvent.ACTION_UP) focusHandler.removeCallbacks(letterJumpHold)
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
        ) {
            if (PlayerActivity.consumeBrowseBack()) return true
            if (event.eventTime <= playerClosedAt + 300L) return true
            goBackOneScreen()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && focusIsIn(binding.navRail)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (panes.isVpnVisible()) panes.vpnBack.requestFocus()
                    else if (panes.isAdminVisible()) panes.cardAdminRefresh.requestFocus()
                    else if (panes.isDeviceVisible()) panes.deviceBack.requestFocus()
                    else if (panes.isRefreshVisible()) panes.refreshNowCard.requestFocus()
                    else if (binding.groupColumn.visibility == View.VISIBLE) {
                        showMenu(GuideMenu.GROUPS)
                        focusGroups()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> return true
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusNav(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    focusNav(-1)
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            focusIsIn(binding.channelList) &&
            selectedCategoryId == PlaylistLoader.FAVORITES_ID
        ) {
            val direction = favoriteMoveDirection(event)
            if (direction != null) {
                focusedChannel?.let { moveFavorite(it, direction) }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && focusIsIn(binding.programPane)) {
            val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (slot != null && slot.column > 0) {
                        focusProgram(slot.row, slot.column - 1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (slot != null) focusProgram(slot.row, slot.column + 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (slot != null) focusProgramAtTime(slot.row - 1, guideFocusTime(slot))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (slot != null) focusProgramAtTime(slot.row + 1, guideFocusTime(slot))
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && focusIsIn(binding.channelList)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveWithin(binding.channelList, channelStep(), channelHasMore())
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveWithin(binding.channelList, -channelStep(), false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!staysIn(binding.channelList, View.FOCUS_LEFT)) {
                        showMenu(GuideMenu.GROUPS)
                        focusGroups()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (section == GuideSection.TV) {
                        val row = focusedChannelRow()
                        showMenu(GuideMenu.PROGRAMS)
                        focusCurrentProgram(row)
                        return true
                    }
                    if (!staysIn(binding.channelList, View.FOCUS_RIGHT)) {
                        if (visibleChannels.size > 40) showLetterJump()
                        return true
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            focusIsIn(binding.shortcutRow) &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            binding.homeContinue.visibility == View.VISIBLE
        ) {
            binding.homeContinuePlay.requestFocus()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && focusIsIn(binding.categoryList)) {
            val item = binding.categoryList.findContainingItemView(currentFocus ?: binding.categoryList)
            val pos = if (item == null) -1 else binding.categoryList.getChildAdapterPosition(item)
            val span = (binding.categoryList.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)?.spanCount ?: CARD_SPAN
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos < 0 || pos % span == 0) {
                        categoryAdapter.setSelected(null)
                        showMenu(GuideMenu.SETTINGS)
                        sectionButton().requestFocus()
                        binding.navRail.post { flushGroupRefresh() }
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val last = categoryAdapter.itemCount - 1
                    if (pos >= 0 && (pos % span == span - 1 || pos == last)) {
                        val category = categoryAdapter.categories().getOrNull(pos) ?: return true
                        selectCategory(category, true)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (pos in 0 until span) {
                        when {
                            binding.homeContinue.visibility == View.VISIBLE -> {
                                categoryAdapter.setSelected(null)
                                binding.homeContinuePlay.requestFocus()
                                return true
                            }
                            binding.shortcutRow.visibility == View.VISIBLE -> {
                                categoryAdapter.setSelected(null)
                                binding.btnFavoritesSetup.requestFocus()
                                return true
                            }
                        }
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && panes.refreshRoot?.let { focusIsIn(it) } == true) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    when (currentFocus) {
                        panes.refreshIntervalCard -> panes.refreshNowCard.requestFocus()
                        panes.refreshNowCard -> panes.refreshBack.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    when (currentFocus) {
                        panes.refreshBack -> panes.refreshNowCard.requestFocus()
                        panes.refreshNowCard -> panes.refreshIntervalCard.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    binding.btnAdmin.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        if (rowSelectKey(event.keyCode) && focusIsIn(binding.categoryList)) {
            if (event.isLongPress) {
                val category = focusedCategory()
                if (category != null) offerGroupLock(category)
                groupHold = true
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                event.startTracking()
                groupHold = false
            } else if (event.action == KeyEvent.ACTION_UP && groupHold) {
                groupHold = false
                return true
            }
        }
        if (rowSelectKey(event.keyCode) && focusOnGuideRow()) {
            if (event.isLongPress) {
                if (!rowLongPress) {
                    rowLongPress = true
                    showRowOptions()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                event.startTracking()
                rowLongPress = false
            } else if (event.action == KeyEvent.ACTION_UP) {
                val held = rowLongPress
                rowLongPress = false
                if (held) return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            if (panes.isVpnVisible()) {
                panes.vpnBack.requestFocus()
            } else if (panes.isAdminVisible()) {
                panes.cardAdminRefresh.requestFocus()
            } else if (panes.isDeviceVisible()) {
                panes.deviceBack.requestFocus()
            } else if (panes.isRefreshVisible()) {
                panes.refreshNowCard.requestFocus()
            } else {
                focusGroups()
            }
            return true
        }
        return passKey(event)
    }

internal fun BrowseActivity.containsView(root: View, child: View?): Boolean {
        var current = child
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

internal fun BrowseActivity.staysIn(list: View, direction: Int): Boolean {
        val focused = currentFocus ?: return false
        val next = focused.focusSearch(direction) ?: return false
        return containsView(list, next)
    }

internal fun BrowseActivity.moveWithin(list: androidx.recyclerview.widget.RecyclerView, direction: Int, moreBelow: Boolean) {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        val holder = currentFocus?.let { list.findContainingViewHolder(it) }
        val current = holder?.bindingAdapterPosition?.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION } ?: 0
        val focusId = currentFocus?.id ?: View.NO_ID
        if (direction > 0 && current == count - 1) {
            if (moreBelow && list === binding.channelList) {
                pendingChannelFocus = count
                pendingChannelFocusId = focusId
                loadGroupPage(reset = false)
                return
            }
            focusRowAt(list, 0, focusId)
            return
        }
        val nextIndex = current + direction
        if (direction > 0 && nextIndex >= count) {
            if (moreBelow && list === binding.channelList && current < count - 1) {
                pendingChannelFocus = count
                pendingChannelFocusId = focusId
                loadGroupPage(reset = false)
                return
            }
            focusRowAt(list, 0, focusId)
            return
        }
        if (nextIndex < 0) {
            focusRowAt(list, count - 1, focusId)
            return
        }
        focusRowAt(list, nextIndex, focusId)
    }

internal fun BrowseActivity.paintQueueChip() {
        val saved = playlist ?: return
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.queue(saved.id).size
            }
            if (isDestroyed) return@launch
            binding.btnQueue.text = if (count > 0) {
                getString(R.string.queue_chip_count, count)
            } else {
                getString(R.string.nav_queue)
            }
        }
    }

internal fun BrowseActivity.enlargeTouchChips() {
        if (!touchPrimary) return
        val min = (48 * resources.displayMetrics.density).toInt()
        val padH = (16 * resources.displayMetrics.density).toInt()
        listOf(
            binding.btnFavoritesSetup, binding.btnQueue, binding.btnFresh,
            binding.btnRemind, binding.btnParental, binding.btnLastGroup,
            binding.mobileTabLive, binding.mobileTabMovies, binding.mobileTabShows,
            binding.mobileBack, binding.mobileAdmin, binding.mobileAbout, binding.mobileSearch,
            binding.mobileCast, binding.groupsSearch,
            binding.bottomTabLive, binding.bottomTabMovies, binding.bottomTabShows,
            binding.bottomTabLibrary, binding.bottomTabAdmin
        ).forEach { chip ->
            chip.minHeight = min
            chip.setPadding(padH, chip.paddingTop, padH, chip.paddingBottom)
            chip.isFocusableInTouchMode = false
        }
    }

internal fun BrowseActivity.applyGuideColumns() {
        val live = section == GuideSection.TV
        val full = guideMenu == GuideMenu.CHANNELS || guideMenu == GuideMenu.PROGRAMS
        val showSettings = !full
        val tablet = touchPrimary && !com.olympus.iptv.ui.DeviceForm.isCompact(this)
        val showGroups = !full || tablet
        val showChannels = full
        val stacked = touchPrimary &&
            com.olympus.iptv.ui.DeviceForm.isCompact(this) &&
            com.olympus.iptv.ui.DeviceForm.isPortrait(this)
        val phoneChrome = touchPrimary && com.olympus.iptv.ui.DeviceForm.isCompact(this)
        // Portrait phones use stacked now/next cards. Landscape and TV use the hour grid.
        val showPrograms = live && full && !stacked && guideMenu == GuideMenu.PROGRAMS
        setNavExpanded(true)
        val blockChannels = live && guideMenu == GuideMenu.PROGRAMS && !phoneChrome
        channelAdapter.guideRowsFocusable = !blockChannels
        binding.channelList.descendantFocusability = if (blockChannels) {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        channelAdapter.paintRowFocus(binding.channelList)
        val row = binding.guideRow
        // TV gets a short column morph; phones skip it — transitions feel laggy on touch devices.
        if (!phoneChrome && row.isLaidOut && !skipGuideTransition) {
            TransitionManager.beginDelayedTransition(row, AutoTransition().apply { duration = 180 })
        }
        skipGuideTransition = false
        if (showGroups) {
            binding.groupColumn.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            binding.categoryList.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        binding.mobileTopBar.visibility = if (phoneChrome) View.VISIBLE else View.GONE
        binding.mobileBottomTabs.visibility = if (phoneChrome) View.VISIBLE else View.GONE
        binding.navRail.visibility = if (showSettings && !phoneChrome) View.VISIBLE else View.GONE
        binding.navDivider.visibility = if (showSettings && !phoneChrome) View.VISIBLE else View.GONE
        if (!showGroups && focusIsIn(binding.groupColumn)) binding.groupColumn.clearFocus()
        binding.groupColumn.visibility = if (showGroups) View.VISIBLE else View.GONE
        binding.shortcutRow.visibility = if (showGroups && live) View.VISIBLE else View.GONE
        binding.groupPills.visibility = if (stacked && showChannels) View.VISIBLE else View.GONE
        if (stacked && showChannels) syncGroupPills()
        binding.contentDivider.visibility = if (showGroups && showChannels) View.VISIBLE else View.GONE
        binding.contentColumn.visibility = if (showChannels) View.VISIBLE else View.GONE
        binding.programPane.visibility = if (showPrograms) View.VISIBLE else View.GONE
        if (phoneChrome) {
            val pinOpen = binding.groupPin.visibility == View.VISIBLE ||
                binding.groupPinWrong.visibility == View.VISIBLE
            val settingsOpen = panes.isAdminVisible() ||
                panes.isAboutVisible() ||
                panes.isRefreshVisible() ||
                panes.isVpnVisible() ||
                panes.isDeviceVisible()
            binding.mobileBack.visibility = if (full || pinOpen || settingsOpen) View.VISIBLE else View.GONE
            binding.mobileSectionRow.visibility = View.GONE
            binding.mobileTitle.text = when {
                panes.isAdminVisible() -> getString(R.string.nav_admin)
                panes.isAboutVisible() -> getString(R.string.nav_about)
                full && selectedCategoryName.isNotBlank() -> selectedCategoryName
                live -> getString(R.string.nav_tv)
                section == GuideSection.MOVIES -> getString(R.string.vod)
                section == GuideSection.SHOWS -> getString(R.string.nav_shows)
                else -> getString(R.string.rail_title)
            }
            paintBottomTabs()
            paintMobileSettingsChips()
        } else {
            binding.mobileSectionRow.visibility = View.GONE
        }
        paintHomeContinue()
        paintNowPlayingBar()
        if (touchPrimary) {
            binding.groupsFilter.visibility = if (showGroups) View.VISIBLE else View.GONE
        }
        val content = binding.contentColumn.layoutParams as LinearLayout.LayoutParams
        val programs = binding.programPane.layoutParams as LinearLayout.LayoutParams
        val density = resources.displayMetrics.density
        binding.channelList.setHasFixedSize(!stacked)
        if (tablet && showGroups) {
            val groups = binding.groupColumn.layoutParams as LinearLayout.LayoutParams
            val width = (260 * density).toInt()
            if (groups.width != width || groups.weight != 0f) {
                groups.width = width
                groups.weight = 0f
                binding.groupColumn.layoutParams = groups
            }
        } else if (!phoneChrome) {
            val groups = binding.groupColumn.layoutParams as LinearLayout.LayoutParams
            if (groups.weight != 1f) {
                groups.width = 0
                groups.weight = 1f
                binding.groupColumn.layoutParams = groups
            }
        }
        if (stacked && showChannels) {
            content.width = 0
            content.weight = 1f
            programs.width = 0
            programs.weight = 0f
        } else if (tablet && showChannels && showPrograms) {
            content.width = 0
            content.weight = 1f
            programs.width = 0
            programs.weight = 1.4f
        } else if (showChannels && showPrograms && !full) {
            content.width = ((if (guideMenu == GuideMenu.SETTINGS) 180 else 220) * density).toInt()
            content.weight = 0f
            programs.width = 0
            programs.weight = 1f
        } else if (showChannels && showPrograms) {
            content.weight = 0f
            programs.width = 0
            programs.weight = 1.5f
        } else {
            content.width = 0
            content.weight = if (showChannels) 1f else 0f
            programs.width = 0
            programs.weight = 0f
        }
        binding.contentColumn.layoutParams = content
        binding.programPane.layoutParams = programs
        binding.previewCard.visibility = View.GONE
        if (previewReady()) previewCtl.pause()
        styleGuideHeader()
        if (showPrograms) {
            if (full) fitChannelColumn()
            ensureProgramGuide()
            if (epgReady()) epgCtl.syncChannelOffset()
        } else if (epgReady()) {
            epgCtl.syncChannelOffset()
        }
    }

internal fun BrowseActivity.hidePreviewChrome() {
        binding.groupPills.visibility = View.GONE
        binding.previewCard.visibility = View.GONE
        if (previewReady()) previewCtl.pause()
    }


internal fun BrowseActivity.openFullGuide() {
        openStamp += 1
        channelsStamp = -1
        landOnOpened = true
        openFocusTries = 0
        skipGuideTransition = true
        binding.categoryList.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.groupColumn.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (focusIsIn(binding.categoryList)) currentFocus?.clearFocus()
        // Portrait phones use stacked now/next cards. Landscape, tablets, and TV use the hour grid.
        val portraitPhone = touchPrimary &&
            com.olympus.iptv.ui.DeviceForm.isCompact(this) &&
            com.olympus.iptv.ui.DeviceForm.isPortrait(this)
        if (section == GuideSection.TV && !portraitPhone) {
            if (guideMenu != GuideMenu.PROGRAMS) previousMenu = guideMenu
            guideMenu = GuideMenu.PROGRAMS
        } else {
            if (guideMenu != GuideMenu.CHANNELS) previousMenu = guideMenu
            guideMenu = GuideMenu.CHANNELS
        }
        applyGuideColumns()
        focusOpenedGuide()
    }

internal fun BrowseActivity.waitingOnGuide(): Boolean {
        val channel = visibleChannels.firstOrNull() ?: return false
        if (programAdapter.programsFor(channel.id) != null) return false
        val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot ?: return false
        return slot.row == 0 && focusIsIn(binding.programPane)
    }

internal fun BrowseActivity.queuePreview(channel: Channel) {
        previewCtl.queue(channel, liveSection = section == GuideSection.TV)
    }

internal fun BrowseActivity.startPreview(channel: Channel) {
        previewCtl.start(channel)
    }

internal fun BrowseActivity.handOffPreview(channel: Channel) {
        previewCtl.handOff(channel)
    }

internal fun BrowseActivity.stopPreview() {
        previewCtl.stop()
    }


internal fun BrowseActivity.openProgramGuide() {
        if (section != GuideSection.TV) return
        val row = focusedChannelRow()
        showMenu(GuideMenu.PROGRAMS)
        binding.programList.post {
            syncProgramRowHeight()
            positionNowLine(programAdapter.scrollX)
            focusProgram(row, currentProgramColumn(row))
        }
    }


internal fun BrowseActivity.offerReminder(channel: Channel) {
        val saved = playlist ?: return
        val ready = reminderTarget(channel)
        if (ready != null) {
            val number = visibleChannels.indexOfFirst { it.id == channel.id }.let { if (it >= 0) it + 1 else channel.number ?: 1 }
            RemindPrompt.show(this, saved.id, channel, number, ready)
            return
        }
        lifecycleScope.launch {
            val epg = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).epgRepository.nowNext(saved, channel)
            }
            if (isDestroyed) return@launch
            rememberEpg(channel.id, epg)
            val now = EpgUtils.now()
            val program = epg.next?.takeIf { it.startMillis > now } ?: epg.now?.takeIf { it.startMillis > now }
            if (program == null) {
                Toast.makeText(screen(), R.string.remind_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val number = visibleChannels.indexOfFirst { it.id == channel.id }.let { if (it >= 0) it + 1 else channel.number ?: 1 }
            RemindPrompt.show(screen(), saved.id, channel, number, program)
        }
    }

internal fun BrowseActivity.reminderTarget(channel: Channel): EpgProgram? {
        val now = EpgUtils.now()
        val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot
        val focused = if (slot != null && visibleChannels.getOrNull(slot.row)?.id == channel.id) {
            programAdapter.programAt(channel.id, slot.column)
        } else {
            null
        }
        if (focused != null && focused.startMillis > now) return focused
        programAdapter.programsFor(channel.id).orEmpty().firstOrNull { it.startMillis > now }?.let { return it }
        val known = epgCache[channel.id]
        return known?.next?.takeIf { it.startMillis > now } ?: known?.now?.takeIf { it.startMillis > now }
    }

internal fun BrowseActivity.ensureProgramGuide() {
        if (!guideReady()) return
        if (programAdapter.windowEnd <= programAdapter.windowStart) {
            val now = com.olympus.iptv.playlist.EpgUtils.now()
            val start = GuideMetrics.start(now, halfHourMs)
            programAdapter.setWindow(start, GuideMetrics.end(start))
            programAdapter.scrollX = 0
        }
        if (binding.programHeader.childCount == 0) buildProgramHeader()
        syncProgramRowHeight()
        programAdapter.submit(visibleChannels)
        loadVisibleGuidePrograms()
        binding.programList.post {
            syncProgramRowHeight()
            positionNowLine(programAdapter.scrollX)
        }
    }

internal fun BrowseActivity.syncProgramGuide() {
        if (section == GuideSection.TV && binding.programPane.visibility == View.VISIBLE) {
            fitChannelColumn()
            ensureProgramGuide()
        }
        focusOpenedGuide()
    }

internal fun BrowseActivity.loadVisibleGuidePrograms() {
        if (section != GuideSection.TV || binding.programPane.visibility != View.VISIBLE) return
        val layout = binding.programList.layoutManager as? LinearLayoutManager
            ?: binding.channelList.layoutManager as? LinearLayoutManager
            ?: return
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val lastVisible = layout.findLastVisibleItemPosition()
        val last = if (lastVisible < first + 12) first + 23 else lastVisible
        val from = (first - 4).coerceAtLeast(0)
        val to = (last + 12).coerceAtMost(visibleChannels.lastIndex)
        if (to < from) return
        loadGuidePrograms(visibleChannels.subList(from, to + 1))
    }

internal fun BrowseActivity.nameOnlyGuideGroup(): Boolean {
        if (section != GuideSection.TV) return false
        val count = groupCounts[selectedCategoryId] ?: 0
        return count >= LARGE_LIVE_GROUP
    }

internal fun BrowseActivity.loadGuidePrograms(channels: List<Channel>) {
        val missing = channels.filter { channel ->
            channel.kind == ContentKind.LIVE &&
                programAdapter.programsFor(channel.id) == null &&
                guideEpgBusy.add(channel.id)
        }
        if (missing.isEmpty()) {
            binding.programList.post {
                lastLabelShiftScroll = programAdapter.scrollX
                applyGuideRowHeight(programAdapter.refreshLabelShift(binding.programList))
            }
            return
        }
        // Mega Live folders (24/7, etc.): hour blocks of the channel name — skip provider EPG.
        if (nameOnlyGuideGroup()) {
            for (channel in missing) {
                programAdapter.put(channel.id, emptyList())
                guideEpgBusy.remove(channel.id)
            }
            binding.programList.post {
                lastLabelShiftScroll = programAdapter.scrollX
                applyGuideRowHeight(programAdapter.refreshLabelShift(binding.programList))
            }
            return
        }
        val saved = playlist ?: run {
            missing.forEach { guideEpgBusy.remove(it.id) }
            return
        }
        guideJob = lifecycleScope.launch {
            try {
                fetchGuidePrograms(saved, missing.take(12), 6)
                fetchGuidePrograms(saved, missing.drop(12), 4)
            } finally {
                missing.forEach { guideEpgBusy.remove(it.id) }
            }
            if (isDestroyed) return@launch
            binding.programList.post {
                lastLabelShiftScroll = programAdapter.scrollX
                applyGuideRowHeight(programAdapter.refreshLabelShift(binding.programList))
            }
        }
    }


internal suspend fun BrowseActivity.fetchGuidePrograms(saved: SavedPlaylist, channels: List<Channel>, width: Int) {
        if (channels.isEmpty()) return
        channels.chunked(width).forEach { batch ->
            coroutineScope {
                batch.map { channel ->
                    async(Dispatchers.IO) {
                        channel.id to runCatching { epgRepo.upcoming(saved, channel) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }.forEach { (id, list) ->
                val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot
                val keep = slot != null && visibleChannels.getOrNull(slot.row)?.id == id
                programAdapter.put(id, list)
                if (landOnOpened && visibleChannels.getOrNull(0)?.id == id) {
                    openFocusTries = 0
                    scheduleGuideFocusRestore { focusCurrentProgram(0) }
                } else if (keep && binding.programPane.visibility == View.VISIBLE) {
                    val column = if (slot.column == 0) currentProgramColumn(slot.row) else slot.column
                    val row = slot.row
                    scheduleGuideFocusRestore {
                        val held = currentFocus?.tag as? ProgramGuideAdapter.Slot
                        if (held != null && held.row == row && held.column == column && currentFocus?.hasFocus() == true) {
                            return@scheduleGuideFocusRestore
                        }
                        focusProgram(row, column)
                    }
                }
            }
        }
    }

internal fun BrowseActivity.scheduleGuideAlign(
        source: androidx.recyclerview.widget.RecyclerView,
        target: androidx.recyclerview.widget.RecyclerView
    ) {
        if (guideScrollSync || binding.programPane.visibility != View.VISIBLE) return
        val from = source.layoutManager as? LinearLayoutManager ?: return
        val index = from.findFirstVisibleItemPosition()
        if (index == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val child = from.findViewByPosition(index) ?: return
        if (index == lastAlignIndex && child.top == lastAlignTop) return
        lastAlignIndex = index
        lastAlignTop = child.top
        alignGuideSource = source
        alignGuideTarget = target
        source.removeCallbacks(alignGuideRunnable)
        source.postOnAnimation(alignGuideRunnable)
    }

internal fun BrowseActivity.paintProgramSynopsis(channel: Channel, program: EpgProgram?) {
        if (!viewReady() || !epgReady()) return
        epgCtl.paintSynopsis(channel, program)
    }

internal fun BrowseActivity.rememberGuideUi() {
        if (!viewReady() || !epgReady()) return
        epgCtl.captureScrolls(
            model = browseModel,
            playlistId = playlist?.id,
            sectionKey = when (section) {
                GuideSection.MOVIES -> "vod"
                GuideSection.SHOWS -> "series"
                else -> "live"
            },
            categoryId = selectedCategoryId,
            categoryName = selectedCategoryName,
            menu = guideMenu.name.lowercase(Locale.US)
        )
    }

internal fun BrowseActivity.restoreGuideUiIfPossible() {
        if (!epgReady()) return
        epgCtl.restoreScrolls(browseModel, playlist?.id)
    }

internal fun BrowseActivity.alignGuide(
        source: androidx.recyclerview.widget.RecyclerView,
        target: androidx.recyclerview.widget.RecyclerView
    ) {
        if (guideScrollSync || binding.programPane.visibility != View.VISIBLE) return
        val from = source.layoutManager as? LinearLayoutManager ?: return
        val to = target.layoutManager as? LinearLayoutManager ?: return
        val index = from.findFirstVisibleItemPosition()
        if (index == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val child = from.findViewByPosition(index) ?: return
        val shown = to.findViewByPosition(index)
        if (shown != null && shown.top == child.top) {
            lastAlignIndex = index
            lastAlignTop = child.top
            return
        }
        guideScrollSync = true
        lastAlignIndex = index
        lastAlignTop = child.top
        to.scrollToPositionWithOffset(index, child.top)
        target.post { guideScrollSync = false }
    }

internal fun BrowseActivity.currentProgramColumn(row: Int): Int {
        val channel = visibleChannels.getOrNull(row) ?: return 0
        return programAdapter.columnAt(channel, com.olympus.iptv.playlist.EpgUtils.now())
    }

internal fun BrowseActivity.categoryPreviewLogos(category: Category): List<String> {
        val map = when (section) {
            GuideSection.TV -> liveByCategory
            GuideSection.MOVIES -> vodByCategory
            GuideSection.SHOWS -> if (seriesByCategory.isNotEmpty()) seriesByCategory else vodByCategory
        }
        val rows = map[category.id].orEmpty()
        if (rows.isEmpty()) return emptyList()
        val urls = ArrayList<String>(3)
        for (channel in rows) {
            val url = channel.logoUrl?.takeIf { it.isNotBlank() } ?: continue
            if (urls.contains(url)) continue
            urls.add(url)
            if (urls.size >= 3) break
        }
        return urls
    }

internal fun BrowseActivity.invalidateTopWatchedCache() {
        topWatchedIdsBySection = emptyMap()
        topWatchedCacheKey = ""
    }

internal fun BrowseActivity.ensureTopWatchedCache() {
        val key = "${recentStamp}|${favoriteOrder.joinToString()}"
        if (key == topWatchedCacheKey && topWatchedIdsBySection.isNotEmpty()) return
        val countsBySection = HashMap<String, LinkedHashMap<String, Int>>()
        for (entry in recentEntries) {
            val id = entry.channelId?.takeIf { it.isNotBlank() } ?: continue
            val section = entrySection(entry)
            val counts = countsBySection.getOrPut(section) { LinkedHashMap() }
            counts[id] = (counts[id] ?: 0) + 1
        }
        val favIds = favoriteIds.map { it.substringAfter(':') }.toSet()
        val built = HashMap<String, List<String>>(3)
        for ((section, counts) in countsBySection) {
            built[section] = counts.entries
                .filter { it.key in favIds }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(10)
        }
        topWatchedIdsBySection = built
        topWatchedCacheKey = key
    }

internal fun BrowseActivity.idIndex(sectionNow: GuideSection): Map<String, Channel> {
        val indexed = when (sectionNow) {
            GuideSection.TV -> liveById
            GuideSection.MOVIES -> vodById
            GuideSection.SHOWS -> if (seriesById.isNotEmpty()) seriesById else vodById
        }
        if (indexed.isNotEmpty()) return indexed
        return visibleChannels.associateBy { it.id }
    }

internal fun BrowseActivity.indexedPool(data: LoadedPlaylist, sectionNow: GuideSection, categoryId: String?): List<Channel>? {
        if (categoryId == null ||
            categoryId == PlaylistLoader.ALL_ID ||
            categoryId == PlaylistLoader.FAVORITES_ID
        ) {
            return null
        }
        val map = when (sectionNow) {
            GuideSection.TV -> liveByCategory
            GuideSection.MOVIES -> vodByCategory
            GuideSection.SHOWS -> when {
                seriesByCategory.isNotEmpty() -> seriesByCategory
                data.seriesChannels.isNullOrEmpty() -> vodByCategory
                else -> return null
            }
        }
        if (map.isEmpty()) return null
        return map[categoryId].orEmpty()
    }

internal fun BrowseActivity.prefetchEpg(channels: List<Channel>) {
        val saved = playlist ?: return
        epgPrefetchJob?.cancel()
        val limit = if (touchPrimary) 3 else 8
        val pending = channels.filter { it.kind == ContentKind.LIVE && !epgCache.containsKey(it.id) }.take(limit)
        if (pending.isEmpty()) return
        epgPrefetchJob = lifecycleScope.launch {
            val loaded = coroutineScope {
                pending.map { channel ->
                    async(Dispatchers.IO) {
                        channel.id to runCatching { epgRepo.nowNext(saved, channel) }.getOrDefault(EpgNowNext())
                    }
                }.awaitAll()
            }
            if (!isActive) return@launch
            loaded.forEach { (id, nowNext) ->
                rememberEpg(id, nowNext)
                channelAdapter.notifyEpgChanged(id)
            }
        }
    }

internal fun BrowseActivity.setupPipSwipeOpen() {
        if (!touchPrimary) return
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!pipGestures) return false
                val start = e1 ?: return false
                val dy = start.y - e2.y
                if (dy < 120f || kotlin.math.abs(velocityY) < 800f) return false
                if (kotlin.math.abs(velocityY) < kotlin.math.abs(velocityX)) return false
                val channel = focusedChannel ?: visibleChannels.firstOrNull() ?: return false
                Haptics.tick(binding.channelList)
                play(channel, enterPip = true)
                return true
            }
        })
        binding.channelList.addOnItemTouchListener(object :
            androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(
                rv: androidx.recyclerview.widget.RecyclerView,
                e: MotionEvent
            ): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

internal fun BrowseActivity.toggleGuideFavorite(channel: Channel) {
        val saved = playlist ?: return
        Haptics.confirm(binding.root)
        val section = favoriteSection(section)
        val key = Favorites.keyFor(channel, section)
        val adding = !Favorites.matches(channel, favoriteIds)
        if (adding) {
            favoriteIds.add(key)
            if (!favoriteOrder.contains(key)) favoriteOrder.add(key)
        } else {
            favoriteIds.remove(key)
            favoriteOrder.remove(key)
            if (section == Favorites.LIVE) {
                favoriteIds.remove(channel.id)
                favoriteOrder.remove(channel.id)
            }
        }
        invalidateTopWatchedCache()
        if (!adding && selectedCategoryId == PlaylistLoader.FAVORITES_ID) {
            visibleChannels.removeAll { it.id == channel.id }
            channelAdapter.remove(channel.id)
            if (visibleChannels.isEmpty()) finishChannelPane(emptyList())
        } else {
            channelAdapter.notifyFavoriteChanged(channel.id)
        }
        val app = application as OlympusIptvApp
        app.appScope.launch {
            val base = app.playlistStore.favoritesRevision(saved.id)
            app.playlistStore.setFavorite(saved.id, key, adding)
            if (!adding && section == Favorites.LIVE) {
                app.playlistStore.setFavorite(saved.id, channel.id, false)
            }
            pushFavorites(app, saved.id, base)
            app.appScope.launch { com.olympus.iptv.ui.player.TvLauncherBridge.refreshFavorites(app, saved.id) }
        }
        com.olympus.iptv.ui.ActionPill.show(
            this,
            getString(if (adding) R.string.pill_favorite_add else R.string.pill_favorite_remove, channel.name)
        ) {
            toggleGuideFavorite(channel)
        }
    }

internal fun BrowseActivity.tuneFromLauncher(playlistId: String) {
        val id = intent.getStringExtra(BrowseActivity.EXTRA_TUNE_ID)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(BrowseActivity.EXTRA_TUNE_ID)
        val app = application as OlympusIptvApp
        app.autoResumeUsed = true
        lifecycleScope.launch {
            val channel = withContext(Dispatchers.IO) {
                app.catalogDb.readChannel(playlistId, CatalogDb.LIVE, id)
                    ?: app.catalogDb.readChannel(playlistId, CatalogDb.VOD, id)
                    ?: app.catalogDb.readChannel(playlistId, CatalogDb.SERIES, id)
            } ?: return@launch
            if (!isDestroyed && channel.streamUrl.isNotBlank()) play(channel)
        }
    }

internal fun BrowseActivity.paintAutoResume() {
        if (panes.deviceOrNull?.deviceAutoResumeTitle == null) return
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val on = withContext(Dispatchers.IO) { app.playlistStore.autoResume() }
            if (isDestroyed) return@launch
            panes.deviceOrNull?.deviceAutoResumeTitle?.text =
                getString(R.string.auto_resume_title) + if (on) " · On" else " · Off"
        }
    }

internal fun BrowseActivity.toggleAutoResume() {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) { !app.playlistStore.autoResume() }
            withContext(Dispatchers.IO) { app.playlistStore.saveAutoResume(next) }
            if (!isDestroyed) paintAutoResume()
        }
    }

internal fun BrowseActivity.maybeAutoResume(playlistId: String) {
        val app = application as OlympusIptvApp
        if (app.autoResumeUsed) return
        if (intent.getBooleanExtra(BrowseActivity.EXTRA_SKIP_RESUME, false)) {
            app.autoResumeUsed = true
            return
        }
        app.autoResumeUsed = true
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) { app.playlistStore.autoResume() }
            if (!enabled || isDestroyed) return@launch
            val id = withContext(Dispatchers.IO) { app.playlistStore.lastChannelId(playlistId) } ?: return@launch
            val channel = withContext(Dispatchers.IO) {
                app.catalogDb.readChannel(playlistId, CatalogDb.LIVE, id)
            } ?: return@launch
            if (isDestroyed || channel.streamUrl.isBlank()) return@launch
            play(channel)
        }
    }

internal fun BrowseActivity.hideGuideChannel(channel: Channel) {
        val saved = playlist ?: return
        val previous = activeConfig.hiddenChannels.orEmpty()
        if (channel.id in previous) return
        activeConfig = activeConfig.copy(hiddenChannels = previous + channel.id)
        visibleChannels.removeAll { it.id == channel.id }
        channelAdapter.remove(channel.id)
        val app = application as OlympusIptvApp
        app.appScope.launch { app.playlistStore.saveLineup(saved.id, activeConfig) }
        com.olympus.iptv.ui.ActionPill.show(this, getString(R.string.pill_hidden, channel.name)) {
            activeConfig = activeConfig.copy(hiddenChannels = previous)
            app.appScope.launch { app.playlistStore.saveLineup(saved.id, activeConfig) }
            render(restoreFocus = true)
        }
    }

internal fun BrowseActivity.maybeShowGuidePip() {
        val app = application as OlympusIptvApp
        val channel = app.lastPlayed?.takeIf {
            it.kind == ContentKind.LIVE && it.seriesId.isNullOrBlank() && it.streamUrl.isNotBlank()
        } ?: return
        if (app.lastPlayedPlaylistId != playlist?.id) return
        previewCtl.showGuidePip(channel) { play(it) }
        paintNowPlayingBar()
    }

internal fun BrowseActivity.playFromGuide(channel: Channel, program: EpgProgram?) {
        val now = System.currentTimeMillis()
        if (program != null && program.endMillis <= now) {
            if (channel.supportsCatchup()) playCatchup(channel, program)
            else Toast.makeText(this, R.string.catchup_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        play(channel)
    }
