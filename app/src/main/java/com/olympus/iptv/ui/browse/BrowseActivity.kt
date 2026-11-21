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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

class BrowseActivity : AppCompatActivity(), ToolHost {
    internal lateinit var binding: ActivityBrowseBinding
    internal val browseModel by lazy { ViewModelProvider(this)[BrowseViewModel::class.java] }
    internal val loader = PlaylistLoader()
    internal lateinit var previewCtl: BrowsePreviewController
    internal lateinit var epgCtl: BrowseEpgController

    internal var playlist: SavedPlaylist? = null
    internal var source: LoadedPlaylist? = null
    internal var loaded: LoadedPlaylist? = null
    internal val epgRepo: EpgRepository
        get() = (application as OlympusIptvApp).epgRepository
    internal var kind = ContentKind.LIVE
    internal var renderedSection: GuideSection? = null
    internal var selectedCategoryId: String? = PlaylistLoader.FAVORITES_ID
    internal var touchPrimary = false
    internal var openingPlayer = false
    internal var pipGestures = true
    internal var returnMenu = GuideMenu.GROUPS
    internal var returnChannelId: String? = null
    internal var returnOnProgram = false
    internal var restorePlaybackFocus = false
    internal var playerClosedAt = 0L
    internal var moviesPending = false
    internal var showsPending = false
    @Volatile internal var movieCount = 0
    @Volatile internal var showCount = 0
    @Volatile internal var moviePercent = -1
    @Volatile internal var showPercent = -1

    internal fun screen(): BrowseActivity = this

    internal fun viewReady() = ::binding.isInitialized
    internal fun previewReady() = ::previewCtl.isInitialized
    internal fun epgReady() = ::epgCtl.isInitialized
    internal fun vpnReady() = ::vpnUi.isInitialized
    internal fun guideReady() = ::programAdapter.isInitialized
    internal var movieError: String? = null
    internal var movieAttempt = 1
    internal var showError: String? = null
    internal var lastMoviePost = 0L
    internal var lastShowPost = 0L
    internal val countFormat = NumberFormat.getIntegerInstance()
    internal var refreshLoop: Job? = null
    internal var refreshMinutes = PlaylistStore.DEFAULT_REFRESH_MINUTES
    internal var groupsNeedRefresh = false
    internal val pendingLive = ArrayList<Channel>()
    internal val pendingVod = ArrayList<Channel>()
    internal val pendingSeries = ArrayList<Channel>()
    internal var activeConfig = LineupConfig()
    internal var channelJob: Job? = null
    internal var channelQuery = 0
    internal var liveIndexGeneration = 0
    internal var vodIndexGeneration = 0
    internal var seriesIndexGeneration = 0
    internal var liveIndexing = false
    internal var vodIndexing = false
    internal var seriesIndexing = false
    internal var liveByCategory: Map<String, List<Channel>> = emptyMap()
    internal var vodByCategory: Map<String, List<Channel>> = emptyMap()
    internal var seriesByCategory: Map<String, List<Channel>> = emptyMap()
    internal var liveById: Map<String, Channel> = emptyMap()
    internal var vodById: Map<String, Channel> = emptyMap()
    internal var seriesById: Map<String, Channel> = emptyMap()
    internal val visibleChannels = ArrayList<Channel>()
    internal var pageCursor: CatalogDb.PageCursor? = null
    internal var pageExhausted = false
    internal var emptyPageSkips = 0
    internal var pendingChannelFocus = -1
    internal var pendingChannelFocusId = View.NO_ID
    internal var pageLoading = false
    internal var loadingFromDb = false
    internal val libraryMutex = Mutex()
    internal var focusedChannel: Channel? = null
    internal var detailJob: Job? = null
    internal val favoriteOrder = mutableListOf<String>()
    internal var recentEntries: List<WatchEntry> = emptyList()
    internal var groupCounts: Map<String, Int> = emptyMap()
    internal var groupCountToken = 0
    internal val groupCountCache = HashMap<String, Map<String, Int>>()
    internal var hideEmptyGroups = true
    internal var recentStamp = ""
    internal val favoriteIds = mutableSetOf<String>()

    internal val epgCache = java.util.concurrent.ConcurrentHashMap<String, EpgNowNext>()
    internal val groupClockFormat = SimpleDateFormat("MM/dd/yyyy  hh:mm a", Locale.US)
    internal var groupClockMinute = Long.MIN_VALUE
    internal var guideRowsTall = false
    internal var lastLabelShiftScroll = Int.MIN_VALUE












































    override fun showTool(fragment: Fragment) = browseToolHost.showTool(fragment)

    override fun hideTool() = browseToolHost.hideTool()

    override fun isToolVisible(): Boolean = browseToolHost.isToolVisible()

    internal enum class GuideSection { TV, MOVIES, SHOWS }

