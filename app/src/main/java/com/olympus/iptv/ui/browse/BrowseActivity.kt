package com.olympus.iptv.ui.browse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.olympus.iptv.BuildConfig
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import com.olympus.iptv.remind.RemindersActivity
import com.olympus.iptv.record.RecordPrompt
import com.olympus.iptv.record.RecordingService
import com.olympus.iptv.ui.FocusMotion
import com.olympus.iptv.ui.Haptics
import com.olympus.iptv.ui.epg.EpgEditsActivity
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

class BrowseActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowseBinding
    private val loader = PlaylistLoader()

    private var playlist: SavedPlaylist? = null
    private var source: LoadedPlaylist? = null
    private var loaded: LoadedPlaylist? = null
    private val epgRepo: EpgRepository
        get() = (application as OlympusIptvApp).epgRepository
    private var kind = ContentKind.LIVE
    private var renderedSection: GuideSection? = null
    private var selectedCategoryId: String? = PlaylistLoader.FAVORITES_ID
    private var touchPrimary = false
    private var openingPlayer = false
    private var pipGestures = true
    private var returnMenu = GuideMenu.GROUPS
    private var returnChannelId: String? = null
    private var returnOnProgram = false
    private var restorePlaybackFocus = false
    private var playerClosedAt = 0L
    private var moviesPending = false
    private var showsPending = false
    @Volatile private var movieCount = 0
    @Volatile private var showCount = 0
    @Volatile private var moviePercent = -1
    @Volatile private var showPercent = -1
    private var movieError: String? = null
    private var movieAttempt = 1
    private var showError: String? = null
    private var lastMoviePost = 0L
    private var lastShowPost = 0L
    private val countFormat = NumberFormat.getIntegerInstance()
    private var refreshLoop: Job? = null
    private var refreshMinutes = PlaylistStore.DEFAULT_REFRESH_MINUTES
    private var groupsNeedRefresh = false
    private val pendingLive = ArrayList<Channel>()
    private val pendingVod = ArrayList<Channel>()
    private val pendingSeries = ArrayList<Channel>()
    private var activeConfig = LineupConfig()
    private var channelJob: Job? = null
    private var channelQuery = 0
    private var liveIndexGeneration = 0
    private var vodIndexGeneration = 0
    private var seriesIndexGeneration = 0
    private var liveIndexing = false
    private var vodIndexing = false
    private var seriesIndexing = false
    private var liveByCategory: Map<String, List<Channel>> = emptyMap()
    private var vodByCategory: Map<String, List<Channel>> = emptyMap()
    private var seriesByCategory: Map<String, List<Channel>> = emptyMap()
    private var liveById: Map<String, Channel> = emptyMap()
    private var vodById: Map<String, Channel> = emptyMap()
    private var seriesById: Map<String, Channel> = emptyMap()
    private val visibleChannels = ArrayList<Channel>()
    private var pageCursor: CatalogDb.PageCursor? = null
    private var pageExhausted = false
    private var emptyPageSkips = 0
    private var pendingChannelFocus = -1
    private var pendingChannelFocusId = View.NO_ID
    private var pageLoading = false
    private var loadingFromDb = false
    private val libraryMutex = Mutex()
    private var focusedChannel: Channel? = null
    private var detailJob: Job? = null
    private val favoriteOrder = mutableListOf<String>()
    private var recentEntries: List<WatchEntry> = emptyList()
    private var groupCounts: Map<String, Int> = emptyMap()
    private var groupCountToken = 0
    private val groupCountCache = HashMap<String, Map<String, Int>>()
    private var hideEmptyGroups = true
    private var recentStamp = ""
    private val favoriteIds = mutableSetOf<String>()

    private val epgCache = java.util.concurrent.ConcurrentHashMap<String, EpgNowNext>()
    private val groupClockFormat = SimpleDateFormat("MM/dd/yyyy  hh:mm a", Locale.US)
    private var groupClockMinute = Long.MIN_VALUE
    private var guideRowsTall = false
    private var lastLabelShiftScroll = Int.MIN_VALUE

    private fun rememberEpg(channelId: String, epg: EpgNowNext) {
        epgCache[channelId] = epg
        if (epgCache.size <= EPG_CACHE_CAP) return
        val drop = epgCache.size - (EPG_CACHE_CAP * 3 / 4)
        val keys = epgCache.keys.take(drop)
        for (key in keys) epgCache.remove(key)
    }

    private val lineupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        showBlank()
        applySavedLineup()
    }

    private lateinit var vpnUi: VpnUi

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) AppTunnel.connect(this)
        else if (::vpnUi.isInitialized) vpnUi.denied()
    }

    private val focusHandler = Handler(Looper.getMainLooper())
    private var categoryLoad: Runnable? = null
    private var epgWait: Runnable? = null
    private var epgPrefetchWait: Runnable? = null
    private val categoryAdapter = CategoryAdapter(
        onSelect = { category, immediate -> selectCategory(category, immediate) },
        detail = { cardDetail(it) },
        icon = { cardIcon(it) },
        onHold = { offerGroupLock(it) },
        locked = { GroupLock.locked(activeConfig, it.id) }
    )
    private val channelAdapter = ChannelAdapter(
        onClick = { play(it) },
        onLongClick = { channel ->
            showRowOptions(channel)
            true
        },
        onFocused = { showChannelDetail(it) },
        onMove = { channel, direction -> moveFavorite(channel, direction) },
        isFavorite = { Favorites.matches(it, favoriteIds) },
        onFavorite = { toggleGuideFavorite(it) },
        onRecord = { offerRecording(it) },
        epgProvider = { epgCache[it.id] }
    ).also { adapter ->
        adapter.continueLabel = { continueText(it) }
    }
    private lateinit var programAdapter: ProgramGuideAdapter
    private var guideJob: Job? = null
    private var guideScrollSync = false
    private val halfHourMs = 30L * 60L * 1000L
    private var guideHeaderHalfHour = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
        programAdapter = ProgramGuideAdapter(
            context = this,
            onPlay = { channel, program -> playFromGuide(channel, program) },
            onScroll = { scrollProgramGuide(it) },
            onFocus = { showChannelDetail(it) }
        )
        programAdapter.preferKeepPx = {
            programAdapter.pxFor(com.olympus.iptv.playlist.EpgUtils.now())
        }
        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        touchPrimary = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)
        if (touchPrimary) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
            listOf(
                binding.cardAdminRefresh, binding.cardAdminWeb, binding.cardAdminEpg, binding.cardAdminVpn,
                binding.refreshNowCard, binding.refreshIntervalCard
            ).forEach { card ->
                card.isFocusableInTouchMode = false
            }
        }
        wireHomeContinue()
        paintQueueChip()

        binding.categoryList.layoutManager = if (touchPrimary) {
            LinearLayoutManager(this)
        } else {
            androidx.recyclerview.widget.GridLayoutManager(this, CARD_SPAN)
        }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter
        binding.channelList.adapter = channelAdapter
        binding.programList.layoutManager = LinearLayoutManager(this)
        binding.programList.adapter = programAdapter
        binding.programList.itemAnimator = null
        binding.categoryList.itemAnimator = null
        binding.channelList.itemAnimator = null
        binding.categoryList.setHasFixedSize(true)
        binding.channelList.setHasFixedSize(true)
        binding.categoryList.setItemViewCacheSize(if (touchPrimary) 8 else 12)
        binding.channelList.setItemViewCacheSize(if (touchPrimary) 12 else 24)
        if (!touchPrimary) {
            (binding.categoryList.layoutManager as androidx.recyclerview.widget.GridLayoutManager)
                .initialPrefetchItemCount = CARD_SPAN * 2
        } else {
            (binding.categoryList.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 6
        }
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
            startActivity(QueueActivity.intent(this, saved.id))
        }
        binding.btnFresh.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(FreshActivity.intent(this, saved.id))
        }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnRecord.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(RecordActivity.intent(this, saved.id))
        }
        binding.btnRemind.setOnClickListener {
            startActivity(RemindersActivity.intent(this))
        }
        binding.guideNow.setOnClickListener { jumpGuideToNow() }
        binding.btnParental.setOnClickListener {
            val saved = playlist ?: return@setOnClickListener
            startActivity(com.olympus.iptv.ui.epg.ParentalActivity.intent(this, saved.id))
        }
        binding.btnAdmin.setOnClickListener {
            if (binding.adminPane.visibility == View.VISIBLE) showBlank() else showAdminPane()
        }
        binding.cardAdminRefresh.setOnClickListener {
            if (playlist == null) return@setOnClickListener
            showRefreshPane()
        }
        binding.cardAdminWeb.setOnClickListener { showDevicePane() }
        binding.cardAdminEpg.setOnClickListener {
            if (source == null) return@setOnClickListener
            openEpgEdits()
        }
        vpnUi = VpnUi(this, binding) { vpnConsent.launch(it) }
        binding.cardAdminVpn.setOnClickListener { showVpnPane() }
        binding.vpnBack.setOnClickListener { showAdminPane(binding.cardAdminVpn) }
        binding.refreshBack.setOnClickListener { showAdminPane(binding.cardAdminRefresh) }
        binding.deviceBack.setOnClickListener { showAdminPane(binding.cardAdminWeb) }
        binding.deviceRemovePlaylist.setOnClickListener { confirmRemovePlaylist() }
        binding.refreshNowCard.setOnClickListener { refreshPlaylist(quiet = true) }
        binding.refreshIntervalCard.setOnClickListener { cycleRefreshInterval() }
        binding.adminQuietToggle.setOnClickListener { toggleAdminQuietHours() }
        binding.adminQuietSchedule.setOnClickListener { cycleAdminQuietSchedule() }
        binding.adminBackupLink.setOnClickListener { openBackupShortcut() }
        binding.btnLastGroup.setOnClickListener { openLastGroup() }
        showRefreshInterval(refreshMinutes)
        binding.btnAbout.setOnClickListener {
            if (binding.aboutPane.visibility == View.VISIBLE) showBlank() else showAboutPane()
        }
        binding.btnFavoritesSetup.setOnClickListener { openFavoritesSetup() }
        binding.tabLive.isSelected = true
        binding.navRail.post { tightenNavRail() }
        showLoading(true)

        lifecycleScope.launch {
            val account = resolveAccount() ?: return@launch
            bootstrap(account)
        }
        } catch (error: Throwable) {
            com.olympus.iptv.AppLog.error("Guide", "The guide failed to open", error)
            val message = android.widget.TextView(this)
            message.setTextColor(getColor(R.color.text_primary))
            message.text = getString(R.string.favorites_setup_guide_failed)
            message.setPadding(48, 48, 48, 48)
            setContentView(message)
        }
    }

    private suspend fun resolveAccount(): SavedPlaylist? {
        val app = application as OlympusIptvApp
        val (saved, favoritesReady) = withContext(Dispatchers.IO) { app.playlistStore.opening() }
        if (saved == null || saved.email.isNullOrBlank()) {
            startActivity(Intent(this, com.olympus.iptv.ui.login.LoginActivity::class.java))
            finish()
            overridePendingTransition(0, 0)
            return null
        }
        val ready = favoritesReady ?: withContext(Dispatchers.IO) {
            val count = app.catalogDb.bucketCounts(saved.id).firstOrNull() ?: 0
            val already = count > 0
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

    private suspend fun bootstrap(savedIn: SavedPlaylist) {
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
        groupCountriesReady = false
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
                if (!isDestroyed) binding.deviceIdValue.text = deviceId
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
                libraryJob.cancel()
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
            if (!isDestroyed && !intent.getBooleanExtra(EXTRA_SKIP_RESUME, false)) {
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
        } catch (error: Throwable) {
            com.olympus.iptv.AppLog.error("Guide", "The guide failed to open", error)
            if (!isDestroyed) {
                android.widget.Toast.makeText(this, R.string.favorites_setup_guide_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncSharedLineup() {
        val saved = playlist ?: return
        val email = saved.email?.trim().orEmpty()
        if (email.isBlank()) return
        val app = application as OlympusIptvApp
        app.appScope.launch {
            val client = LineupClient(this@BrowseActivity)
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

    private suspend fun publishSharedCatalog(
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

    private fun sameLineupEdits(left: LineupConfig, right: LineupConfig): Boolean {
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

    private fun applyFavorites(ids: List<String>) {
        invalidateTopWatchedCache()
        favoriteOrder.clear()
        favoriteOrder.addAll(ids)
        favoriteIds.clear()
        favoriteIds.addAll(ids)
        warmFavoriteAndRecentEpg()
        updateGroupClock(force = true)
    }

    private fun updateGroupClock(force: Boolean = false) {
        if (!::binding.isInitialized) return
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

    private fun loadSavedBuckets(playlistId: String, db: CatalogDb) {
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

    private suspend fun replaceBucket(
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

    private fun libraryHasItems(catalog: LoadedPlaylist): Boolean {
        return catalog.liveChannels.isNotEmpty() ||
            catalog.vodChannels.isNotEmpty() ||
            catalog.seriesChannels.orEmpty().isNotEmpty()
    }

    private fun persistLibrary(db: CatalogDb, playlistId: String, catalog: LoadedPlaylist) {
        db.insertMissing(playlistId, CatalogDb.LIVE, catalog.liveCategories, catalog.liveChannels)
        db.insertMissing(playlistId, CatalogDb.VOD, catalog.vodCategories, catalog.vodChannels)
        db.insertMissing(
            playlistId,
            CatalogDb.SERIES,
            catalog.seriesCategories.orEmpty(),
            catalog.seriesChannels.orEmpty()
        )
    }

    private fun storeBatch(
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

    private suspend inline fun <T> refreshResult(crossinline block: suspend () -> T): T? {
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun <T> pipeBatches(
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

    private fun refreshPlaylist(quiet: Boolean = false) {
        val saved = playlist ?: return
        val app = application as OlympusIptvApp
        if (app.catalogRefresh?.isActive == true) {
            if (touchPrimary && ::binding.isInitialized && quiet) {
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
                        this@BrowseActivity,
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

    private fun ensureRefreshLoop() {
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

    private fun showVpnPane() {
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
        binding.devicePane.visibility = View.GONE
        binding.aboutPane.visibility = View.GONE
        binding.adminPane.visibility = View.GONE
        binding.refreshPane.visibility = View.GONE
        binding.vpnPane.visibility = View.VISIBLE
        hidePreviewChrome()
        vpnUi.onShow()
    }

    private fun showRefreshPane() {
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
        binding.devicePane.visibility = View.GONE
        binding.aboutPane.visibility = View.GONE
        binding.adminPane.visibility = View.GONE
        binding.vpnPane.visibility = View.GONE
        binding.refreshPane.visibility = View.VISIBLE
        hidePreviewChrome()
        showRefreshInterval(refreshMinutes)
        binding.refreshNowCard.post { binding.refreshNowCard.requestFocus() }
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val minutes = withContext(Dispatchers.IO) { app.playlistStore.refreshIntervalMinutes() }
            refreshMinutes = minutes
            if (!isDestroyed && binding.refreshPane.visibility == View.VISIBLE) showRefreshInterval(minutes)
        }
    }

    private fun showRefreshStatus(text: String) {
        if (!::binding.isInitialized || isDestroyed) return
        binding.refreshStatus.text = text
    }

    private fun showRefreshInterval(minutes: Int) {
        binding.refreshIntervalValue.text = getString(R.string.refresh_every, intervalLabel(minutes))
    }

    private fun cycleRefreshInterval() {
        val choices = PlaylistStore.REFRESH_INTERVALS
        val index = choices.indexOf(refreshMinutes).let { if (it < 0) 1 else it }
        val next = choices[(index + 1) % choices.size]
        refreshMinutes = next
        showRefreshInterval(next)
        val app = application as OlympusIptvApp
        app.appScope.launch { app.playlistStore.saveRefreshIntervalMinutes(next) }
    }

    private fun intervalLabel(minutes: Int): String = when (minutes) {
        30 -> getString(R.string.refresh_30)
        60 -> getString(R.string.refresh_60)
        120 -> getString(R.string.refresh_120)
        360 -> getString(R.string.refresh_360)
        720 -> getString(R.string.refresh_720)
        1440 -> getString(R.string.refresh_1440)
        else -> getString(R.string.refresh_60)
    }

    private fun absorbNewCategories(
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

    private fun queueNewChannels(live: List<Channel>, vod: List<Channel>, series: List<Channel>) {
        if (live.isNotEmpty()) pendingLive.addAll(live)
        if (vod.isNotEmpty()) pendingVod.addAll(vod)
        if (series.isNotEmpty()) pendingSeries.addAll(series)
        deliverPendingChannels()
    }

    private fun deliverPendingChannels() {
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

    private fun flushGroupRefresh() {
        if (isDestroyed) return
        if (groupsNeedRefresh && source != null && binding.groupColumn.visibility == View.VISIBLE) {
            groupsNeedRefresh = false
            render(restoreFocus = false, reloadChannels = false)
        }
        if (binding.groupColumn.visibility == View.VISIBLE) deliverPendingChannels()
    }

    private fun publishAdded(delta: CatalogDb.Added, bucket: String) {
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
                    binding.devicePane.visibility != View.VISIBLE &&
                    binding.aboutPane.visibility != View.VISIBLE &&
                    binding.adminPane.visibility != View.VISIBLE &&
                    binding.blankPane.visibility != View.VISIBLE &&
                    binding.refreshPane.visibility != View.VISIBLE &&
                    binding.vpnPane.visibility != View.VISIBLE
                if (bucket == CatalogDb.VOD) moviesPending = false
                if (bucket == CatalogDb.SERIES) showsPending = false
                showCatalog(next, config, openGuide = openGuide && next.liveChannels.isNotEmpty())
                showLoading(false)
            }
        }
    }

    private fun mergeCategories(current: List<com.olympus.iptv.data.Category>, extra: List<com.olympus.iptv.data.Category>): List<com.olympus.iptv.data.Category> {
        if (extra.isEmpty()) return current
        val known = current.map { it.id }.toSet()
        return current + extra.filter { it.id !in known }
    }

    private fun onMovieCount(count: Int) {
        movieCount = count
        val now = SystemClock.uptimeMillis()
        if (now - lastMoviePost < 400 && count % 500 != 0) return
        lastMoviePost = now
        runOnUiThread { publishLibraryStatus() }
    }

    private fun onMoviePercent(percent: Int) {
        moviePercent = percent
        runOnUiThread { publishLibraryStatus() }
    }

    private fun onShowCount(count: Int) {
        showCount = count
        val now = SystemClock.uptimeMillis()
        if (now - lastShowPost < 400 && count % 500 != 0) return
        lastShowPost = now
        runOnUiThread { publishLibraryStatus() }
    }

    private fun onShowPercent(percent: Int) {
        showPercent = percent
        runOnUiThread { publishLibraryStatus() }
    }

    private fun publishLibraryStatus() {
        if (!::binding.isInitialized || isDestroyed) return
        binding.libraryStatusText.text = when (section) {
            GuideSection.MOVIES -> movieStatus()
            GuideSection.SHOWS -> showStatus()
            else -> movieStatus()
        }
    }

    private fun movieStatus(): String {
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

    private fun showStatus(): String {
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

    private fun showCatalog(fetched: LoadedPlaylist, config: LineupConfig, openGuide: Boolean) {
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
            binding.devicePane.visibility == View.VISIBLE ||
            binding.aboutPane.visibility == View.VISIBLE ||
            binding.adminPane.visibility == View.VISIBLE ||
            binding.blankPane.visibility == View.VISIBLE ||
            binding.refreshPane.visibility == View.VISIBLE ||
            binding.vpnPane.visibility == View.VISIBLE
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

    private fun publishGuide() {
        val app = application as OlympusIptvApp
        app.guidePlaylistId = playlist?.id
        binding.btnSearch.isEnabled = true
        binding.btnAdmin.isEnabled = true
    }

    private fun offerRecording(channel: Channel) {
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
            RecordPrompt.show(this@BrowseActivity, saved.id, channel, epg.now, epg.next)
        }
    }

    private fun openSearch() {
        if (source == null) return
        startActivity(Intent(this, SearchActivity::class.java))
    }

    private fun indexSection(sectionNow: GuideSection, channels: List<Channel>) {
        if (channels.isEmpty()) return
        when (sectionNow) {
            GuideSection.TV -> if (liveIndexing || liveByCategory.isNotEmpty()) return
            GuideSection.MOVIES -> if (vodIndexing || vodByCategory.isNotEmpty()) return
            GuideSection.SHOWS -> if (seriesIndexing || seriesByCategory.isNotEmpty()) return
        }
        when (sectionNow) {
            GuideSection.TV -> liveIndexing = true
            GuideSection.MOVIES -> vodIndexing = true
            GuideSection.SHOWS -> seriesIndexing = true
        }
        val generation = when (sectionNow) {
            GuideSection.TV -> ++liveIndexGeneration
            GuideSection.MOVIES -> ++vodIndexGeneration
            GuideSection.SHOWS -> ++seriesIndexGeneration
        }
        lifecycleScope.launch(Dispatchers.Default) {
            val indexed = indexChannels(channels)
            withContext(Dispatchers.Main) {
                if (isDestroyed || generation != generationFor(sectionNow)) return@withContext
                when (sectionNow) {
                    GuideSection.TV -> {
                        liveByCategory = indexed.first
                        liveById = indexed.second
                        liveIndexing = false
                    }
                    GuideSection.MOVIES -> {
                        vodByCategory = indexed.first
                        vodById = indexed.second
                        vodIndexing = false
                    }
                    GuideSection.SHOWS -> {
                        seriesByCategory = indexed.first
                        seriesById = indexed.second
                        seriesIndexing = false
                    }
                }
                val showsNeedMovies = sectionNow == GuideSection.MOVIES &&
                    section == GuideSection.SHOWS &&
                    source?.seriesChannels.isNullOrEmpty()
                if (binding.groupColumn.visibility == View.VISIBLE && (section == sectionNow || showsNeedMovies)) {
                    showChannels()
                }
            }
        }
    }

    private fun generationFor(sectionNow: GuideSection): Int = when (sectionNow) {
        GuideSection.TV -> liveIndexGeneration
        GuideSection.MOVIES -> vodIndexGeneration
        GuideSection.SHOWS -> seriesIndexGeneration
    }

    private fun indexChannels(channels: List<Channel>): Pair<Map<String, List<Channel>>, Map<String, Channel>> {
        val byCategory = HashMap<String, MutableList<Channel>>()
        val byId = HashMap<String, Channel>(channels.size)
        for (channel in channels) {
            byId[channel.id] = channel
            val key = channel.categoryId ?: channel.groupTitle ?: continue
            byCategory.getOrPut(key) { ArrayList() }.add(channel)
        }
        return byCategory to byId
    }

    private fun ensureSectionIndex(sectionNow: GuideSection) {
        val data = source ?: return
        when (sectionNow) {
            GuideSection.TV -> if (liveByCategory.isEmpty()) indexSection(GuideSection.TV, data.liveChannels)
            GuideSection.MOVIES -> if (vodByCategory.isEmpty()) indexSection(GuideSection.MOVIES, data.vodChannels)
            GuideSection.SHOWS -> {
                val series = data.seriesChannels.orEmpty()
                if (series.isNotEmpty()) {
                    if (seriesByCategory.isEmpty()) indexSection(GuideSection.SHOWS, series)
                } else if (vodByCategory.isEmpty()) {
                    indexSection(GuideSection.MOVIES, data.vodChannels)
                }
            }
        }
    }

    private fun applySavedLineup() {
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

    private fun tightenNavRail() {
        val inner = binding.navRail.getChildAt(0) as? android.view.ViewGroup ?: return
        val labels = listOf(
            binding.btnSearch,
            binding.tabLive,
            binding.tabVod,
            binding.tabShows,
            binding.btnRecord,
            binding.btnAdmin,
            binding.refreshLine,
            binding.btnAbout
        )
        var needed = 0
        val density = resources.displayMetrics.density
        val icon = 32 * density
        val measured = labels
        for (label in measured) {
            if (label.visibility != View.VISIBLE) continue
            val text = label.text?.toString().orEmpty()
            if (text.isEmpty()) continue
            val reach = inner.paddingLeft + label.paddingLeft + icon +
                label.paint.measureText(text) + label.paddingRight + inner.paddingRight
            if (reach > needed) needed = reach.toInt()
        }
        if (binding.playlistTitle.visibility == View.VISIBLE) {
            val title = binding.playlistTitle
            val header = inner.paddingLeft + title.paddingLeft +
                title.paint.measureText(title.text?.toString().orEmpty()) +
                title.paddingRight + inner.paddingRight
            if (header > needed) needed = header.toInt()
        }
        if (needed <= 0) return
        val params = binding.navRail.layoutParams
        if (params.width == needed) return
        params.width = needed
        binding.navRail.layoutParams = params
    }

    private fun showDashboardAddress() {
        showDashboardUrl(LineupClient.peekDashboardUrl() ?: com.olympus.iptv.portal.DashboardHost.url())
        lifecycleScope.launch {
            val url = runCatching { LineupClient(this@BrowseActivity).dashboardAddress() }.getOrNull()
            if (url.isNullOrBlank() || isDestroyed) return@launch
            showDashboardUrl(url)
        }
    }

    private fun openEpgEdits() {
        val saved = playlist ?: return
        lineupLauncher.launch(EpgEditsActivity.intent(this, saved.id, kind, saved.includeVod))
    }

    private enum class GuideSection { TV, MOVIES, SHOWS }

    private var section = GuideSection.TV
    private var returnToGuide = true

    private fun rememberReturnScreen() {
        if (binding.groupColumn.visibility == View.VISIBLE) returnToGuide = true
        else if (binding.blankPane.visibility == View.VISIBLE) returnToGuide = false
    }

    private fun backFromOverlay() {
        val rail = when {
            binding.aboutPane.visibility == View.VISIBLE -> binding.btnAbout
            binding.adminPane.visibility == View.VISIBLE -> binding.btnAdmin
            else -> sectionButton()
        }
        showMenu(GuideMenu.SETTINGS)
        if (returnToGuide && loaded != null) showGuide() else showBlank()
        rail.post { rail.requestFocus() }
    }

    private fun goBackOneScreen() {
        if (binding.vpnPane.visibility == View.VISIBLE) {
            showAdminPane(binding.cardAdminVpn)
            return
        }
        if (binding.refreshPane.visibility == View.VISIBLE) {
            showAdminPane(binding.cardAdminRefresh)
            return
        }
        if (binding.devicePane.visibility == View.VISIBLE) {
            showAdminPane(binding.cardAdminWeb)
            return
        }
        if (binding.adminPane.visibility == View.VISIBLE ||
            binding.aboutPane.visibility == View.VISIBLE ||
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

    private fun openSection(next: GuideSection) {
        if (next == section && binding.groupColumn.visibility == View.VISIBLE && guideMenu == GuideMenu.GROUPS) {
            armGroupFocus()
            focusGroups()
            return
        }
        setSection(next)
    }

    private fun setSection(next: GuideSection) {
        landOnOpened = false
        guideMenu = GuideMenu.GROUPS
        previousMenu = GuideMenu.GROUPS
        armGroupFocus()
        // Prefer the Continue strip when Movies/Shows have something to resume.
        landOnContinue = next != GuideSection.TV
        section = next
        if (next != GuideSection.SHOWS) {
            episodePrefetchJob?.cancel()
            episodePrefetchIds = emptyList()
        }
        applyChannelLayout()
        kind = if (next == GuideSection.TV) ContentKind.LIVE else ContentKind.VOD
        selectedCategoryId = PlaylistLoader.FAVORITES_ID
        showGuide()
        binding.tabLive.isSelected = next == GuideSection.TV
        binding.tabVod.isSelected = next == GuideSection.MOVIES
        binding.tabShows.isSelected = next == GuideSection.SHOWS
        rememberGuideSection(next)
        if (renderedSection == next && loaded != null) {
            showGuide()
            paintHomeContinue()
            if (landOnContinue && binding.homeContinue.visibility == View.VISIBLE) {
                focusHomeContinue()
            } else {
                focusGroups()
            }
            return
        }
        categoryAdapter.submit(emptyList(), null)
        channelAdapter.submit(emptyList())
        binding.groupColumn.post { render(restoreFocus = true) }
    }

    private fun rememberGuideSection(next: GuideSection) {
        val key = when (next) {
            GuideSection.TV -> "live"
            GuideSection.MOVIES -> "vod"
            GuideSection.SHOWS -> "series"
        }
        (application as OlympusIptvApp).appScope.launch {
            runCatching { (application as OlympusIptvApp).playlistStore.saveLastGuideSection(key) }
        }
    }

    private fun focusHomeContinue() {
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

    private fun showDevicePane() {
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
        binding.refreshPane.visibility = View.GONE
        binding.vpnPane.visibility = View.GONE
        binding.aboutPane.visibility = View.GONE
        binding.adminPane.visibility = View.GONE
        binding.devicePane.visibility = View.VISIBLE
        hidePreviewChrome()
        binding.deviceBack.post { binding.deviceBack.requestFocus() }
        val knownEmail = playlist?.email?.trim().orEmpty()
        if (knownEmail.isNotBlank()) binding.deviceEmailValue.text = knownEmail
        showDashboardUrl(LineupClient.peekDashboardUrl() ?: com.olympus.iptv.portal.DashboardHost.url())
        lifecycleScope.launch {
            if (knownEmail.isBlank()) {
                val email = (application as OlympusIptvApp).playlistStore.session.first()?.email?.trim().orEmpty()
                if (email.isNotBlank() && binding.devicePane.visibility == View.VISIBLE) {
                    binding.deviceEmailValue.text = email
                }
            }
            val url = runCatching { LineupClient(this@BrowseActivity).dashboardAddress() }.getOrNull()
            if (!url.isNullOrBlank() && binding.devicePane.visibility == View.VISIBLE) {
                showDashboardUrl(url)
            }
        }
    }

    private fun showDashboardUrl(url: String) {
        binding.deviceUrlValue.text = url
        binding.deviceUrlValue.setTextColor(getColor(R.color.accent))
    }

    private fun confirmRemovePlaylist() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.device_remove_playlist_title)
            .setMessage(R.string.device_remove_playlist_message)
            .setPositiveButton(R.string.device_remove_playlist_confirm) { _, _ -> removePlaylistAndRestart() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removePlaylistAndRestart() {
        val app = application as OlympusIptvApp
        binding.deviceRemovePlaylist.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { AppTunnel.disconnect(this@BrowseActivity) }
                runCatching { stopService(Intent(this@BrowseActivity, RecordingService::class.java)) }
                app.playlistStore.resetDevice()
                app.catalogDb.wipeAll()
                ReminderStore.clear(this@BrowseActivity)
                EpisodeCache.clear(filesDir)
                runCatching { File(getExternalFilesDir(null), "recordings").deleteRecursively() }
                runCatching { File(filesDir, "watchmode-week.json").delete() }
                runCatching { File(filesDir, "fresh-cards.json").delete() }
            }
            startActivity(
                Intent(this@BrowseActivity, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun showGuide() {
        binding.btnAdmin.isSelected = false
        binding.btnAbout.isSelected = false
        binding.devicePane.visibility = View.GONE
        binding.aboutPane.visibility = View.GONE
        binding.adminPane.visibility = View.GONE
        binding.refreshPane.visibility = View.GONE
        binding.vpnPane.visibility = View.GONE
        binding.blankPane.visibility = View.GONE
        binding.groupColumn.visibility = View.VISIBLE
        applyGuideColumns()
        binding.tabLive.isSelected = section == GuideSection.TV
        binding.tabVod.isSelected = section == GuideSection.MOVIES
        binding.tabShows.isSelected = section == GuideSection.SHOWS
        updateGroupClock(force = true)
    }

    private fun showBlank() {
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
        binding.devicePane.visibility = View.GONE
        binding.aboutPane.visibility = View.GONE
        binding.adminPane.visibility = View.GONE
        binding.refreshPane.visibility = View.GONE
        binding.vpnPane.visibility = View.GONE
        binding.blankPane.visibility = View.VISIBLE
        hidePreviewChrome()
        updateGroupClock()
    }

    private fun showAboutPane() {
        rememberReturnScreen()
        epgPrefetchJob?.cancel()
        binding.btnAbout.isSelected = true
        binding.btnAdmin.isSelected = false
        binding.tabLive.isSelected = false
        binding.tabVod.isSelected = false
        binding.tabShows.isSelected = false
        binding.groupColumn.visibility = View.GONE
        binding.contentDivider.visibility = View.GONE
        binding.contentColumn.visibility = View.GONE
        binding.programPane.visibility = View.GONE
        binding.blankPane.visibility = View.GONE
        binding.refreshPane.visibility = View.GONE
        binding.vpnPane.visibility = View.GONE
        binding.devicePane.visibility = View.GONE
        binding.adminPane.visibility = View.GONE
        binding.aboutPane.visibility = View.VISIBLE
        hidePreviewChrome()
        updateGroupClock()
        if (touchPrimary) {
            binding.mobileTopBar.visibility = View.VISIBLE
            binding.mobileBack.visibility = View.VISIBLE
            binding.mobileTitle.text = getString(R.string.nav_about)
            paintBottomTabs()
            paintMobileSettingsChips()
        }
    }

    private fun showAdminPane(focus: View = binding.cardAdminRefresh) {
        rememberReturnScreen()
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
        binding.refreshPane.visibility = View.GONE
        binding.vpnPane.visibility = View.GONE
        binding.devicePane.visibility = View.GONE
        binding.aboutPane.visibility = View.GONE
        binding.adminPane.visibility = View.VISIBLE
        hidePreviewChrome()
        updateGroupClock()
        paintAdminParentalSummary()
        if (touchPrimary) {
            binding.mobileTopBar.visibility = View.VISIBLE
            binding.mobileBack.visibility = View.VISIBLE
            binding.mobileTitle.text = getString(R.string.nav_admin)
            paintBottomTabs()
            paintMobileSettingsChips()
        }
        focus.post { if (focus.isShown) focus.requestFocus() }
    }

    private fun paintAdminParentalSummary() {
        if (!::binding.isInitialized) return
        lifecycleScope.launch {
            val prefs = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.devicePrefs()
            }
            if (isDestroyed) return@launch
            val locked = activeConfig.groupPins.orEmpty().size
            val base = if (locked <= 0) {
                getString(R.string.admin_parental_none, prefs.parentalTimeoutMinutes)
            } else {
                getString(R.string.admin_parental_summary, locked, prefs.parentalTimeoutMinutes)
            }
            val quiet = getString(if (prefs.quietHours) R.string.admin_quiet_on else R.string.admin_quiet_off)
            binding.adminParentalSummary.text = base + quiet
            binding.adminQuietToggle.text = getString(
                if (prefs.quietHours) R.string.quiet_hours_on else R.string.quiet_hours_off
            )
            binding.adminQuietSchedule.text = getString(
                R.string.admin_quiet_schedule,
                clockLabel(prefs.quietStartMinute),
                clockLabel(prefs.quietEndMinute)
            )
            paintParentalChip(locked)
            paintLastGroupChip()
            maybeShowBackupTip()
        }
    }

    private fun paintParentalChip(locked: Int = activeConfig.groupPins.orEmpty().size) {
        if (!::binding.isInitialized) return
        binding.btnParental.text = if (locked > 0) {
            getString(R.string.parental_chip_count, locked)
        } else {
            getString(R.string.parental_chip)
        }
    }

    private var lastGroupId: String? = null
    private var lastGroupName: String? = null

    private fun paintLastGroupChip() {
        if (!::binding.isInitialized) return
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

    private fun openLastGroup() {
        val id = lastGroupId ?: return
        val category = categoryAdapter.categories().firstOrNull { it.id == id }
            ?: Category(id = id, name = lastGroupName.orEmpty())
        Haptics.tick(binding.btnLastGroup)
        selectCategory(category, immediate = true)
    }

    private fun clockLabel(minuteOfDay: Int): String {
        val hour = (minuteOfDay / 60) % 24
        val minute = minuteOfDay % 60
        val am = hour < 12
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%d:%02d %s", h12, minute, if (am) "AM" else "PM")
    }

    private fun toggleAdminQuietHours() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val prefs = withContext(Dispatchers.IO) { store.devicePrefs() }
            withContext(Dispatchers.IO) {
                store.saveQuietHours(
                    !prefs.quietHours,
                    prefs.quietStartMinute,
                    prefs.quietEndMinute
                )
            }
            com.olympus.iptv.remind.ReminderClock.arm(this@BrowseActivity)
            paintAdminParentalSummary()
        }
    }

    private fun cycleAdminQuietSchedule() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val prefs = withContext(Dispatchers.IO) { store.devicePrefs() }
            val presets = listOf(
                22 * 60 to 7 * 60,
                23 * 60 to 7 * 60,
                0 to 6 * 60,
                21 * 60 to 8 * 60
            )
            val index = presets.indexOfFirst {
                it.first == prefs.quietStartMinute && it.second == prefs.quietEndMinute
            }
            val next = presets[(index + 1).coerceAtLeast(0) % presets.size]
            withContext(Dispatchers.IO) {
                store.saveQuietHours(prefs.quietHours || true, next.first, next.second)
            }
            com.olympus.iptv.remind.ReminderClock.arm(this@BrowseActivity)
            paintAdminParentalSummary()
        }
    }

    private fun openBackupShortcut() {
        Haptics.tick(binding.adminBackupLink)
        showDevicePane()
        Toast.makeText(this, R.string.admin_backup_detail, Toast.LENGTH_LONG).show()
    }

    private fun paintNowPlayingBar() {
        if (!touchPrimary || !::binding.isInitialized) return
        val app = application as OlympusIptvApp
        val channel = app.lastPlayed
        val match = channel != null && app.lastPlayedPlaylistId == playlist?.id
        if (!match || channel == null) {
            binding.nowPlayingBar.visibility = View.GONE
            return
        }
        binding.nowPlayingTitle.text = channel.name
        binding.nowPlayingBar.visibility =
            if (binding.groupColumn.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun resumeNowPlaying() {
        val app = application as OlympusIptvApp
        val channel = app.lastPlayed ?: return
        if (app.lastPlayedPlaylistId != playlist?.id) return
        Haptics.tick(binding.nowPlayingResume)
        play(channel, queueOverride = listOf(channel), zapMode = PlayerActivity.ZAP_LIST, rememberReturn = true)
    }

    private fun maybeShowBackupTip() {
        if (!touchPrimary) return
        val prefs = getSharedPreferences("olympus_tips", MODE_PRIVATE)
        if (prefs.getBoolean("backup_tip_shown", false)) return
        prefs.edit().putBoolean("backup_tip_shown", true).apply()
        Toast.makeText(this, R.string.backup_tip, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        (application as OlympusIptvApp).unwatchLineup(lineupWatch)
        AppTunnel.removeListener(vpnWatch)
        if (::vpnUi.isInitialized) vpnUi.stop()
        focusHandler.removeCallbacks(clockTick)
        runCatching { unregisterReceiver(systemClock) }
        previewPlayer?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        com.olympus.iptv.portal.DashboardHost.removeUrlListener(dashboardUrls)
        previewJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        refreshLoop?.cancel()
        refreshLoop = null
        super.onDestroy()
    }

    private val vpnWatch: (TunnelState) -> Unit = { state ->
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            showVpnStatus(state)
            offerVpnConsent()
        }
    }

    private fun offerVpnConsent() {
        if (!AppTunnel.needsConsent || isDestroyed) return
        val consent = android.net.VpnService.prepare(this)
        AppTunnel.needsConsent = false
        if (consent != null) vpnConsent.launch(consent) else AppTunnel.connect(this)
    }

    private fun showVpnStatus(state: TunnelState) {
        val detail = when (state) {
            is TunnelState.Off -> getString(R.string.admin_vpn_detail)
            is TunnelState.Connecting -> getString(R.string.vpn_connecting)
            is TunnelState.On -> getString(R.string.vpn_connected, state.label)
            is TunnelState.Reconnecting -> getString(R.string.vpn_reconnecting, state.label)
            is TunnelState.Failed -> state.message
        }
        binding.cardAdminVpnDetail.text = detail
        val chip = when (state) {
            is TunnelState.On -> getString(R.string.vpn_chip, state.label)
            is TunnelState.Reconnecting -> getString(R.string.vpn_chip_retry)
            else -> ""
        }
        binding.vpnChip.text = chip
        binding.vpnChip.visibility = if (chip.isEmpty()) View.GONE else View.VISIBLE
    }

    private val dashboardUrls: (String) -> Unit = { url ->
        runOnUiThread { if (!isDestroyed) showDashboardUrl(url) }
    }

    private var seenLibraryStamp = -1L
    private var holdChannelId: String? = null
    private val groupScrollOffset = HashMap<String, Int>()
    private var pinFailStreak = 0

    private val lineupWatch: (String) -> Unit = { id ->
        if (id == playlist?.id && !isDestroyed) {
            adoptRemoteLineup()
            reloadFavorites()
        }
    }

    private fun adoptRemoteLineup() {
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

    private fun visibleFocusId(): String? {
        val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot
        if (slot != null) return visibleChannels.getOrNull(slot.row)?.id
        val focus = currentFocus ?: return null
        val index = binding.channelList.findContainingViewHolder(focus)?.bindingAdapterPosition ?: -1
        return visibleChannels.getOrNull(index)?.id
    }

    private fun restoreHeldChannel() {
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

    override fun onStart() {
        super.onStart()
        (application as OlympusIptvApp).watchLineup(lineupWatch)
        if (!openingPlayer) adoptRemoteLineup()
        if (::vpnUi.isInitialized) vpnUi.start()
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

    private val systemClock = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateGroupClock(force = true)
            alignGuideToSystemClock()
        }
    }

    private val clockTick: Runnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            updateGroupClock(force = true)
            if (binding.programPane.visibility == View.VISIBLE) {
                tickGuideHeader()
                positionNowLine(programAdapter.scrollX)
            }
            val delayMs = 60_000L - (System.currentTimeMillis() % 60_000L)
            focusHandler.postDelayed(this, delayMs.coerceIn(1_000L, 60_000L))
        }
    }

    private fun alignGuideToSystemClock() {
        if (!::programAdapter.isInitialized || !::binding.isInitialized) return
        if (section != GuideSection.TV || binding.programPane.visibility != View.VISIBLE) {
            styleGuideHeader()
            return
        }
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val start = now / halfHourMs * halfHourMs
        programAdapter.setWindow(start, start + 8L * 60L * 60L * 1000L)
        programAdapter.scrollX = 0
        if (visibleChannels.isNotEmpty()) programAdapter.submit(visibleChannels)
        binding.programHeader.removeAllViews()
        buildProgramHeader()
        scrollProgramGuide(0)
        styleGuideHeader()
    }

    override fun onResume() {
        super.onResume()
        reloadRenamedGroups()
        val returningFromPlayer = openingPlayer
        openingPlayer = false
        reloadFavorites()
        reloadRecent()
        flushGroupRefresh()
        GroupLock.purgeExpired()
        paintNowPlayingBar()
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
                adoptRemoteLineup()
            }
            return
        }
        val stagedPins = (application as OlympusIptvApp).stagedLineup?.takeIf { it.first == playlist?.id }?.second
        if (stagedPins != null && stagedPins.groupPins != activeConfig.groupPins) {
            activeConfig = stagedPins
            categoryAdapter.refreshDetails()
        }
        if (loaded != null && currentFocus == null && binding.groupColumn.visibility == View.VISIBLE && !landOnOpened) {
            focusGroups()
        }
    }

    private fun reloadRenamedGroups() {
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

    private fun reloadRecent() {
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

    private fun sectionKey(sectionNow: GuideSection): String = when (sectionNow) {
        GuideSection.TV -> "live"
        GuideSection.MOVIES -> "vod"
        GuideSection.SHOWS -> "series"
    }

    private fun withRecent(rest: List<Category>): List<Category> {
        val recent = Category(PlaylistLoader.RECENT_ID, getString(R.string.recent_watched), kind)
        return listOf(recent) + withTopWatched(rest)
    }

    private fun withTopWatched(rest: List<Category>): List<Category> {
        if (favoriteIds.isEmpty() || recentEntries.isEmpty()) return rest
        val top = Category(PlaylistLoader.TOP_WATCHED_ID, getString(R.string.top_watched), kind)
        return listOf(top) + rest
    }

    private fun hasContinue(sectionNow: GuideSection): Boolean {
        if (sectionNow == GuideSection.TV) return false
        val wanted = sectionKey(sectionNow)
        return recentEntries.any { entrySection(it) == wanted && it.canResume() }
    }

    private fun withContinue(rest: List<Category>): List<Category> {
        if (!hasContinue(section)) return withRecent(rest)
        val head = Category(PlaylistLoader.CONTINUE_ID, getString(R.string.continue_group), kind)
        return listOf(head) + withRecent(rest)
    }

    private fun entrySection(entry: WatchEntry): String {
        val raw = entry.section?.trim().orEmpty()
        return if (raw.isEmpty()) "live" else raw
    }

    private fun continueText(channel: Channel): String? {
        val spot = if (!channel.seriesId.isNullOrBlank() && channel.streamUrl.isBlank()) {
            recentEntries.firstOrNull { it.seriesId == channel.seriesId && it.canResume() }
        } else {
            recentEntries.firstOrNull { it.channelId == channel.id && it.canResume() }
        } ?: return null
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

    private fun recentChannels(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
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

    private fun continueChannels(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
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

    private fun reloadFavorites() {
        val saved = playlist ?: return
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) { app.playlistStore.favorites(saved.id) }
            if (ids == favoriteOrder) return@launch
            applyFavorites(ids)
            if (selectedCategoryId == PlaylistLoader.FAVORITES_ID &&
                binding.groupColumn.visibility == View.VISIBLE
            ) {
                showChannels()
            }
        }
    }

    private var pinCategory: Category? = null
    private var pinOpens = false
    private var pinClearing = false
    private var pinDigits = ""
    private var pinKeyIndex = 4
    private var dialDigits = ""
    private val dialJump = Runnable { jumpDial() }
    private var guidePinWired = false

    private val guidePinKeys: List<View> by lazy {
        listOf(
            binding.guidePin1, binding.guidePin2, binding.guidePin3,
            binding.guidePin4, binding.guidePin5, binding.guidePin6,
            binding.guidePin7, binding.guidePin8, binding.guidePin9,
            binding.guidePinClearDigit, binding.guidePin0, binding.groupPinCancel
        )
    }

    private fun wireGuidePinPad() {
        if (guidePinWired) return
        guidePinWired = true
        val digits = listOf(
            binding.guidePin0 to '0',
            binding.guidePin1 to '1',
            binding.guidePin2 to '2',
            binding.guidePin3 to '3',
            binding.guidePin4 to '4',
            binding.guidePin5 to '5',
            binding.guidePin6 to '6',
            binding.guidePin7 to '7',
            binding.guidePin8 to '8',
            binding.guidePin9 to '9'
        )
        for ((view, digit) in digits) {
            view.setOnClickListener { appendGuidePinDigit(digit) }
        }
        binding.guidePinClearDigit.setOnClickListener { clearGuidePinDigit() }
        binding.groupPinCancel.setOnClickListener { cancelGroupPinToGroups() }
        binding.groupPinWrongOk.setOnClickListener { dismissWrongPinToGroups() }
        binding.groupPinScrim.setOnClickListener { /* absorb */ }
        for ((index, key) in guidePinKeys.withIndex()) {
            val col = index % 3
            val row = index / 3
            key.nextFocusLeftId = guidePinKeys[row * 3 + (col - 1).coerceAtLeast(0)].id
            key.nextFocusRightId = guidePinKeys[row * 3 + (col + 1).coerceAtMost(2)].id
            key.nextFocusUpId = guidePinKeys[(row - 1).coerceAtLeast(0) * 3 + col].id
            key.nextFocusDownId = guidePinKeys[(row + 1).coerceAtMost(3) * 3 + col].id
            key.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) pinKeyIndex = index
            }
        }
        binding.groupPinCancel.nextFocusDownId = binding.groupPinClear.id
        binding.guidePin0.nextFocusDownId = binding.groupPinClear.id
        binding.guidePinClearDigit.nextFocusDownId = binding.groupPinClear.id
        binding.groupPinClear.nextFocusUpId = binding.groupPinCancel.id
    }

    private fun showGroupPin(category: Category, open: Boolean) {
        wireGuidePinPad()
        pinCategory = category
        pinOpens = open
        pinClearing = false
        pinDigits = ""
        pinKeyIndex = 4
        binding.groupPinTitle.setText(if (open) R.string.group_pin_title else R.string.group_pin_set)
        binding.groupPinWrong.visibility = View.GONE
        paintGroupPin()
        paintGuidePinClear(category.id)
        binding.groupPinClear.setOnClickListener { beginGuidePinClear() }
        binding.guideShell.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (touchPrimary) {
            val params = binding.groupPin.layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                val margin = (16 * resources.displayMetrics.density).toInt()
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.marginStart = margin
                params.marginEnd = margin
                binding.groupPin.layoutParams = params
            }
            binding.mobileTopBar.visibility = View.VISIBLE
            binding.mobileBack.visibility = View.VISIBLE
        }
        binding.groupPinScrim.visibility = View.VISIBLE
        binding.groupPin.visibility = View.VISIBLE
        binding.groupPinWrong.visibility = View.GONE
        binding.groupPin.post { reclaimGuidePinFocus() }
    }

    private fun paintGuidePinClear(groupId: String = pinCategory?.id.orEmpty()) {
        val hasPin = !activeConfig.groupPins.orEmpty()[groupId].isNullOrBlank()
        binding.groupPinClear.visibility =
            if (hasPin && !pinClearing) View.VISIBLE else View.GONE
    }

    private fun beginGuidePinClear() {
        val category = pinCategory ?: return
        if (activeConfig.groupPins.orEmpty()[category.id].isNullOrBlank()) return
        pinClearing = true
        pinOpens = false
        pinDigits = ""
        pinKeyIndex = 4
        binding.groupPinTitle.setText(R.string.parental_clear_need_pin_one)
        paintGroupPin()
        paintGuidePinClear(category.id)
        binding.groupPinWrong.visibility = View.GONE
        reclaimGuidePinFocus()
    }

    private fun hideGroupPin() {
        pinCategory = null
        pinDigits = ""
        pinClearing = false
        binding.groupPin.visibility = View.GONE
        binding.groupPinScrim.visibility = View.GONE
        binding.groupPinWrong.visibility = View.GONE
        binding.guideShell.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun cancelGroupPinToGroups() {
        hideGroupPin()
        showMenu(GuideMenu.GROUPS)
        armGroupFocus()
        focusGroups()
    }

    private fun dismissWrongPinToGroups() {
        binding.groupPinWrong.visibility = View.GONE
        hideGroupPin()
        showMenu(GuideMenu.GROUPS)
        armGroupFocus()
        focusGroups()
    }

    private fun showWrongPinModal() {
        pinFailStreak += 1
        if (pinFailStreak >= 2) {
            val email = playlist?.email?.trim().orEmpty()
            if (email.isNotBlank() && PinAlert.configured()) {
                val app = application as OlympusIptvApp
                app.appScope.launch {
                    val deviceId = runCatching { app.playlistStore.deviceId() }.getOrDefault("")
                    PinAlert.notifyFailedUnlock(app, email, deviceId)
                }
            }
            pinFailStreak = 0
        }
        pinDigits = ""
        paintGroupPin()
        binding.groupPin.visibility = View.GONE
        binding.groupPinWrong.visibility = View.VISIBLE
        binding.groupPinWrongOk.post { binding.groupPinWrongOk.requestFocus() }
    }

    private fun paintGroupPin() {
        binding.groupPinDigits.text = "•".repeat(pinDigits.length).padEnd(4, '–')
    }

    private fun appendGuidePinDigit(digit: Char) {
        if (!digit.isDigit() || pinDigits.length >= 4) return
        pinDigits += digit
        paintGroupPin()
        if (pinDigits.length == 4) submitGroupPin()
    }

    private fun clearGuidePinDigit() {
        if (pinDigits.isEmpty()) return
        pinDigits = pinDigits.dropLast(1)
        paintGroupPin()
    }

    private fun reclaimGuidePinFocus() {
        if (binding.groupPin.visibility != View.VISIBLE) return
        val index = pinKeyIndex.coerceIn(0, guidePinKeys.lastIndex)
        if (!guidePinKeys[index].requestFocus()) {
            binding.guidePin5.requestFocus()
        }
    }

    private fun moveGuidePinFocus(dx: Int, dy: Int): Boolean {
        if (binding.groupPin.visibility != View.VISIBLE) return false
        val index = guidePinKeys.indexOfFirst { it === currentFocus }.let {
            if (it >= 0) it else pinKeyIndex
        }.coerceIn(0, guidePinKeys.lastIndex)
        val col = index % 3
        val row = index / 3
        val next = (row + dy).coerceIn(0, 3) * 3 + (col + dx).coerceIn(0, 2)
        pinKeyIndex = next
        guidePinKeys[next].requestFocus()
        return true
    }

    private fun focusInGuidePin(): Boolean {
        var view = currentFocus ?: return false
        while (true) {
            if (view === binding.groupPin || view === binding.groupPinWrong) return true
            view = view.parent as? View ?: return false
        }
    }

    private fun submitGroupPin() {
        val category = pinCategory ?: return
        if (pinClearing) {
            if (GroupLock.accepts(activeConfig, category.id, pinDigits)) {
                pinFailStreak = 0
                Haptics.tick(binding.groupPin)
                saveGroupPin(category.id, null)
                hideGroupPin()
                categoryAdapter.refreshDetails()
                paintParentalChip()
                Toast.makeText(this, R.string.pin_cleared_ok, Toast.LENGTH_SHORT).show()
                focusGroups()
            } else {
                Haptics.reject(binding.groupPin)
                showWrongPinModal()
            }
            return
        }
        if (!pinOpens) {
            saveGroupPin(category.id, pinDigits)
            hideGroupPin()
            categoryAdapter.refreshDetails()
            paintParentalChip()
            Haptics.tick(binding.groupPin)
            Toast.makeText(this, R.string.pin_set_ok, Toast.LENGTH_SHORT).show()
            focusGroups()
            return
        }
        if (GroupLock.accepts(activeConfig, category.id, pinDigits)) {
            pinFailStreak = 0
            Haptics.tick(binding.groupPin)
            lifecycleScope.launch {
                val minutes = withContext(Dispatchers.IO) {
                    (application as OlympusIptvApp).playlistStore.parentalTimeoutMinutes()
                }
                GroupLock.unlock(category.id, minutes)
                hideGroupPin()
                selectedCategoryId = category.id
                showChannels()
                openFullGuide()
            }
            return
        }
        Haptics.reject(binding.groupPin)
        showWrongPinModal()
    }

    private fun saveGroupPin(groupId: String, digits: String?) {
        val saved = playlist ?: return
        val pins = activeConfig.groupPins.orEmpty().toMutableMap()
        if (digits == null) {
            pins.remove(groupId)
            GroupLock.lock(groupId)
        } else {
            pins[groupId] = GroupLock.code(digits)
            GroupLock.lock(groupId)
        }
        val next = activeConfig.copy(
            groupPins = pins.ifEmpty { null },
            revision = System.currentTimeMillis()
        )
        activeConfig = next
        val app = application as OlympusIptvApp
        app.stagedLineup = saved.id to next
        app.appScope.launch {
            runCatching { app.playlistStore.saveLineup(saved.id, next) }
            val email = saved.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
            }
        }
    }

    private var pinAt = 0L

    private fun offerGroupLock(category: Category) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - pinAt < 500L) return
        pinAt = now
        if (category.id == PlaylistLoader.ALL_ID ||
            category.id == PlaylistLoader.FAVORITES_ID ||
            category.id == PlaylistLoader.RECENT_ID ||
            category.id == PlaylistLoader.CONTINUE_ID ||
            category.id == PlaylistLoader.TOP_WATCHED_ID
        ) return
        showGroupPin(category, open = false)
    }

    private fun onGroupPinKey(event: KeyEvent): Boolean {
        if (binding.groupPinWrong.visibility == View.VISIBLE) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B
            ) {
                dismissWrongPinToGroups()
            }
            return true
        }
        if (binding.groupPin.visibility != View.VISIBLE) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!focusInGuidePin()) reclaimGuidePinFocus()
            val digit = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                    ('0' + (event.keyCode - KeyEvent.KEYCODE_0))
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                    ('0' + (event.keyCode - KeyEvent.KEYCODE_NUMPAD_0))
                else -> null
            }
            if (digit != null) {
                appendGuidePinDigit(digit)
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                    clearGuidePinDigit()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                    cancelGroupPinToGroups()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return moveGuidePinFocus(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> return moveGuidePinFocus(0, 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> return moveGuidePinFocus(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return moveGuidePinFocus(1, 0)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    (currentFocus ?: guidePinKeys[pinKeyIndex.coerceIn(0, guidePinKeys.lastIndex)])
                        .performClick()
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL ->
                    return true
            }
            if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
                event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
            ) {
                return true
            }
        }
        if (!focusInGuidePin()) {
            reclaimGuidePinFocus()
            return true
        }
        return true
    }

    private fun onDialKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (currentFocus is android.widget.EditText) return false
        val inGuide = binding.contentColumn.visibility == View.VISIBLE &&
            (focusIsIn(binding.channelList) || focusIsIn(binding.programPane))
        if (!inGuide && dialDigits.isEmpty()) return false
        val digit = when (event.keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && inGuide) {
            if (dialDigits.length >= 4) dialDigits = ""
            dialDigits += digit.toString()
            binding.channelDialNumber.text = dialDigits
            binding.channelDialHint.visibility = View.GONE
            binding.channelDial.visibility = View.VISIBLE
            focusHandler.removeCallbacks(dialJump)
            focusHandler.postDelayed(dialJump, 1200L)
            return true
        }
        if (dialDigits.isEmpty()) return false
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            focusHandler.removeCallbacks(dialJump)
            jumpDial()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            clearDial()
            return true
        }
        return false
    }

    private fun jumpDial() {
        val typed = dialDigits
        if (typed.isEmpty()) return
        val onPrograms = focusIsIn(binding.programPane)
        val number = typed.toIntOrNull() ?: 0
        clearDial()
        val byNumber = visibleChannels.indexOfFirst { it.number == number }
        val index = if (byNumber >= 0) byNumber else number - 1
        if (number <= 0 || index !in visibleChannels.indices) {
            binding.channelDialNumber.text = typed
            binding.channelDialHint.text = getString(R.string.channel_missing)
            binding.channelDialHint.visibility = View.VISIBLE
            binding.channelDial.visibility = View.VISIBLE
            focusHandler.postDelayed({ binding.channelDial.visibility = View.GONE }, 900L)
            return
        }
        if (onPrograms) focusProgram(index, currentProgramColumn(index))
        else focusRowAt(binding.channelList, index, View.NO_ID)
    }

    private fun clearDial() {
        dialDigits = ""
        focusHandler.removeCallbacks(dialJump)
        binding.channelDial.visibility = View.GONE
        binding.channelDialHint.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onGroupPinKey(event)) return true
        if (onDialKey(event)) return true
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
                    if (binding.vpnPane.visibility == View.VISIBLE) binding.vpnBack.requestFocus()
                    else if (binding.adminPane.visibility == View.VISIBLE) binding.cardAdminRefresh.requestFocus()
                    else if (binding.devicePane.visibility == View.VISIBLE) binding.deviceBack.requestFocus()
                    else if (binding.refreshPane.visibility == View.VISIBLE) binding.refreshNowCard.requestFocus()
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
                    } else {
                        showMenu(GuideMenu.CHANNELS)
                        focusReturnChannel(slot?.row ?: focusedChannelRow())
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
                    if (!staysIn(binding.channelList, View.FOCUS_RIGHT)) return true
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
                                binding.homeContinuePlay.requestFocus()
                                return true
                            }
                            binding.shortcutRow.visibility == View.VISIBLE -> {
                                binding.btnFavoritesSetup.requestFocus()
                                return true
                            }
                        }
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && focusIsIn(binding.refreshPane)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    when (currentFocus) {
                        binding.refreshIntervalCard -> binding.refreshNowCard.requestFocus()
                        binding.refreshNowCard -> binding.refreshBack.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    when (currentFocus) {
                        binding.refreshBack -> binding.refreshNowCard.requestFocus()
                        binding.refreshNowCard -> binding.refreshIntervalCard.requestFocus()
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
            if (binding.vpnPane.visibility == View.VISIBLE) {
                binding.vpnBack.requestFocus()
            } else if (binding.adminPane.visibility == View.VISIBLE) {
                binding.cardAdminRefresh.requestFocus()
            } else if (binding.devicePane.visibility == View.VISIBLE) {
                binding.deviceBack.requestFocus()
            } else if (binding.refreshPane.visibility == View.VISIBLE) {
                binding.refreshNowCard.requestFocus()
            } else {
                focusGroups()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun favoriteMoveDirection(event: KeyEvent): Int? {
        val moved = event.isCtrlPressed || event.isAltPressed || event.isShiftPressed
        return when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP -> if (moved) -1 else null
            KeyEvent.KEYCODE_DPAD_DOWN -> if (moved) 1 else null
            else -> null
        }
    }

    private fun focusIsIn(root: View): Boolean {
        var current = currentFocus
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun containsView(root: View, child: View?): Boolean {
        var current = child
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun staysIn(list: View, direction: Int): Boolean {
        val focused = currentFocus ?: return false
        val next = focused.focusSearch(direction) ?: return false
        return containsView(list, next)
    }

    private fun channelHasMore(): Boolean = !pageExhausted

    private fun moveWithin(list: androidx.recyclerview.widget.RecyclerView, direction: Int, moreBelow: Boolean) {
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

    private fun channelStep(): Int {
        val grid = binding.channelList.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
        return if (grid != null && section != GuideSection.TV) grid.spanCount else 1
    }

    private fun applyChannelLayout() {
        val posters = section != GuideSection.TV
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

    private enum class GuideMenu { SETTINGS, GROUPS, CHANNELS, PROGRAMS }

    private var guideMenu = GuideMenu.SETTINGS
    private var previousMenu = GuideMenu.GROUPS
    private var navExpanded = true
    private val navLabels = HashMap<Int, CharSequence>()
    private var previewPlayer: ExoPlayer? = null
    private var previewChannelId: String? = null
    private var previewJob: Job? = null

    private fun setupMobileChrome() {
        binding.mobileTopBar.visibility = View.VISIBLE
        binding.mobileBottomTabs.visibility = View.VISIBLE
        binding.mobileSectionRow.visibility = View.GONE
        binding.mobileBack.setOnClickListener { onMobileBack() }
        binding.mobileTitle.setOnClickListener { /* section switching is the bottom tabs */ }
        binding.mobileAdmin.setOnClickListener { showAdminPane() }
        binding.mobileAbout.setOnClickListener { showAboutPane() }
        binding.mobileSearch.setOnClickListener { openSearch() }
        binding.groupsSearch.visibility = View.VISIBLE
        binding.groupsSearch.setOnClickListener { openSearch() }
        binding.groupsFilter.visibility = View.VISIBLE
        binding.groupsFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                categoryAdapter.setFilter(s?.toString().orEmpty())
            }
        })
        binding.nowPlayingResume.setOnClickListener { resumeNowPlaying() }
        binding.nowPlayingBar.setOnClickListener { resumeNowPlaying() }
        binding.groupsRefresh.isEnabled = true
        binding.groupsRefresh.setColorSchemeResources(R.color.accent)
        binding.groupsRefresh.setProgressBackgroundColorSchemeResource(R.color.bg_elevated)
        binding.groupsRefresh.setOnRefreshListener {
            Haptics.tick(binding.groupsRefresh)
            refreshPlaylist(quiet = true)
        }
        paintNowPlayingBar()
        val openLive = View.OnClickListener { openSection(GuideSection.TV) }
        val openMovies = View.OnClickListener { openSection(GuideSection.MOVIES) }
        val openShows = View.OnClickListener { openSection(GuideSection.SHOWS) }
        binding.mobileTabLive.setOnClickListener(openLive)
        binding.mobileTabMovies.setOnClickListener(openMovies)
        binding.mobileTabShows.setOnClickListener(openShows)
        binding.bottomTabLive.setOnClickListener(openLive)
        binding.bottomTabMovies.setOnClickListener(openMovies)
        binding.bottomTabShows.setOnClickListener(openShows)
        paintBottomTabs()
        paintMobileSettingsChips()
    }

    private fun paintBottomTabs() {
        if (!touchPrimary || !::binding.isInitialized) return
        val settingsOpen = binding.adminPane.visibility == View.VISIBLE ||
            binding.aboutPane.visibility == View.VISIBLE
        binding.bottomTabLive.isSelected = !settingsOpen && section == GuideSection.TV
        binding.bottomTabMovies.isSelected = !settingsOpen && section == GuideSection.MOVIES
        binding.bottomTabShows.isSelected = !settingsOpen && section == GuideSection.SHOWS
        binding.bottomTabLive.setTextColor(
            getColor(
                if (!settingsOpen && section == GuideSection.TV) R.color.accent else R.color.text_primary
            )
        )
        binding.bottomTabMovies.setTextColor(
            getColor(
                if (!settingsOpen && section == GuideSection.MOVIES) R.color.accent else R.color.text_primary
            )
        )
        binding.bottomTabShows.setTextColor(
            getColor(
                if (!settingsOpen && section == GuideSection.SHOWS) R.color.accent else R.color.text_primary
            )
        )
    }

    private fun paintMobileSettingsChips() {
        if (!touchPrimary || !::binding.isInitialized) return
        val adminOpen = binding.adminPane.visibility == View.VISIBLE
        val aboutOpen = binding.aboutPane.visibility == View.VISIBLE
        binding.mobileAdmin.isSelected = adminOpen
        binding.mobileAbout.isSelected = aboutOpen
        binding.mobileAdmin.setTextColor(
            getColor(if (adminOpen) R.color.accent else R.color.text_primary)
        )
        binding.mobileAbout.setTextColor(
            getColor(if (aboutOpen) R.color.accent else R.color.text_primary)
        )
    }

    private fun wireHomeContinue() {
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

    private fun wireHomeContinueFocusChain() {
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

    private fun paintQueueChip() {
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

    private fun enlargeTouchChips() {
        if (!touchPrimary) return
        val min = (48 * resources.displayMetrics.density).toInt()
        val padH = (16 * resources.displayMetrics.density).toInt()
        listOf(
            binding.btnFavoritesSetup, binding.btnQueue, binding.btnFresh,
            binding.btnRemind, binding.btnParental, binding.btnLastGroup,
            binding.mobileTabLive, binding.mobileTabMovies, binding.mobileTabShows,
            binding.mobileBack, binding.mobileAdmin, binding.mobileAbout, binding.mobileSearch,
            binding.groupsSearch,
            binding.bottomTabLive, binding.bottomTabMovies, binding.bottomTabShows
        ).forEach { chip ->
            chip.minHeight = min
            chip.setPadding(padH, chip.paddingTop, padH, chip.paddingBottom)
            chip.isFocusableInTouchMode = false
        }
    }

    private fun paintHomeContinue() {
        if (!::binding.isInitialized) return
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

    private fun openHomeContinue() {
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

    private fun openContinueGroup() {
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

    private fun onMobileBack() {
        when {
            binding.groupPinWrong.visibility == View.VISIBLE -> dismissWrongPinToGroups()
            binding.groupPin.visibility == View.VISIBLE -> cancelGroupPinToGroups()
            guideMenu == GuideMenu.CHANNELS || guideMenu == GuideMenu.PROGRAMS -> {
                rememberGroupScroll()
                showMenu(GuideMenu.GROUPS)
                armGroupFocus()
                focusGroups()
            }
            binding.devicePane.visibility == View.VISIBLE ||
                binding.aboutPane.visibility == View.VISIBLE ||
                binding.adminPane.visibility == View.VISIBLE ||
                binding.refreshPane.visibility == View.VISIBLE ||
                binding.vpnPane.visibility == View.VISIBLE -> {
                openSection(section)
            }
            else -> Unit
        }
    }

    private fun showMenu(next: GuideMenu) {
        if (next == GuideMenu.PROGRAMS && section != GuideSection.TV) return
        if (next == guideMenu) return
        if ((guideMenu == GuideMenu.CHANNELS || guideMenu == GuideMenu.PROGRAMS) &&
            next == GuideMenu.GROUPS
        ) {
            rememberGroupScroll()
        }
        previousMenu = guideMenu
        guideMenu = next
        val guideUp = binding.groupColumn.visibility == View.VISIBLE ||
            binding.contentColumn.visibility == View.VISIBLE ||
            binding.programPane.visibility == View.VISIBLE
        if (guideUp) applyGuideColumns()
    }

    private fun applyGuideColumns() {
        val live = section == GuideSection.TV
        val full = guideMenu == GuideMenu.CHANNELS || guideMenu == GuideMenu.PROGRAMS
        val showSettings = !full
        val showGroups = !full
        val showChannels = full
        val phoneChrome = touchPrimary
        // EPG pane only in PROGRAMS mode. CHANNELS (phones, or huge Live folders) is list-only.
        val showPrograms = live && full && !phoneChrome && guideMenu == GuideMenu.PROGRAMS
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
        binding.groupPills.visibility = View.GONE
        binding.contentDivider.visibility = if (showGroups && showChannels) View.VISIBLE else View.GONE
        binding.contentColumn.visibility = if (showChannels) View.VISIBLE else View.GONE
        binding.programPane.visibility = if (showPrograms) View.VISIBLE else View.GONE
        if (phoneChrome) {
            val pinOpen = binding.groupPin.visibility == View.VISIBLE ||
                binding.groupPinWrong.visibility == View.VISIBLE
            val settingsOpen = binding.adminPane.visibility == View.VISIBLE ||
                binding.aboutPane.visibility == View.VISIBLE ||
                binding.refreshPane.visibility == View.VISIBLE ||
                binding.vpnPane.visibility == View.VISIBLE ||
                binding.devicePane.visibility == View.VISIBLE
            binding.mobileBack.visibility = if (full || pinOpen || settingsOpen) View.VISIBLE else View.GONE
            binding.mobileSectionRow.visibility = View.GONE
            binding.mobileTitle.text = when {
                binding.adminPane.visibility == View.VISIBLE -> getString(R.string.nav_admin)
                binding.aboutPane.visibility == View.VISIBLE -> getString(R.string.nav_about)
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
        if (phoneChrome && showChannels) {
            // Full-width channel list — do not leave weight on a hidden EPG pane.
            content.width = 0
            content.weight = 1f
            programs.width = 0
            programs.weight = 0f
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
        previewPlayer?.playWhenReady = false
        styleGuideHeader()
        if (showPrograms) {
            if (full) fitChannelColumn()
            ensureProgramGuide()
        }
    }

    private fun setNavExpanded(expanded: Boolean) {
        if (expanded != navExpanded) {
            navExpanded = expanded
            val items = listOf(
                binding.btnSearch,
                binding.tabLive,
                binding.tabVod,
                binding.tabShows,
                binding.btnRecord,
                binding.btnAdmin,
                binding.btnAbout
            )
            for (view in items) {
                if (view.id !in navLabels) navLabels[view.id] = view.text
                view.text = if (expanded) navLabels[view.id] else ""
            }
            binding.playlistTitle.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        if (!expanded) {
            val params = binding.navRail.layoutParams
            val width = (72 * resources.displayMetrics.density).toInt()
            if (params.width != width) {
                params.width = width
                binding.navRail.layoutParams = params
            }
            return
        }
        tightenNavRail()
    }

    private fun syncGroupPills() {
        val row = binding.groupPillRow
        val categories = categoryAdapter.categories()
        val same = row.childCount == categories.size && categories.indices.all { index ->
            row.getChildAt(index).tag == categories[index].id
        }
        if (!same) {
            row.removeAllViews()
            val density = resources.displayMetrics.density
            val padH = (14 * density).toInt()
            val padV = (8 * density).toInt()
            val gap = (8 * density).toInt()
            for (category in categories) {
                val selected = category.id == selectedCategoryId
                row.addView(TextView(this).apply {
                    tag = category.id
                    text = com.olympus.iptv.data.NameImages.strip(category.name)
                    textSize = 13f
                    isFocusable = false
                    isSelected = selected
                    setPadding(padH, padV, padH, padV)
                    setTextColor(getColor(if (selected) R.color.bg else R.color.text_primary))
                    setBackgroundResource(R.drawable.bg_group_pill)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = gap }
                })
            }
        } else {
            for (index in categories.indices) {
                val pill = row.getChildAt(index) as TextView
                val selected = categories[index].id == selectedCategoryId
                pill.isSelected = selected
                pill.setTextColor(getColor(if (selected) R.color.bg else R.color.text_primary))
            }
        }
        val selectedIndex = categories.indexOfFirst { it.id == selectedCategoryId }
        if (selectedIndex >= 0) {
            row.getChildAt(selectedIndex)?.let { child ->
                binding.groupPills.post { binding.groupPills.smoothScrollTo(child.left, 0) }
            }
        }
    }

    private fun hidePreviewChrome() {
        binding.groupPills.visibility = View.GONE
        binding.previewCard.visibility = View.GONE
        previewPlayer?.playWhenReady = false
    }

    private var landOnOpened = false
    private var openFocusTries = 0
    private var openStamp = 0
    private var channelsStamp = -1
    private var skipGuideTransition = false

    private fun openFullGuide() {
        openStamp += 1
        channelsStamp = -1
        landOnOpened = true
        openFocusTries = 0
        skipGuideTransition = true
        binding.categoryList.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.groupColumn.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (focusIsIn(binding.categoryList)) currentFocus?.clearFocus()
        // TV Live always opens the guide pane. Mega-folders use name/hour filler (no EPG fetch).
        if (section == GuideSection.TV && !touchPrimary) {
            if (guideMenu != GuideMenu.PROGRAMS) previousMenu = guideMenu
            guideMenu = GuideMenu.PROGRAMS
        } else {
            if (guideMenu != GuideMenu.CHANNELS) previousMenu = guideMenu
            guideMenu = GuideMenu.CHANNELS
        }
        applyGuideColumns()
        focusOpenedGuide()
    }

    private fun markChannelsReady() {
        if (!landOnOpened) return
        channelsStamp = openStamp
        openFocusTries = 0
        focusOpenedGuide()
    }

    private fun focusOpenedGuide() {
        if (!landOnOpened) return
        if (channelsStamp != openStamp) {
            retryOpenedFocus()
            return
        }
        if (visibleChannels.isEmpty()) {
            landOnOpened = false
            openFocusTries = 0
            return
        }
        if (section == GuideSection.TV && touchPrimary) {
            if (binding.contentColumn.visibility != View.VISIBLE) {
                retryOpenedFocus()
                return
            }
            if (titleFocusHeld()) {
                landOnOpened = false
                openFocusTries = 0
                return
            }
            focusFirstTitle()
            return
        }
        if (section == GuideSection.TV && binding.programPane.visibility != View.VISIBLE) {
            retryOpenedFocus()
            return
        }
        if (section != GuideSection.TV && binding.contentColumn.visibility != View.VISIBLE) {
            retryOpenedFocus()
            return
        }
        if (section == GuideSection.TV) {
            if (programAdapter.itemCount == 0) {
                retryOpenedFocus()
                return
            }
            if (programFocusHeld()) {
                landOnOpened = false
                openFocusTries = 0
                return
            }
            if (waitingOnGuide()) return
            focusCurrentProgram(0)
            return
        }
        if (titleFocusHeld()) {
            landOnOpened = false
            openFocusTries = 0
            return
        }
        focusFirstTitle()
    }

    private fun waitingOnGuide(): Boolean {
        val channel = visibleChannels.firstOrNull() ?: return false
        if (programAdapter.programsFor(channel.id) != null) return false
        val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot ?: return false
        return slot.row == 0 && focusIsIn(binding.programPane)
    }

    private fun retryOpenedFocus() {
        if (!landOnOpened || openFocusTries >= 80) return
        openFocusTries += 1
        focusHandler.postDelayed({ focusOpenedGuide() }, 40L)
    }

    private fun titleFocusHeld(): Boolean {
        val focused = currentFocus ?: return false
        if (!focusIsIn(binding.channelList)) return false
        val row = binding.channelList.getChildAdapterPosition(
            binding.channelList.findContainingItemView(focused) ?: return false
        )
        return row == 0
    }

    private fun focusFirstTitle() {
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

    private fun focusCurrentProgram(row: Int) {
        binding.programList.post {
            val count = programAdapter.itemCount
            if (count == 0) {
                if (landOnOpened && openFocusTries < 40) {
                    openFocusTries++
                    focusCurrentProgram(row)
                }
                return@post
            }
            val safe = row.coerceIn(0, count - 1)
            val channel = visibleChannels.getOrNull(safe) ?: return@post
            syncProgramRowHeight()
            val nowX = programAdapter.pxFor(com.olympus.iptv.playlist.EpgUtils.now())
            val pad = (48 * resources.displayMetrics.density).toInt()
            scrollProgramGuide((nowX - pad).coerceAtLeast(0))
            focusProgram(safe, programAdapter.columnAt(channel, com.olympus.iptv.playlist.EpgUtils.now()))
        }
    }

    private fun queuePreview(channel: Channel) {
        if (section != GuideSection.TV || channel.kind != ContentKind.LIVE || channel.streamUrl.isBlank()) return
        updatePreviewCaption(channel)
        // Phones: skip auto-preview on focus — saves a second decoder/RAM. TV guide still previews.
        if (touchPrimary) return
        if (channel.id == previewChannelId && previewPlayer != null) return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(400)
            if (isDestroyed) return@launch
            val onRow = focusIsIn(binding.channelList) || focusIsIn(binding.programPane)
            if (onRow && focusedChannel != null && focusedChannel?.id != channel.id) return@launch
            startPreview(channel)
        }
    }

    private fun startPreview(channel: Channel) {
        val player = previewPlayer ?: ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_000, 6_000, 250, 500)
                    .setTargetBufferBytes(4 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setUserAgent("Olympus-IPTV")
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(20_000)
                        .setAllowCrossProtocolRedirects(true)
                )
            )
            .build()
            .also { created ->
                created.volume = 0f
                previewPlayer = created
                binding.previewPlayer.player = created
            }
        previewChannelId = channel.id
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(channel.streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(8_000)
                        .setMinOffsetMs(2_000)
                        .setMaxOffsetMs(24_000)
                        .build()
                )
                .build()
        )
        player.prepare()
        player.playWhenReady = true
        updatePreviewCaption(channel)
    }

    private fun updatePreviewCaption(channel: Channel) {
        val title = epgCache[channel.id]?.now?.title?.takeIf { it.isNotBlank() } ?: channel.name
        binding.previewCaption.text = getString(R.string.preview_on_now, title)
    }

    private fun handOffPreview(channel: Channel) {
        val warm = previewPlayer
        if (warm != null && previewChannelId == channel.id) {
            val app = application as OlympusIptvApp
            app.warmPlayer = warm
            app.warmChannelId = channel.id
            previewPlayer = null
            previewChannelId = null
            previewJob?.cancel()
            binding.previewPlayer.player = null
        } else {
            stopPreview()
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewChannelId = null
        previewPlayer?.stop()
        previewPlayer?.clearMediaItems()
    }

    private var selectedCategoryName = ""

    private fun tickGuideHeader() {
        if (binding.guideDate.visibility != View.VISIBLE) return
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val half = now / halfHourMs
        if (half == guideHeaderHalfHour) {
            positionNowLine(programAdapter.scrollX)
            return
        }
        styleGuideHeader()
    }

    private fun styleGuideHeader() {
        val guide = binding.programPane.visibility == View.VISIBLE && section == GuideSection.TV
        val density = resources.displayMetrics.density
        val height = ((if (guide) 48 else 36) * density).toInt()
        val channelHeader = binding.channelHeader.layoutParams
        if (channelHeader.height != height) {
            channelHeader.height = height
            binding.channelHeader.layoutParams = channelHeader
        }
        val programHeader = binding.programHeaderScroll.layoutParams
        if (guide && programHeader.height != height) {
            programHeader.height = height
            binding.programHeaderScroll.layoutParams = programHeader
        }
        binding.guideDate.visibility = if (guide) View.VISIBLE else View.GONE
        binding.guideNow.visibility = if (guide) View.VISIBLE else View.GONE
        if (selectedCategoryName.isEmpty()) return
        if (guide) {
            val now = com.olympus.iptv.playlist.EpgUtils.now()
            guideHeaderHalfHour = now / halfHourMs
            binding.guideDate.text = com.olympus.iptv.playlist.EpgUtils.formatWhen(now)
            binding.contentTitle.text = getString(R.string.guide_group, selectedCategoryName)
            binding.contentTitle.setTextColor(getColor(R.color.text_secondary))
            binding.contentTitle.textSize = 13f
            binding.contentTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            guideHeaderHalfHour = -1L
            binding.contentTitle.text = selectedCategoryName
            binding.contentTitle.setTextColor(getColor(R.color.text_primary))
            binding.contentTitle.textSize = 16f
            binding.contentTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun jumpGuideToNow() {
        if (binding.programPane.visibility != View.VISIBLE || section != GuideSection.TV) return
        ensureProgramGuide()
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val start = now / halfHourMs * halfHourMs
        val end = start + 8L * 60L * 60L * 1000L
        if (programAdapter.setWindow(start, end)) {
            buildProgramHeader()
        }
        val row = when {
            focusIsIn(binding.programPane) ->
                (currentFocus?.tag as? ProgramGuideAdapter.Slot)?.row ?: focusedChannelRow()
            focusIsIn(binding.channelList) -> focusedChannelRow()
            else -> focusedChannelRow()
        }
        showMenu(GuideMenu.PROGRAMS)
        focusCurrentProgram(row)
    }

    private fun guideFocusTime(slot: ProgramGuideAdapter.Slot): Long {
        val now = com.olympus.iptv.playlist.EpgUtils.now()
        val channel = visibleChannels.getOrNull(slot.row) ?: return now
        val program = programAdapter.programAt(channel.id, slot.column) ?: return now
        if (now >= program.startMillis && now < program.endMillis) return now
        val atScroll = if (programAdapter.slotPx > 0) {
            programAdapter.windowStart +
                (programAdapter.scrollX.toLong() * halfHourMs / programAdapter.slotPx)
        } else {
            programAdapter.windowStart
        }
        if (atScroll >= program.startMillis && atScroll < program.endMillis) return atScroll
        return program.startMillis.coerceIn(
            programAdapter.windowStart,
            (programAdapter.windowEnd - 1L).coerceAtLeast(programAdapter.windowStart)
        )
    }

    private fun focusProgramAtTime(row: Int, time: Long) {
        val count = programAdapter.itemCount
        if (count == 0) return
        val safeRow = ((row % count) + count) % count
        val channel = visibleChannels.getOrNull(safeRow) ?: return
        focusProgram(safeRow, programAdapter.columnAt(channel, time))
    }

    private val namePaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var namePaintReady = false
    private var fittedList: List<Channel>? = null
    private var fittedTextPx = 0f

    private fun fitChannelColumn() {
        if (guideMenu != GuideMenu.CHANNELS && guideMenu != GuideMenu.PROGRAMS) return
        if (binding.programPane.visibility != View.VISIBLE) return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val number = if (section == GuideSection.TV) (32f + 6f) * density else 0f
        val around = (6f + 6f + 24f + 8f + 4f) * density + number
        val wanted = (widestNamePx() + around).toInt().coerceAtLeast((120 * density).toInt())
        val parent = binding.guideRow.width.takeIf { it > 0 } ?: metrics.widthPixels
        val guideFloor = (148 * density).toInt()
        val capped = wanted.coerceAtMost((parent - guideFloor).coerceAtLeast((120 * density).toInt()))
        val params = binding.contentColumn.layoutParams as LinearLayout.LayoutParams
        if (params.width == capped) return
        params.width = capped
        params.weight = 0f
        binding.contentColumn.layoutParams = params
    }

    private fun widestNamePx(): Float {
        if (!namePaintReady) {
            namePaint.textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                15f,
                resources.displayMetrics
            )
            namePaint.typeface = android.graphics.Typeface.DEFAULT
            namePaintReady = true
        }
        val channels = visibleChannels
        val previous = fittedList
        if (previous === channels) return fittedTextPx
        var start = 0
        var widest = 0f
        if (previous != null && channels.size >= previous.size && sameNames(previous, channels)) {
            start = previous.size
            widest = fittedTextPx
        }
        for (index in start until channels.size) {
            val measured = namePaint.measureText(channels[index].name)
            if (measured > widest) widest = measured
        }
        fittedList = channels
        fittedTextPx = widest
        return widest
    }

    private fun sameNames(previous: List<Channel>, channels: List<Channel>): Boolean {
        for (index in previous.indices) {
            if (previous[index].name != channels[index].name) return false
        }
        return true
    }

    private fun focusChannelPane() {
        val manager = binding.channelList.layoutManager as? LinearLayoutManager
        val index = manager?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        focusRowAt(binding.channelList, index, R.id.playTarget)
    }

    private fun openProgramGuide() {
        if (section != GuideSection.TV) return
        val row = focusedChannelRow()
        showMenu(GuideMenu.PROGRAMS)
        binding.programList.post {
            syncProgramRowHeight()
            positionNowLine(programAdapter.scrollX)
            focusProgram(row, currentProgramColumn(row))
        }
    }

    private var rowLongPress = false
    private var groupHold = false
    private var rowMenuAt = 0L

    private fun rowSelectKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    private fun focusOnGuideRow(): Boolean =
        focusIsIn(binding.channelList) || focusIsIn(binding.programPane)

    private fun focusedCategory(): Category? {
        val item = binding.categoryList.findContainingItemView(currentFocus ?: return null) ?: return null
        val pos = binding.categoryList.getChildAdapterPosition(item)
        return categoryAdapter.categories().getOrNull(pos)
    }

    private fun showRowOptions(seed: Channel? = null) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - rowMenuAt < 400L) return
        rowMenuAt = now
        val channel = seed
            ?: (currentFocus?.tag as? ProgramGuideAdapter.Slot)?.let { visibleChannels.getOrNull(it.row) }
            ?: focusedChannel
            ?: return
        val saved = playlist ?: return
        val queueItem = WatchQueue.itemFor(channel, section == GuideSection.SHOWS)
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val queued = queueItem != null && withContext(Dispatchers.IO) {
                app.playlistStore.queue(saved.id).any { it.bucket == queueItem.bucket && it.id == queueItem.id }
            }
            if (isDestroyed) return@launch
            val favorite = Favorites.matches(channel, favoriteIds)
            val labels = ArrayList<String>()
            val actions = ArrayList<Int>()
            labels += getString(if (favorite) R.string.guide_remove_favorite else R.string.guide_add_favorite)
            actions += 0
            if (section == GuideSection.TV && !touchPrimary) {
                labels += getString(R.string.guide_move_up)
                actions += 1
                labels += getString(R.string.guide_move_down)
                actions += 2
            }
            labels += getString(R.string.record)
            actions += 3
            if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) {
                labels += getString(R.string.remind_me)
                actions += 4
            }
            if (queueItem != null) {
                labels += getString(if (queued) R.string.queue_remove else R.string.queue_add)
                actions += 5
            }
            if (section != GuideSection.TV) {
                labels += getString(R.string.vod_ratings_reviews)
                actions += 6
            }
            if (touchPrimary) {
                labels += getString(R.string.open_in_pip)
                actions += 7
            }
            val runAction: (Int) -> Unit = { action ->
                when (action) {
                    0 -> toggleGuideFavorite(channel)
                    1 -> moveFavorite(channel, -1)
                    2 -> moveFavorite(channel, 1)
                    3 -> offerRecording(channel)
                    4 -> offerReminder(channel)
                    5 -> if (queueItem != null) {
                        app.appScope.launch {
                            if (queued) app.playlistStore.removeQueue(saved.id, queueItem.bucket, queueItem.id)
                            else app.playlistStore.addQueue(saved.id, queueItem)
                        }
                    }
                    6 -> openVodRatings(channel)
                    7 -> play(channel, enterPip = true)
                }
            }
            if (touchPrimary) {
                showPhoneRowSheet(channel.name, labels, actions, runAction)
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this@BrowseActivity)
                    .setTitle(channel.name)
                    .setItems(labels.toTypedArray()) { _, which -> runAction(actions[which]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showPhoneRowSheet(
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

    private fun offerReminder(channel: Channel) {
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
                Toast.makeText(this@BrowseActivity, R.string.remind_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val number = visibleChannels.indexOfFirst { it.id == channel.id }.let { if (it >= 0) it + 1 else channel.number ?: 1 }
            RemindPrompt.show(this@BrowseActivity, saved.id, channel, number, program)
        }
    }

    private fun reminderTarget(channel: Channel): EpgProgram? {
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

    private fun ensureProgramGuide() {
        if (!::programAdapter.isInitialized) return
        if (programAdapter.windowEnd <= programAdapter.windowStart) {
            val now = com.olympus.iptv.playlist.EpgUtils.now()
            val start = now / halfHourMs * halfHourMs
            programAdapter.setWindow(start, start + 8L * 60L * 60L * 1000L)
            programAdapter.scrollX = 0
        }
        if (binding.programHeader.childCount == 0) buildProgramHeader()
        programAdapter.submit(visibleChannels)
        loadVisibleGuidePrograms()
        binding.programList.post {
            syncProgramRowHeight()
            positionNowLine(programAdapter.scrollX)
        }
    }

    private fun syncProgramGuide() {
        if (section == GuideSection.TV && binding.programPane.visibility == View.VISIBLE) {
            fitChannelColumn()
            ensureProgramGuide()
        }
        focusOpenedGuide()
    }

    private fun loadVisibleGuidePrograms() {
        if (section != GuideSection.TV || binding.programPane.visibility != View.VISIBLE) return
        val layout = binding.programList.layoutManager as? LinearLayoutManager
            ?: binding.channelList.layoutManager as? LinearLayoutManager
            ?: return
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = layout.findLastVisibleItemPosition().let { if (it < 0) first + 11 else it }
        val from = (first - 4).coerceAtLeast(0)
        val to = (last + 8).coerceAtMost(visibleChannels.lastIndex)
        if (to < from) return
        loadGuidePrograms(visibleChannels.subList(from, to + 1))
    }

    private fun nameOnlyGuideGroup(): Boolean {
        if (section != GuideSection.TV) return false
        val count = groupCounts[selectedCategoryId] ?: 0
        return count >= LARGE_LIVE_GROUP
    }

    private fun loadGuidePrograms(channels: List<Channel>) {
        val missing = channels.filter { it.kind == ContentKind.LIVE && programAdapter.programsFor(it.id) == null }
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
            }
            binding.programList.post {
                lastLabelShiftScroll = programAdapter.scrollX
                applyGuideRowHeight(programAdapter.refreshLabelShift(binding.programList))
            }
            return
        }
        val saved = playlist ?: return
        guideJob?.cancel()
        guideJob = lifecycleScope.launch {
            fetchGuidePrograms(saved, missing.take(8), 8)
            fetchGuidePrograms(saved, missing.drop(8), 4)
            if (isDestroyed) return@launch
            binding.programList.post {
                lastLabelShiftScroll = programAdapter.scrollX
                applyGuideRowHeight(programAdapter.refreshLabelShift(binding.programList))
            }
        }
    }

    private var guideFocusRestore: Runnable? = null

    private suspend fun fetchGuidePrograms(saved: SavedPlaylist, channels: List<Channel>, width: Int) {
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

    private fun scheduleGuideFocusRestore(action: () -> Unit) {
        guideFocusRestore?.let { focusHandler.removeCallbacks(it) }
        val next = Runnable { guideFocusRestore = null; action() }
        guideFocusRestore = next
        focusHandler.postDelayed(next, 48L)
    }

    private var alignGuideSource: androidx.recyclerview.widget.RecyclerView? = null
    private var alignGuideTarget: androidx.recyclerview.widget.RecyclerView? = null
    private var lastAlignIndex = Int.MIN_VALUE
    private var lastAlignTop = Int.MIN_VALUE
    private val alignGuideRunnable = Runnable {
        val source = alignGuideSource ?: return@Runnable
        val target = alignGuideTarget ?: return@Runnable
        alignGuide(source, target)
    }

    private fun scheduleGuideAlign(
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

    private fun buildProgramHeader() {
        val header = binding.programHeader
        header.removeAllViews()
        val start = programAdapter.windowStart
        val count = ((programAdapter.windowEnd - start) / halfHourMs).toInt().coerceAtLeast(1)
        val width = programAdapter.timelineWidth()
        val nowIndex = ((com.olympus.iptv.playlist.EpgUtils.now() - start) / halfHourMs).toInt()
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(width, 0, 1f)
        }
        repeat(count) { index ->
            times.addView(TextView(this).apply {
                text = EpgUtils.formatClock(start + index * halfHourMs)
                gravity = Gravity.CENTER
                setTextColor(getColor(if (index == nowIndex) R.color.accent else R.color.text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(programAdapter.slotPx, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        val density = resources.displayMetrics.density
        val rule = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, (10 * density).toInt())
        }
        rule.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.stroke))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                density.toInt().coerceAtLeast(1)
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        })
        val dot = (5 * density).toInt()
        repeat(count) { index ->
            rule.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(getColor(if (index == nowIndex) R.color.accent else R.color.text_secondary))
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(dot, dot).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = index * programAdapter.slotPx + programAdapter.slotPx / 2 - dot / 2
                }
            })
        }
        header.addView(times)
        header.addView(rule)
        binding.programHeaderScroll.scrollTo(programAdapter.scrollX, 0)
        positionNowLine(programAdapter.scrollX)
    }

    private fun scrollProgramGuide(x: Int) {
        if (guideScrollSync) return
        guideScrollSync = true
        programAdapter.scrollX = x
        binding.programHeaderScroll.scrollTo(x, 0)
        positionNowLine(x)
        for (index in 0 until binding.programList.childCount) {
            val row = binding.programList.getChildAt(index)
            row.findViewById<android.widget.HorizontalScrollView>(R.id.programRowScroll)?.scrollTo(x, 0)
        }
        if (kotlin.math.abs(x - lastLabelShiftScroll) >= 12) {
            lastLabelShiftScroll = x
            val needTall = programAdapter.refreshLabelShift(binding.programList)
            applyGuideRowHeight(needTall)
        }
        guideScrollSync = false
    }

    private fun positionNowLine(scrollX: Int) {
        val line = binding.programNowLine
        val x = programAdapter.pxFor(com.olympus.iptv.playlist.EpgUtils.now()) - scrollX
        val width = binding.programList.width
        if (width > 0 && x in 0..width) {
            line.visibility = View.VISIBLE
            line.translationX = x.toFloat()
        } else {
            line.visibility = View.INVISIBLE
        }
    }

    private var guideDensity = PlaylistStore.GUIDE_COMFORTABLE

    private fun baseGuideRowDp(): Int =
        if (guideDensity == PlaylistStore.GUIDE_COMPACT) 40 else 52

    private fun tallGuideRowDp(): Int =
        if (guideDensity == PlaylistStore.GUIDE_COMPACT) 56 else 72

    private fun syncProgramRowHeight() {
        applyGuideRowHeight(guideRowsTall)
    }

    private fun applyGuideRowHeight(tall: Boolean) {
        guideRowsTall = tall
        val dp = if (tall) tallGuideRowDp() else baseGuideRowDp()
        val height = (dp * resources.displayMetrics.density).toInt()
        val programChanged = programAdapter.rowHeight != height
        val channelChanged = channelAdapter.guideRowHeight != height
        if (!programChanged && !channelChanged) return
        programAdapter.rowHeight = height
        channelAdapter.guideRowHeight = height
        if (programAdapter.itemCount > 0) {
            programAdapter.notifyItemRangeChanged(0, programAdapter.itemCount)
        }
        if (channelAdapter.itemCount > 0 && binding.programPane.visibility == View.VISIBLE) {
            channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount)
        }
    }

    private fun alignGuide(
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

    private fun focusedChannelRow(): Int {
        val focused = currentFocus ?: return 0
        val position = binding.channelList.findContainingViewHolder(focused)?.bindingAdapterPosition ?: return 0
        return position.takeIf { it >= 0 } ?: 0
    }

    private fun currentProgramColumn(row: Int): Int {
        val channel = visibleChannels.getOrNull(row) ?: return 0
        return programAdapter.columnAt(channel, com.olympus.iptv.playlist.EpgUtils.now())
    }

    private fun programFocusHeld(): Boolean {
        val slot = currentFocus?.tag as? ProgramGuideAdapter.Slot ?: return false
        if (slot.row != 0 || !focusIsIn(binding.programPane)) return false
        val channel = visibleChannels.getOrNull(0) ?: return false
        if (programAdapter.programsFor(channel.id) == null) return false
        return slot.column == programAdapter.columnAt(channel, com.olympus.iptv.playlist.EpgUtils.now())
    }

    private fun focusProgram(row: Int, column: Int, retry: Boolean = true) {
        val count = programAdapter.itemCount
        if (count == 0) return
        val safeRow = ((row % count) + count) % count
        guideScrollSync = true
        binding.programList.scrollToPosition(safeRow)
        binding.channelList.scrollToPosition(safeRow)
        binding.programList.post {
            guideScrollSync = false
            val track = binding.programList.findViewHolderForAdapterPosition(safeRow)
                ?.itemView
                ?.findViewById<android.widget.FrameLayout>(R.id.programTrack)
            if (track == null || track.childCount == 0) {
                if ((retry || landOnOpened) && openFocusTries < 40) {
                    openFocusTries++
                    binding.programList.post { focusProgram(safeRow, column, false) }
                }
                return@post
            }
            val target = track.getChildAt(column.coerceIn(0, track.childCount - 1))
            if (!target.requestFocus() || !target.hasFocus()) {
                if ((retry || landOnOpened) && openFocusTries < 40) {
                    openFocusTries++
                    binding.programList.post { focusProgram(safeRow, column, false) }
                }
                return@post
            }
            val channel = visibleChannels.getOrNull(safeRow)
            val listed = channel != null && programAdapter.programsFor(channel.id) != null
            if (landOnOpened && channelsStamp == openStamp && listed && safeRow == 0) {
                landOnOpened = false
                openFocusTries = 0
            } else if (!landOnOpened) {
                openFocusTries = 0
            }
        }
    }

    private fun revealPendingChannel() {
        val index = pendingChannelFocus
        if (index < 0) return
        val focusId = pendingChannelFocusId
        pendingChannelFocus = -1
        pendingChannelFocusId = View.NO_ID
        val count = channelAdapter.itemCount
        if (count == 0) return
        focusRowAt(binding.channelList, if (index < count) index else 0, focusId)
    }

    private fun focusRowAt(list: androidx.recyclerview.widget.RecyclerView, index: Int, focusId: Int) {
        if (index < 0) return
        list.scrollToPosition(index)
        list.post {
            if (list === binding.channelList) alignGuide(binding.channelList, binding.programList)
            if (list === binding.programList) alignGuide(binding.programList, binding.channelList)
            val row = list.findViewHolderForAdapterPosition(index)?.itemView
            if (row == null) {
                list.post { focusRow(list.findViewHolderForAdapterPosition(index)?.itemView, focusId) }
            } else {
                focusRow(row, focusId)
            }
        }
    }

    private fun focusRow(row: View?, focusId: Int) {
        if (row == null) return
        val same = if (focusId != View.NO_ID) row.findViewById<View>(focusId) else null
        val play = row.findViewById<View>(R.id.playTarget)
        val target = listOf(same, play, row).firstOrNull { it != null && it.isShown && it.isFocusable }
        target?.requestFocus()
    }

    private fun navItems(): List<View> = listOf(
        binding.btnSearch,
        binding.tabLive,
        binding.tabVod,
        binding.tabShows,
        binding.btnRecord,
        binding.btnAdmin,
        binding.btnAbout
    )

    private fun openFavoritesSetup() {
        val saved = playlist ?: return
        val ready = loaded != null && !moviesPending && !showsPending
        startActivity(
            Intent(this, com.olympus.iptv.ui.login.FavoritesSetupActivity::class.java)
                .putExtra(com.olympus.iptv.ui.login.FavoritesSetupActivity.EXTRA_PLAYLIST_ID, saved.id)
                .putExtra(com.olympus.iptv.ui.login.FavoritesSetupActivity.EXTRA_CATALOG_READY, ready)
                .putExtra(com.olympus.iptv.ui.login.FavoritesSetupActivity.EXTRA_FROM_GUIDE, true)
        )
    }

    private fun focusNav(direction: Int) {
        val items = navItems().filter { it.visibility == View.VISIBLE }
        val index = items.indexOf(currentFocus)
        val next = if (index < 0) 0 else index + direction
        if (next in items.indices) items[next].requestFocus()
    }

    private fun sectionButton(): View = when (section) {
        GuideSection.TV -> binding.tabLive
        GuideSection.MOVIES -> binding.tabVod
        GuideSection.SHOWS -> binding.tabShows
    }

    private var focusGroupIndex = -1
    private var landOnGroups = false
    private var landOnContinue = false
    private var groupFocusTries = 0

    private fun armGroupFocus() {
        landOnGroups = true
        groupFocusTries = 0
        focusGroupIndex = -1
        if (focusIsIn(binding.navRail)) currentFocus?.clearFocus()
    }

    private fun focusGroups() {
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

    private fun retryGroupFocus() {
        if (!landOnGroups || groupFocusTries >= 40) {
            landOnGroups = false
            groupFocusTries = 0
            focusGroupIndex = -1
            return
        }
        groupFocusTries += 1
        focusHandler.postDelayed({ focusGroups() }, 40L)
    }

    private fun cardIcon(category: Category): Int = when (category.id) {
        PlaylistLoader.RECENT_ID, PlaylistLoader.CONTINUE_ID, PlaylistLoader.TOP_WATCHED_ID ->
            R.drawable.ic_card_play
        PlaylistLoader.FAVORITES_ID -> R.drawable.ic_star
        PlaylistLoader.ALL_ID -> R.drawable.ic_nav_tv
        else -> R.drawable.ic_card_layers
    }

    private fun cardDetail(category: Category): String {
        val titles = section != GuideSection.TV
        val base = when (category.id) {
            PlaylistLoader.RECENT_ID -> {
                val wanted = sectionKey(section)
                val count = recentEntries.count { entrySection(it) == wanted }
                if (count > 0) getString(R.string.recent_count, count) else getString(R.string.recent_card)
            }
            PlaylistLoader.CONTINUE_ID -> {
                val wanted = sectionKey(section)
                val count = recentEntries.count { entrySection(it) == wanted && it.canResume() }
                if (count > 0) getString(R.string.continue_count, count) else getString(R.string.continue_card)
            }
            PlaylistLoader.TOP_WATCHED_ID -> getString(R.string.top_watched_card)
            PlaylistLoader.FAVORITES_ID -> {
                val prefix = favoriteSection(section) + ":"
                val label = if (titles) R.string.group_titles else R.string.group_channels
                getString(label, favoriteIds.count { it.startsWith(prefix) })
            }
            else -> {
                val count = groupCounts[category.id]
                if (count == null) ""
                else {
                    val label = if (titles) R.string.group_titles else R.string.group_channels
                    getString(label, count)
                }
            }
        }
        if (!GroupLock.locked(activeConfig, category.id)) return base
        val locked = getString(R.string.group_locked)
        return if (base.isBlank()) locked else "$base · $locked"
    }

    private fun refreshGroupCounts() {
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

    private fun render(restoreFocus: Boolean = false, reloadChannels: Boolean = true) {
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
                withRecent(listOf(favorites) + withAll)
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

    private fun showChannels() {
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

    private fun stillShowing(query: Int, sectionNow: GuideSection, categoryId: String?): Boolean {
        val open = binding.groupColumn.visibility == View.VISIBLE ||
            binding.contentColumn.visibility == View.VISIBLE
        return query == channelQuery &&
            !isDestroyed &&
            open &&
            section == sectionNow &&
            selectedCategoryId == categoryId
    }

    private fun libraryBucket(sectionNow: GuideSection): String = when (sectionNow) {
        GuideSection.TV -> CatalogDb.LIVE
        GuideSection.MOVIES -> CatalogDb.VOD
        GuideSection.SHOWS -> CatalogDb.SERIES
    }

    private fun libraryGroup(sectionNow: GuideSection, categoryId: String, config: LineupConfig): List<Channel> {
        val saved = playlist ?: return emptyList()
        val rows = (application as OlympusIptvApp).catalogDb.readCategoryChannels(
            saved.id,
            libraryBucket(sectionNow),
            categoryId
        )
        return prepareGroup(rows, config)
    }

    private fun libraryFavorites(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
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

    private var topWatchedIdsBySection: Map<String, List<String>> = emptyMap()
    private var topWatchedCacheKey: String = ""

    private fun invalidateTopWatchedCache() {
        topWatchedIdsBySection = emptyMap()
        topWatchedCacheKey = ""
    }

    private fun ensureTopWatchedCache() {
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

    private fun topWatchedFavorites(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
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

    private fun favoriteSection(sectionNow: GuideSection): String = when (sectionNow) {
        GuideSection.TV -> Favorites.LIVE
        GuideSection.MOVIES -> Favorites.VOD
        GuideSection.SHOWS -> Favorites.SERIES
    }

    private fun showChannelWait(text: String) {
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

    private fun publishChannels(channels: List<Channel>, scrollTop: Boolean) {
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

    private fun rememberGroupScroll() {
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

    private fun showChannelSkeleton(show: Boolean) {
        if (!::binding.isInitialized) return
        binding.channelSkeleton.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.libraryStatus.visibility = View.GONE
            binding.errorText.visibility = View.GONE
        }
    }

    private fun maybePrefetchGroupPage(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        if (pageExhausted || pageLoading) return
        val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val last = layout.findLastVisibleItemPosition()
        if (last == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val remain = pagePrefetchRemain(selectedCategoryId)
        if (last >= channelAdapter.itemCount - remain) loadGroupPage(reset = false)
    }

    private fun loadGroupPage(reset: Boolean) {
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

    private fun finishChannelPane(channels: List<Channel>) {
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

    private fun groupLabel(categoryId: String?): String? {
        if (categoryId == null ||
            categoryId == PlaylistLoader.ALL_ID ||
            categoryId == PlaylistLoader.FAVORITES_ID
        ) {
            return null
        }
        return activeConfig.groupNames?.get(categoryId)
    }

    private fun idIndex(sectionNow: GuideSection): Map<String, Channel> {
        val indexed = when (sectionNow) {
            GuideSection.TV -> liveById
            GuideSection.MOVIES -> vodById
            GuideSection.SHOWS -> if (seriesById.isNotEmpty()) seriesById else vodById
        }
        if (indexed.isNotEmpty()) return indexed
        return visibleChannels.associateBy { it.id }
    }

    private fun moveFavorite(channel: Channel, direction: Int) {
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

    private var groupedSection: GuideSection? = null
    private var groupedCategories: List<Category>? = null
    private var groupedConfig: LineupConfig? = null
    private var groupedCountries: Map<String, String> = emptyMap()
    private var groupedResult: List<Category> = emptyList()
    private var groupCountries: Map<String, String> = emptyMap()
    private var groupCountriesReady = false

    private fun ensureGroupCountries() {
        if (groupCountriesReady) return
        val id = playlist?.id ?: return
        groupCountriesReady = true
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val map = withContext(Dispatchers.IO) {
                app.catalogDb.dashboardSnapshot(id).groups
                    .asSequence()
                    .filter { it.kind == "LIVE" && it.countryId.isNotEmpty() }
                    .associate { it.id to it.countryId }
            }
            if (isDestroyed) return@launch
            groupCountries = map
            groupedSection = null
            if (binding.groupColumn.visibility == View.VISIBLE && loaded != null) {
                render(restoreFocus = false, reloadChannels = false)
            }
        }
    }

    private fun visibleGroups(categories: List<Category>, config: LineupConfig): List<Category> {
        ensureGroupCountries()
        if (groupedSection == section &&
            groupedCategories === categories &&
            groupedConfig === config &&
            groupedCountries === groupCountries
        ) {
            return groupedResult
        }
        val groups = LineupApplier.groups(categories, config, groupCountries)
        groupedSection = section
        groupedCategories = categories
        groupedConfig = config
        groupedCountries = groupCountries
        groupedResult = groups
        return groups
    }

    private fun withoutEmptyGroups(categories: List<Category>): List<Category> {
        if (!hideEmptyGroups || groupCounts.isEmpty()) return categories
        return categories.filter { category ->
            category.id == PlaylistLoader.ALL_ID ||
                category.id == PlaylistLoader.FAVORITES_ID ||
                category.id == PlaylistLoader.RECENT_ID ||
                category.id == PlaylistLoader.CONTINUE_ID ||
                category.id == PlaylistLoader.TOP_WATCHED_ID ||
                (groupCounts[category.id] ?: 0) > 0
        }
    }

    private fun prepareGroup(channels: List<Channel>, config: LineupConfig): List<Channel> {
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

    private fun indexedPool(data: LoadedPlaylist, sectionNow: GuideSection, categoryId: String?): List<Channel>? {
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

    private fun selectCategory(category: Category, immediate: Boolean) {
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

    private var epgPrefetchJob: Job? = null
    private var episodePrefetchJob: Job? = null
    private var episodePrefetchIds: List<String> = emptyList()

    private fun scheduleEpisodePrefetch() {
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

    private fun prefetchEpisodeLists(seriesIds: List<String>) {
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

    private fun warmFavoriteAndRecentEpg() {
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

    private fun schedulePrefetch(channels: List<Channel>) {
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

    private fun prefetchEpg(channels: List<Channel>) {
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

    private fun showChannelDetail(channel: Channel) {
        focusedChannel = channel
        queuePreview(channel)
        epgWait?.let { focusHandler.removeCallbacks(it) }
        epgWait = null
        if (channel.kind != ContentKind.LIVE) {
            loadTitleLine(channel)
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
                updatePreviewCaption(channel)
            }
        }
        epgWait = run
        focusHandler.postDelayed(run, FOCUS_SETTLE_MS)
    }

    private fun loadTitleLine(channel: Channel) {
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

    private fun openVodRatings(channel: Channel) {
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
                context = this@BrowseActivity,
                bundle = bundle,
                onTrailer = {
                    openYoutubeTrailer(channel.name, preferTv)
                },
                onPlay = { play(channel) }
            )
        }
    }

    private fun openYoutubeTrailer(name: String, preferTv: Boolean) {
        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            Toast.makeText(this, R.string.tmdb_missing_key, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val trailer = withContext(Dispatchers.IO) {
                TitleRatings.trailerFor(name, preferTv)
            }
            if (isDestroyed) return@launch
            if (trailer == null) {
                Toast.makeText(this@BrowseActivity, R.string.trailer_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${trailer.key}"))
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${trailer.key}"))
            runCatching { startActivity(appIntent) }.recoverCatching { startActivity(webIntent) }
                .onFailure {
                    Toast.makeText(this@BrowseActivity, R.string.trailer_missing, Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun setupPipSwipeOpen() {
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

    private fun toggleGuideFavorite(channel: Channel) {
        val saved = playlist ?: return
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
        }
    }

    private fun pushFavorites(app: OlympusIptvApp, playlistId: String, baseRevision: Long) {
        app.appScope.launch {
            val email = app.playlistStore.session.first()?.email?.trim().orEmpty()
            if (email.isBlank()) return@launch
            val ids = app.playlistStore.favorites(playlistId)
            val revision = app.playlistStore.favoritesRevision(playlistId)
            LineupClient(app).pushFavorites(email, app.playlistStore.deviceId(), ids, revision, baseRevision)
        }
    }

    private fun restoreAfterPlayer() {
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

    private fun focusReturnChannel(row: Int) {
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

    private fun playContinue(channel: Channel) {
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
                startActivity(EpisodesActivity.intent(this@BrowseActivity, saved.id, channel))
                return@launch
            }
            SeriesPlay.open(this@BrowseActivity, saved.id, ordered, episode)
        }
    }

    private fun playFromGuide(channel: Channel, program: EpgProgram?) {
        val now = System.currentTimeMillis()
        if (program != null && program.endMillis <= now) {
            if (channel.supportsCatchup()) playCatchup(channel, program)
            else Toast.makeText(this, R.string.catchup_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        play(channel)
    }

    private fun playCatchup(channel: Channel, program: EpgProgram) {
        val saved = playlist ?: return
        if (openingPlayer) return
        lifecycleScope.launch {
            val hydrated = withContext(Dispatchers.IO) {
                val db = (application as OlympusIptvApp).catalogDb
                db.readChannel(saved.id, CatalogDb.LIVE, channel.id) ?: channel
            }
            val url = CatchupUrls.url(saved, hydrated, program)
            if (url.isNullOrBlank()) {
                Toast.makeText(this@BrowseActivity, R.string.catchup_unavailable, Toast.LENGTH_SHORT).show()
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
                    this@BrowseActivity,
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

    private fun play(
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

    private fun showLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.categoryList.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.channelList.visibility = if (loading) View.INVISIBLE else View.VISIBLE
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_SKIP_RESUME = "skip_resume"
        private const val CARD_SPAN = 3
        private const val PAGE_SIZE_TV = 200
        private const val PAGE_SIZE_PHONE = 100
        private const val PAGE_SIZE_HUGE = 20
        private const val LARGE_LIVE_GROUP = 250
        private const val HUGE_PREFETCH_REMAIN = 5
        private const val DEFAULT_PREFETCH_REMAIN = 30
        private const val MAX_EMPTY_PAGE_SKIPS = 40
        private const val FOCUS_SETTLE_MS = 160L
        private const val EPG_CACHE_CAP = 120
    }

    private fun pageSizeFor(categoryId: String?): Int {
        val count = groupCounts[categoryId] ?: 0
        return when {
            count >= LARGE_LIVE_GROUP -> PAGE_SIZE_HUGE
            touchPrimary -> PAGE_SIZE_PHONE
            else -> PAGE_SIZE_TV
        }
    }

    private fun pagePrefetchRemain(categoryId: String?): Int {
        val count = groupCounts[categoryId] ?: 0
        return if (count >= LARGE_LIVE_GROUP) HUGE_PREFETCH_REMAIN else DEFAULT_PREFETCH_REMAIN
    }
}