    internal var section = GuideSection.TV
    internal var returnToGuide = true






























































    internal enum class GuideMenu { SETTINGS, GROUPS, CHANNELS, PROGRAMS }

    internal var guideMenu = GuideMenu.SETTINGS
    internal var previousMenu = GuideMenu.GROUPS
    internal var navExpanded = true
    internal val navLabels = HashMap<Int, CharSequence>()


































































































































    internal val dashboardUrls: (String) -> Unit = { url ->
        runOnUiThread { if (!isDestroyed) showDashboardUrl(url) }
    }

    internal var seenLibraryStamp = -1L

    internal var holdChannelId: String? = null

    internal val groupScrollOffset = HashMap<String, Int>()

    internal var pinFailStreak = 0

    internal val lineupWatch: (String) -> Unit = { id ->
        if (id == playlist?.id && !isDestroyed) {
            adoptRemoteLineup()
            reloadFavorites()
        }
    }

    internal var pinCategory: Category? = null

    internal var pinOpens = false

    internal var pinClearing = false

    internal var pinDigits = ""

    internal var pinKeyIndex = 4

    internal var dialDigits = ""

    internal val groupSampleIds = HashMap<String, List<String>>()

    internal var groupNowJob: Job? = null

    internal var groupNowAt = 0L

    internal val lowRamDevice by lazy { com.olympus.iptv.ui.DeviceForm.isLowRam(this) }

    internal val dialJump = Runnable { jumpDial() }

    internal val letterJumpHold = Runnable {
        if (!isDestroyed && focusIsIn(binding.channelList) && visibleChannels.size > 40) showLetterJump()
    }

    internal var guidePinWired = false

    internal val guidePinKeys: List<View> by lazy {
        listOf(
            binding.guidePin1, binding.guidePin2, binding.guidePin3,
            binding.guidePin4, binding.guidePin5, binding.guidePin6,
            binding.guidePin7, binding.guidePin8, binding.guidePin9,
            binding.guidePinClearDigit, binding.guidePin0, binding.groupPinCancel
        )
    }

    internal var pinAt = 0L

    internal var topWatchedIdsBySection: Map<String, List<String>> = emptyMap()

    internal var topWatchedCacheKey: String = ""

    internal var groupedSection: GuideSection? = null

    internal var groupedCategories: List<Category>? = null

    internal var groupedConfig: LineupConfig? = null

    internal var groupedCountries: Map<String, String> = emptyMap()

    internal var groupedResult: List<Category> = emptyList()

    internal var groupCountries: Map<String, String> = emptyMap()

    internal var groupCountriesReady = false

    internal var groupCountriesQueued = false

    internal var groupCountriesLoaded = false

    internal var epgPrefetchJob: Job? = null

    internal var episodePrefetchJob: Job? = null

    internal var episodePrefetchIds: List<String> = emptyList()

    internal val namePaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    internal var namePaintReady = false

    internal var fittedList: List<Channel>? = null

    internal var fittedTextPx = 0f

    internal var alignGuideSource: androidx.recyclerview.widget.RecyclerView? = null

    internal var alignGuideTarget: androidx.recyclerview.widget.RecyclerView? = null

    internal var lastAlignIndex = Int.MIN_VALUE

    internal var lastAlignTop = Int.MIN_VALUE

    internal val alignGuideRunnable = Runnable {
        val source = alignGuideSource ?: return@Runnable
        val target = alignGuideTarget ?: return@Runnable
        alignGuide(source, target)
    }

    internal var focusGroupIndex = -1

    internal var landOnGroups = false

    internal var landOnContinue = false

    internal var groupFocusTries = 0

    internal lateinit var browseToolHost: BrowseToolHost

    internal lateinit var vpnUi: VpnUi

    internal lateinit var panes: BrowseLazyPanes

    internal var pendingVpnDetail: String? = null

    internal var pendingRefreshStatus: String? = null

    internal var pendingDeviceId: String? = null

    internal var dashboardUrlText: String? = null

    internal val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) AppTunnel.connect(this)
        else if (::vpnUi.isInitialized) vpnUi.denied()
    }

    internal val focusHandler = Handler(Looper.getMainLooper())

    internal var categoryLoad: Runnable? = null

    internal var epgWait: Runnable? = null

    internal var epgPrefetchWait: Runnable? = null

    internal val categoryAdapter = CategoryAdapter(
        onSelect = { category, immediate -> selectCategory(category, immediate) },
        detail = { cardDetail(it) },
        icon = { cardIcon(it) },
        onHold = { offerGroupLock(it) },
        locked = { GroupLock.locked(activeConfig, it.id) },
        previewLogos = { categoryPreviewLogos(it) },
        liveGroup = { isLiveBadgeGroup(it) }
    )
    internal val channelAdapter = ChannelAdapter(
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
        epgProvider = { epgCache[it.id] },
        onWatched = { markPosterWatched(it) }
    ).also { adapter ->
        adapter.continueLabel = { continueText(it) }
        adapter.continueProgress = { continueProgress(it) }
        adapter.posterRating = { posterRatingLabel(it) }
        adapter.posterMeta = { posterMetaLine(it) }
        adapter.unhealthy = { it.id in unhealthyIds }
    }

    internal lateinit var programAdapter: ProgramGuideAdapter

    internal var guideJob: Job? = null
    internal var guideEpgStamp: String = ""
    internal var savedBucketsLoaded = false
    internal val guideEpgBusy = HashSet<String>()
    internal val unhealthyIds = HashSet<String>()

    internal var guideScrollSync = false

    internal val halfHourMs = 30L * 60L * 1000L

    internal var guideHeaderHalfHour = -1L

    internal var lastGroupId: String? = null

    internal var lastGroupName: String? = null

    internal val vpnWatch: (TunnelState) -> Unit = { state ->
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            showVpnStatus(state)
            offerVpnConsent()
        }
    }

    internal val systemClock = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateGroupClock(force = true)
            alignGuideToSystemClock()
        }
    }

    internal val clockTick: Runnable = object : Runnable {
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

    internal var landOnOpened = false

    internal var openFocusTries = 0

    internal var openStamp = 0

    internal var channelsStamp = -1

    internal var skipGuideTransition = false

    internal var selectedCategoryName = ""

    internal var rowLongPress = false

    internal var groupHold = false

    internal var rowMenuAt = 0L

    internal var guideFocusRestore: Runnable? = null

    internal var guideDensity = PlaylistStore.GUIDE_COMFORTABLE


    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        hostOnCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                com.olympus.iptv.portal.RemotePad.commands.collect { command ->
                    runOnUiThread { applyDashboardRemote(command) }
                }
            }
        }
    }

    private fun applyDashboardRemote(command: com.olympus.iptv.portal.RemotePad.Command) {
        if (command.action == "play") {
            val id = command.text.trim()
            if (id.isEmpty()) return
            lifecycleScope.launch {
                val saved = playlist ?: return@launch
                val channel = withContext(Dispatchers.IO) {
                    val db = (application as OlympusIptvApp).catalogDb
                    db.readChannel(saved.id, com.olympus.iptv.playlist.CatalogDb.LIVE, id)
                        ?: db.readChannel(saved.id, com.olympus.iptv.playlist.CatalogDb.VOD, id)
                        ?: db.readChannel(saved.id, com.olympus.iptv.playlist.CatalogDb.SERIES, id)
                }
                if (channel != null && !isDestroyed) play(channel)
            }
            return
        }
        val code = when (command.action) {
            "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> android.view.KeyEvent.KEYCODE_BACK
            "text" -> {
                val typed = command.text.trim()
                if (typed.startsWith("http://") || typed.startsWith("https://")) {
                    val channel = com.olympus.iptv.data.Channel(
                        id = "pasted",
                        name = "Pasted stream",
                        streamUrl = typed,
                        kind = com.olympus.iptv.data.ContentKind.LIVE
                    )
                    startActivity(
                        com.olympus.iptv.ui.player.PlayerActivity.intent(
                            this,
                            channel,
                            "",
                            "",
                            0,
                            playlist?.id
                        )
                    )
                    return
                }
                if (typed.isBlank()) return
                startActivity(android.content.Intent(this, com.olympus.iptv.ui.search.SearchActivity::class.java).apply {
                    putExtra(EXTRA_PLAYLIST_ID, playlist?.id)
                    putExtra("query", typed)
                })
                return
            }
            else -> return
        }
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)
        dispatchKeyEvent(down)
        dispatchKeyEvent(up)
    }

    override fun onStart() {
        super.onStart()
        hostOnStart()
    }

    override fun onResume() {
        super.onResume()
        hostOnResume()
    }

    override fun onStop() {
        hostOnStop()
        super.onStop()
    }

    override fun onDestroy() {
        hostOnDestroy()
        super.onDestroy()
    }

    internal fun passKey(event: android.view.KeyEvent): Boolean = super.dispatchKeyEvent(event)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = hostDispatchKey(event)

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_SKIP_RESUME = "skip_resume"
        const val EXTRA_TUNE_ID = "tune_id"
        const val CARD_SPAN = 3
        const val PAGE_SIZE_TV = 200
        const val PAGE_SIZE_PHONE = 100
        const val PAGE_SIZE_HUGE = 20
        const val LARGE_LIVE_GROUP = 250
        const val HUGE_PREFETCH_REMAIN = 5
        const val DEFAULT_PREFETCH_REMAIN = 30
        const val MAX_EMPTY_PAGE_SKIPS = 40
        const val FOCUS_SETTLE_MS = 160L
        const val EPG_CACHE_CAP = 120
    }


}
