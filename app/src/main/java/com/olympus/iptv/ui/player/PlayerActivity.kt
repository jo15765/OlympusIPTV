package com.olympus.iptv.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.olympus.iptv.playlist.StreamHeaders
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.AspectRatioFrameLayout
import coil.load
import com.olympus.iptv.BuildConfig
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import java.io.IOException
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ChannelNames
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.PlayerDecoder
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.data.WatchEntry
import com.olympus.iptv.ui.ChannelNumberJump
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.Haptics
import com.olympus.iptv.ui.InsetsPad
import com.olympus.iptv.ui.browse.GuideFeel
import com.olympus.iptv.ui.cast.CastBridge
import com.olympus.iptv.ui.channelLogo
import com.olympus.iptv.databinding.ActivityPlayerBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.playlist.EpgRepository
import com.olympus.iptv.playlist.Scrobbler
import com.olympus.iptv.playlist.Subtitles
import com.olympus.iptv.playlist.TitleRatings
import com.olympus.iptv.playlist.LineupApplier
import java.util.ArrayDeque
import java.util.Locale
import com.olympus.iptv.playlist.EpgUtils
import com.olympus.iptv.vpn.AppTunnel
import com.olympus.iptv.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    internal lateinit var binding: ActivityPlayerBinding
    internal fun tracksReady(): Boolean = ::binding.isInitialized
    private lateinit var miniGuide: PlayerMiniGuide
    internal var player: ExoPlayer? = null

    internal var queue: List<Channel> = emptyList()
    internal var index: Int = 0
    internal var current: Channel? = null
    internal var playlistId: String? = null
    private var zapMode: String = ZAP_LIST
    private var categoryId: String? = null
    internal var stepping = false
    private var playJob: Job? = null
    private var progressJob: Job? = null
    private var nextPlayer: ExoPlayer? = null
    private var prevPlayer: ExoPlayer? = null
    private var idlePlayer: ExoPlayer? = null
    private var nextPreparedId: String? = null
    private var prevPreparedId: String? = null
    private var preloadJob: Job? = null
    private var streamsReleased = false
    private var recoverJob: Job? = null
    internal var countdownJob: Job? = null
    internal var nextLookup: Job? = null
    internal var introJob: Job? = null
    internal var subtitleJob: Job? = null
    /** Cancels in-flight EPG / catch-up / subtitle work when the user zaps away. */
    private var channelJob = SupervisorJob()
    private val zapEpgPrefetch = object : LinkedHashMap<String, EpgNowNext>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpgNowNext>?): Boolean = size > 8
    }
    internal var sidecar: MediaItem.SubtitleConfiguration? = null
    internal var pendingLanguage: String? = null
    internal var preferredAudio: String = PlaylistStore.DEFAULT_AUDIO_LANGUAGE
    internal var audioAppliedFor: String? = null
    internal var preferredSubtitle: String = PlaylistStore.SUBTITLE_OFF
    internal var subtitleAppliedFor: String? = null
    internal var nextIsEpisode = false
    internal var panelEmbedded = emptyList<StreamChoice>()
    internal var pendingNext: Channel? = null
    internal var resolvedSeries: List<Channel>? = null
    internal var countdownFor: String? = null
    internal var nextHanded = false
    private var advancing = false
    internal var playbackReady = false
    private var playbackToken = 0
    private var recoverAttempt = 0
    private var stallJob: Job? = null
    private var stallMarkPosition = Long.MIN_VALUE
    private var stallMarkAt = 0L
    private var stallHealthySince = 0L
    private var stallStrikes = 0
    private var stallBusyUntil = 0L
    private var stallFamily: String? = null
    private val stallTried = LinkedHashSet<String>()
    private val rebufferAt = ArrayDeque<Long>()
    private var lastVideoFrameAt = 0L
    private var watchingCatchup = false
    private var hadPlaybackReady = false
    private var bufferStartedAt = 0L
    private var bufferWatchToken = 0
    private var playbackWatch: Player.Listener? = null
    private var loadWatch: AnalyticsListener? = null
    private var watchedPlayer: ExoPlayer? = null
    private var filtersReady = false
    private var nameConfig = LineupConfig()
    private var providerName = ""
    private var hiddenChannels = emptySet<String>()
    private var hiddenGroups = emptySet<String>()
    private var hiddenCountries = emptySet<String>()
    private var includeInternational = false
    private var cachedPlaylist: SavedPlaylist? = null
    internal var pictureMode = PlaylistStore.PICTURE_FIT
    internal var autoFrameRate = true
    private var multiViewEnabled = false
    private var audioOnPrimary = true
    private var catchupUrl: String? = null
    private val miniTitles = HashMap<String, String>()
    private val miniProgress = HashMap<String, Int>()
    private var lastInputAt = android.os.SystemClock.elapsedRealtime()
    private var idlePromptShown = false
    private var secondaryPlayer: ExoPlayer? = null
    private var seekDragging = false
    private var seekJob: Job? = null
    internal var touchPrimary = false
    private var menuBaseBottom = 0
    private var overlayBaseTop = 0
    internal var sleepMinutes = 0
    internal var nightMode = false
    internal var radioOn = false
    internal var phoneControls: PhoneMediaControls? = null
    internal var savedBrightness = -1f
    internal val radioClock: Runnable = object : Runnable {
        override fun run() {
            if (!radioOn || isDestroyed) return
            paintRadioClock()
            hideHandler.postDelayed(this, 1_000L)
        }
    }
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal val audioDelay = DelayAudioProcessor()
    private var leveler: android.media.audiofx.DynamicsProcessing? = null
    private var lastBackAt = 0L
    internal var upNextToken = 0
    internal var skipIntroSeries = emptySet<String>()
    private var videoDecoder = PlayerDecoder.HARDWARE
    private var playersDecoder = PlayerDecoder.HARDWARE
    private var decoderDirty = false
    private var pipSwipeHome = true
    internal var scrobble = PlaylistStore.ScrobbleConfig(
        provider = Scrobbler.NONE,
        simklToken = "",
        simklClientId = "",
        traktToken = "",
        traktClientId = ""
    )
    internal var scrobbleChannel: Channel? = null
    internal var scrobbleJob: Job? = null
    internal var scrobbleStarted = false
    private var inPip = false
    internal var pipPending = false
    private var networkWatcher: NetworkWatcher? = null
    private var acceptReconnectAt = 0L
    private var lastReconnectAt = 0L
    private var enterPipOnReady = false
    internal val sleepFire = Runnable { stopForSleep() }
    internal val epgRepo: EpgRepository
        get() = (application as OlympusIptvApp).epgRepository

    internal val hideHandler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { hideTransportChrome(animated = true) }
    private val seekTick = object : Runnable {
        override fun run() {
            if (!::binding.isInitialized || isDestroyed) return
            val seekOpen = transportSeekVisible()
            val liveBehind = binding.btnGoLive.visibility == View.VISIBLE
            if (seekOpen) paintSeekBar()
            if (seekOpen || binding.playbackMenu.visibility == View.VISIBLE) paintGoLive()
            val playing = player?.isPlaying == true
            // Tick slower while paused; ExoPlayer listener drives updates while playing.
            val delay = when {
                !seekOpen && !liveBehind && binding.playbackMenu.visibility != View.VISIBLE -> return
                !playing || seekDragging -> 1_500L
                else -> 1_000L
            }
            hideHandler.postDelayed(this, delay)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
        touchPrimary = DeviceForm.isTouchPrimary(this)
        requestedOrientation = if (touchPrimary) {
            rememberedPhoneOrientation()
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.post {
            com.olympus.iptv.ui.RemoteTip.show(this, getString(R.string.remote_tip_player), binding.root as? android.view.ViewGroup)
        }
        miniGuide = PlayerMiniGuide(
            binding.miniGuide,
            binding.miniGuideList,
            channels = { queue },
            onTune = { position ->
                if (position in queue.indices) {
                    index = position
                    playCurrent()
                }
            },
            nowTitle = { miniTitles[it.id] },
            progress = { miniProgress[it.id] ?: 0 },
            sameShow = { channel ->
                com.olympus.iptv.playlist.SimilarChannels.same(
                    miniTitles[current?.id],
                    miniTitles[channel.id]
                )
            }
        )
        hideHandler.postDelayed(idleWatch, 60_000L)
        binding.miniGuideLast.setOnClickListener {
            miniGuide.hide()
            toggleLastChannel()
        }
        binding.miniGuideSplit.setOnClickListener {
            miniGuide.hide()
            if (current?.kind == ContentKind.LIVE) toggleMultiView()
        }
        if (touchPrimary) binding.miniGuideSplit.visibility = View.GONE
        binding.playerView.useController = false
        binding.playerView.setKeepContentOnPlayerReset(true)
        binding.playerViewB.setKeepContentOnPlayerReset(true)
        applyPicture(pictureMode)
        setupPhoneChrome()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handlePlayerBack()
            }
        })
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val mode = withContext(Dispatchers.IO) { store.pictureMode() }
            val frames = withContext(Dispatchers.IO) { store.autoFrameRate() }
            if (isDestroyed) return@launch
            pictureMode = mode
            autoFrameRate = frames
            applyPicture(mode)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                com.olympus.iptv.portal.RemotePad.commands.collect { command ->
                    if (isDestroyed) return@collect
                    applyRemote(command)
                }
            }
        }

        val remembered = (application as OlympusIptvApp).playbackQueue
        queue = remembered.ifEmpty {
            listOf(
                Channel(
                    id = intent.getStringExtra(EXTRA_ID).orEmpty(),
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    streamUrl = intent.getStringExtra(EXTRA_URL).orEmpty(),
                    logoUrl = intent.getStringExtra(EXTRA_LOGO)
                )
            )
        }
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        zapMode = intent.getStringExtra(EXTRA_ZAP) ?: ZAP_LIST
        categoryId = intent.getStringExtra(EXTRA_CATEGORY)
        enterPipOnReady = intent.getBooleanExtra(EXTRA_ENTER_PIP, false)
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        current = queue.getOrNull(index)

        // Prefer warm in-memory decoder cache — never block the main thread with runBlocking.
        videoDecoder = (application as OlympusIptvApp).playlistStore.videoDecoderCached()
            ?: PlayerDecoder.HARDWARE
        player = newPlayer().also { exo ->
            binding.playerView.player = exo
        }

        playCurrent(
            seedMeta = intent.getStringExtra(EXTRA_META),
            seedNext = intent.getStringExtra(EXTRA_NEXT),
            startAt = intent.getLongExtra(EXTRA_START_AT, -1L).takeIf { it >= 0L }
        )
        val playbackUrl = current?.streamUrl.orEmpty()
        val libraryPlayback = playbackUrl.startsWith("file:") || playbackUrl.startsWith("http://127.0.0.1")
        if (libraryPlayback) binding.btnRecord.visibility = View.GONE
        binding.btnMenuBack.setOnClickListener { handlePlayerBack() }
        binding.btnRecord.setOnClickListener {
            hidePlaybackMenu()
            offerRecording()
        }
        binding.btnAudio.setOnClickListener {
            hidePlaybackMenu()
            openAudio()
        }
        binding.btnSubtitles.setOnClickListener {
            hidePlaybackMenu()
            openSubtitles()
        }
        binding.btnPicture.setOnClickListener {
            hidePlaybackMenu()
            openPicture()
        }
        binding.btnLast.setOnClickListener {
            hidePlaybackMenu()
            toggleLastChannel()
        }
        binding.btnGoLive.setOnClickListener {
            hidePlaybackMenu()
            goLive()
        }
        binding.btnMultiView.setOnClickListener {
            hidePlaybackMenu()
            toggleMultiView()
        }
        binding.btnSleep.setOnClickListener {
            hidePlaybackMenu()
            openSleepTimer()
        }
        binding.btnCast.setOnClickListener {
            hidePlaybackMenu()
            CastBridge.showChooser(this, current)
        }
        binding.btnTrailer.setOnClickListener {
            hidePlaybackMenu()
            openTrailer()
        }
        binding.btnPip.setOnClickListener {
            hidePlaybackMenu()
            enterPip(manual = true)
        }
        binding.btnSkipIntro.setOnClickListener { onSkipIntro() }
        binding.btnPlayNext.setOnClickListener { playPendingNext() }
        binding.btnCancelNext.setOnClickListener { cancelNext() }
        binding.btnNextMenu.setOnClickListener { escapeToMenu() }
        binding.playerView.setOnClickListener { togglePlaybackChrome() }
        binding.playerViewB.setOnClickListener { togglePlaybackChrome() }
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val exo = player ?: return
                val duration = exo.duration.takeIf { it > 0 } ?: return
                binding.seekLabel.text = formatClock((duration * progress) / 1000L)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekDragging = true
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekDragging = false
                val exo = player ?: return
                val duration = exo.duration.takeIf { it > 0 } ?: return
                val progress = seekBar?.progress ?: return
                exo.seekTo((duration * progress) / 1000L)
            }
        })
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            nightMode = withContext(Dispatchers.IO) { store.nightMode() }
            player?.let { applyNightMode(it) }
            preferredAudio = withContext(Dispatchers.IO) { store.audioLanguage() }
            preferredSubtitle = withContext(Dispatchers.IO) { store.subtitleLanguage() }
            // Multi-view needs a second ExoPlayer; skip on phones to keep RAM free.
            multiViewEnabled = if (touchPrimary) {
                false
            } else {
                withContext(Dispatchers.IO) { store.multiView() }
            }
            skipIntroSeries = withContext(Dispatchers.IO) { store.skipIntroSeries() }
            pipSwipeHome = withContext(Dispatchers.IO) { store.pipSwipeHome() }
            scrobble = withContext(Dispatchers.IO) { store.scrobbleConfig() }
            val nextDecoder = withContext(Dispatchers.IO) { store.videoDecoder() }
            if (isDestroyed) return@launch
            if (nextDecoder != videoDecoder) {
                videoDecoder = nextDecoder
                decoderDirty = true
                discardSparePlayers()
            }
            binding.btnPip.visibility = if (canEnterPip()) View.VISIBLE else View.GONE
        }
        } catch (error: Throwable) {
            com.olympus.iptv.AppLog.error("Player", "Player failed to open", error)
            finish()
        }
    }

    private fun setupPhoneChrome() {
        if (!touchPrimary) {
            binding.btnMenuBack.visibility = View.GONE
            binding.phoneDock.visibility = View.GONE
            binding.touchTip.visibility = View.GONE
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding.btnPhoneBack.setOnClickListener { handlePlayerBack() }
        binding.btnPhonePlay.setOnClickListener {
            com.olympus.iptv.ui.Haptics.tick(binding.btnPhonePlay)
            togglePause()
            showTransportChrome()
        }
        binding.btnPhoneMenu.setOnClickListener { showPlaybackMenu() }
        binding.channelName.setOnClickListener {
            if (current?.kind == ContentKind.LIVE && current?.seriesId.isNullOrBlank()) {
                openMiniGuide()
            }
        }
        binding.btnSeekPlay.setOnClickListener {
            togglePause()
            showTransportChrome()
        }
        binding.phoneSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val exo = player ?: return
                val duration = exo.duration.takeIf { it > 0 } ?: return
                binding.seekLabel.text = formatClock((duration * progress) / 1000L)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekDragging = true
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekDragging = false
                val exo = player ?: return
                val duration = exo.duration.takeIf { it > 0 } ?: return
                val progress = seekBar?.progress ?: return
                exo.seekTo((duration * progress) / 1000L)
            }
        })
        showTouchTipOnce()
        layoutPhonePortraitDock()
        menuBaseBottom = binding.playbackMenu.paddingBottom
        overlayBaseTop = binding.overlay.paddingTop
        val menuLeft = binding.playbackMenu.paddingLeft
        val menuTop = binding.playbackMenu.paddingTop
        val menuRight = binding.playbackMenu.paddingRight
        val overlayLeft = binding.overlay.paddingLeft
        val overlayBottom = binding.overlay.paddingBottom
        val overlayRight = binding.overlay.paddingRight
        InsetsPad.apply(
            binding.playbackMenu,
            bottom = true,
            left = true,
            right = true,
            baseLeft = menuLeft,
            baseTop = menuTop,
            baseRight = menuRight,
            baseBottom = menuBaseBottom
        )
        InsetsPad.apply(
            binding.overlay,
            top = true,
            left = true,
            right = true,
            baseLeft = overlayLeft,
            baseTop = overlayBaseTop,
            baseRight = overlayRight,
            baseBottom = overlayBottom
        )
        val seekLeft = binding.seekPane.paddingLeft
        val seekTop = binding.seekPane.paddingTop
        val seekRight = binding.seekPane.paddingRight
        val seekBottom = binding.seekPane.paddingBottom
        InsetsPad.apply(
            binding.seekPane,
            left = true,
            right = true,
            baseLeft = seekLeft,
            baseTop = seekTop,
            baseRight = seekRight,
            baseBottom = seekBottom
        )
        val nextParams = binding.nextPanel.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (nextParams != null) {
            InsetsPad.applyMargins(
                binding.nextPanel,
                bottom = true,
                right = true,
                baseLeft = nextParams.leftMargin,
                baseTop = nextParams.topMargin,
                baseRight = nextParams.rightMargin,
                baseBottom = nextParams.bottomMargin
            )
        }
        InsetsPad.apply(
            binding.subtitlePanel,
            top = true,
            bottom = true,
            left = true,
            baseLeft = binding.subtitlePanel.paddingLeft,
            baseTop = binding.subtitlePanel.paddingTop,
            baseRight = binding.subtitlePanel.paddingRight,
            baseBottom = binding.subtitlePanel.paddingBottom
        )
        binding.playbackMenu.orientation = LinearLayout.HORIZONTAL
        binding.btnMenuBack.visibility = View.VISIBLE
        setPhoneSystemBarsHidden(true)
        val row = binding.phoneDock.getChildAt(1) as? LinearLayout
        if (row != null) {
            val cast = android.widget.TextView(this)
            val height = (52 * resources.displayMetrics.density).toInt()
            cast.layoutParams = LinearLayout.LayoutParams(0, height, 1f).apply {
                marginStart = (10 * resources.displayMetrics.density).toInt()
            }
            cast.gravity = Gravity.CENTER
            cast.setBackgroundResource(R.drawable.bg_button_secondary)
            cast.setText(R.string.cast)
            cast.setTextColor(getColor(R.color.text_primary))
            cast.textSize = 16f
            cast.setOnClickListener { CastBridge.showChooser(this, current) }
            row.addView(cast)
        }
        PlayerTouchGestures.attach(this, binding.playerView)
        phoneControls = PhoneMediaControls(this).also { it.start() }
    }

    internal fun setPhoneSystemBarsHidden(hide: Boolean) {
        if (!touchPrimary) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    internal fun togglePlaybackChrome() {
        if (binding.subtitlePanel.visibility == View.VISIBLE) {
            closeSubtitlePanel()
            return
        }
        if (binding.playbackMenu.visibility == View.VISIBLE) {
            hideTransportChrome(animated = false)
            return
        }
        if (transportShowing()) {
            hideTransportChrome(animated = false)
        } else {
            showTransportChrome()
        }
    }

    private fun transportSeekVisible(): Boolean =
        ::binding.isInitialized && (
            binding.seekPane.visibility == View.VISIBLE ||
                binding.phoneSeek.visibility == View.VISIBLE
            )

    private fun transportShowing(): Boolean =
        ::binding.isInitialized && (
            binding.overlay.visibility == View.VISIBLE ||
                transportSeekVisible() ||
                binding.playbackMenu.visibility == View.VISIBLE
            )

    internal fun showTransportChrome() {
        if (!::binding.isInitialized || isDestroyed) return
        val channel = current
        binding.overlay.visibility = View.VISIBLE
        binding.overlay.alpha = 1f
        applySeekVisibility(channel)
        paintSeekBar()
        paintSeekPlayLabel()
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        hideHandler.removeCallbacks(seekTick)
        val pausedLive = isLiveNow(channel) && player?.playWhenReady == false
        if (transportSeekVisible() || pausedLive) {
            hideHandler.postDelayed(seekTick, 500L)
        }
        val hideMs = if (binding.playbackMenu.visibility == View.VISIBLE) 8_000L else TRANSPORT_HIDE_MS
        hideHandler.removeCallbacks(hideOverlay)
        hideHandler.postDelayed(hideOverlay, hideMs)
    }

    private fun hideTransportChrome(animated: Boolean) {
        if (!::binding.isInitialized) return
        hidePlaybackMenu()
        hideHandler.removeCallbacks(hideOverlay)
        hideHandler.removeCallbacks(seekTick)
        binding.seekPane.visibility = View.GONE
        binding.phoneSeek.visibility = View.GONE
        if (touchPrimary) {
            binding.phoneDock.visibility = View.GONE
            setPhoneSystemBarsHidden(true)
        }
        if (animated && binding.overlay.visibility == View.VISIBLE) {
            binding.overlay.animate().alpha(0f).setDuration(250).withEndAction {
                if (!isDestroyed && ::binding.isInitialized) {
                    binding.overlay.visibility = View.GONE
                    binding.overlay.alpha = 1f
                }
            }.start()
        } else {
            binding.overlay.animate().cancel()
            binding.overlay.visibility = View.GONE
            binding.overlay.alpha = 1f
        }
    }

    private fun applySeekVisibility(channel: Channel?) {
        val portrait = touchPrimary &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (channel == null || isLiveNow(channel)) {
            binding.seekPane.visibility = View.GONE
            binding.phoneSeek.visibility = View.GONE
            if (touchPrimary && portrait) {
                binding.phoneDock.visibility = View.VISIBLE
                layoutPhonePortraitDock()
            } else if (touchPrimary) {
                binding.phoneDock.visibility = View.GONE
            }
            return
        }
        if (portrait) {
            binding.seekPane.visibility = View.GONE
            binding.phoneSeek.visibility = View.VISIBLE
            binding.phoneDock.visibility = View.VISIBLE
            layoutPhonePortraitDock()
        } else {
            binding.seekPane.visibility = View.VISIBLE
            binding.phoneSeek.visibility = if (touchPrimary) View.VISIBLE else View.GONE
            if (touchPrimary) binding.phoneDock.visibility = View.GONE
        }
    }

    private fun paintSeekPlayLabel() {
        if (!::binding.isInitialized) return
        val playing = player?.playWhenReady == true
        val label = if (playing) R.string.player_pause else R.string.player_play
        binding.btnSeekPlay.setText(label)
        paintPhonePlayLabel()
    }

    private fun playbackErrorOpen(): Boolean =
        ::binding.isInitialized && binding.playbackErrorCard.visibility == View.VISIBLE

    private fun handlePlayerBack() {
        if (radioOn) {
            leaveRadioMode()
            return
        }
        if (::miniGuide.isInitialized && miniGuide.isOpen()) {
            miniGuide.hide()
            return
        }
        if (binding.subtitlePanel.visibility == View.VISIBLE) {
            closeSubtitlePanel()
            return
        }
        if (playbackErrorOpen()) {
            hidePlaybackError()
            armBrowseBack()
            finish()
            return
        }
        if (binding.playbackMenu.visibility == View.VISIBLE || transportShowing()) {
            hideTransportChrome(animated = false)
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val live = current?.kind == ContentKind.LIVE && current?.seriesId.isNullOrBlank()
        if (live) {
            if (now - lastBackAt <= 450L) {
                hideHandler.removeCallbacks(leaveAfterBack)
                lastBackAt = 0L
                toggleLastChannel()
                return
            }
            lastBackAt = now
            hideHandler.removeCallbacks(leaveAfterBack)
            hideHandler.postDelayed(leaveAfterBack, 450L)
            return
        }
        armBrowseBack()
        finish()
    }

    private val leaveAfterBack = Runnable {
        if (isDestroyed) return@Runnable
        armBrowseBack()
        finish()
    }

    private fun resetChannelScopedWork() {
        channelJob.cancel()
        channelJob = SupervisorJob()
        subtitleJob?.cancel()
        subtitleJob = null
        preloadJob?.cancel()
        nextLookup?.cancel()
        countdownJob?.cancel()
        introJob?.cancel()
        recoverJob?.cancel()
        stallJob?.cancel()
        countdownFor = null
        upNextToken += 1
    }

    internal fun channelScope() = lifecycleScope + channelJob

    internal fun playCurrent(
        seedMeta: String? = null,
        seedNext: String? = null,
        startAt: Long? = null,
        failover: Boolean = false
    ) {
        val seed = current ?: queue.getOrNull(index)
        if (seed == null) {
            finish()
            return
        }
        val upcomingUrls = listOfNotNull(
            queue.getOrNull(index + 1)?.streamUrl,
            queue.getOrNull(index - 1)?.streamUrl,
            queue.getOrNull(index + 2)?.streamUrl
        )
        if (upcomingUrls.isNotEmpty()) com.olympus.iptv.playlist.HttpClients.warmStreamHosts(upcomingUrls)
        acceptReconnectAt = SystemClock.elapsedRealtime() + 2_000L
        resetChannelScopedWork()
        playJob?.cancel()
        recoverJob?.cancel()
        advancing = true
        playbackReady = false
        sidecar = null
        pendingLanguage = null
        audioAppliedFor = null
        closeSubtitlePanel()
        playbackToken += 1
        recoverAttempt = 0
        if (!failover) {
            stallTried.clear()
            stallFamily = null
        }
        stallStrikes = 0
        stallMarkPosition = Long.MIN_VALUE
        stallMarkAt = 0L
        stallHealthySince = 0L
        rebufferAt.clear()
        lastVideoFrameAt = 0L
        watchingCatchup = false
        stallBusyUntil = SystemClock.elapsedRealtime() + 6_000L
        clearSpare(1)
        clearSpare(-1)
        playJob = lifecycleScope.launch {
            try {
            val savedId = playlistId
            if (!savedId.isNullOrBlank()) ensureFilters(savedId)
            val loaded = hydrate(seed)
            if (loaded.streamUrl.isBlank()) {
                PlaybackTrace.blankUrl(loaded)
                Toast.makeText(this@PlayerActivity, R.string.playback_no_url, Toast.LENGTH_LONG).show()
                armBrowseBack()
                finish()
                return@launch
            }
            val alias = lineupName(loaded, seed)
            providerName = loaded.name
            val channel = loaded
            current = channel
            phoneControls?.publish()
            val app = application as OlympusIptvApp
            app.lastPlayed = channel
            app.lastPlayedPlaylistId = playlistId
            app.appScope.launch { WatchNext.publish(app, channel, playlistId) }
            hadPlaybackReady = false
            bufferStartedAt = 0L
            bufferWatchToken += 1
            // Do not pin Connecting here — wait for real buffering / clear on first frame.
            showConnectingBadge(false)
            hidePlaybackError()
            PlaybackTrace.start(channel)
            if (zapMode == ZAP_LIST && index in queue.indices) {
                val updated = queue.toMutableList()
                updated[index] = channel
                queue = updated
            }
            binding.btnAudio.visibility = View.GONE
            binding.btnSubtitles.visibility = if (!channel.seriesId.isNullOrBlank() && channel.streamUrl.isNotBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            binding.btnLast.visibility =
                if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.btnMultiView.visibility =
                if (!touchPrimary && channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            binding.btnTrailer.visibility =
                if (channel.kind == ContentKind.VOD || !channel.seriesId.isNullOrBlank()) View.VISIBLE else View.GONE
            binding.btnPip.visibility = if (canEnterPip()) View.VISIBLE else View.GONE
            binding.btnGoLive.visibility = View.GONE
            subtitleAppliedFor = null
            binding.channelName.text = alias
            binding.channelMeta.text = aliasCaption(seedMeta, loaded.name, alias)
            binding.channelNext.text = aliasCaption(seedNext, loaded.name, alias)
            binding.overlayLogo.load(channel.logoUrl) {
                channelLogo((64 * resources.displayMetrics.density).toInt().coerceAtLeast(1))
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }
            flashOverlay()
            refreshEpgForCurrent()
            rememberCurrent()
            scrobbleStart(channel)
            applyChannelDelay(channel)
            if (channel.kind != ContentKind.LIVE || !channel.seriesId.isNullOrBlank()) {
                enrichTmdb(channel, alias)
            }
            val resumeAt = startAt ?: resumePosition(channel)
            val playAt = if (
                startAt == null &&
                shouldAutoSkip(channel) &&
                resumeAt < EpisodeSequence.INTRO_MS
            ) {
                EpisodeSequence.INTRO_MS
            } else {
                resumeAt
            }
            val warmed = takeGuideWarm(channel.id) ?: takeSpare(channel.id)
            if (warmed != null) {
                val previous = player
                player = warmed
                MobileBitrateCap.apply(this@PlayerActivity, warmed)
                warmed.addListener(trackListener)
                watchPlaybackEvents(warmed)
                binding.playerView.player = warmed
                if (playAt > 0L) warmed.seekTo(playAt)
                warmed.playWhenReady = true
                syncConnectingFromPlayer(warmed)
                StreamSessions.set(channel.id, channel.name, "Playing")
                binding.playerView.post { syncConnectingFromPlayer(warmed) }
                if (previous != null && previous !== warmed) recycle(previous)
                holdRadioSurface()
            } else {
                val exo = player ?: return@launch
                watchPlaybackEvents(exo)
                exo.stop()
                exo.setMediaItem(mediaItem(channel))
                if (playAt > 0L) exo.seekTo(playAt)
                exo.prepare()
                applyNightMode(exo)
                exo.playWhenReady = true
                holdRadioSurface()
                StreamSessions.set(channel.id, channel.name, "Playing")
                binding.playerView.post { syncConnectingFromPlayer(exo) }
                binding.playerView.postDelayed({
                    val active = player
                    if (active === exo) syncConnectingFromPlayer(exo)
                }, 800L)
            }
            configureSeekPane(channel)
            syncMultiView(channel)
            watchPlayback(channel)
            val family = com.olympus.iptv.playlist.ChannelFailover.stem(channel.name).ifBlank { channel.id }
            stallFamily = family
            if (failover) stallTried.add(channel.id)
            if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) armStallWatch()
            binding.channelName.text = lineupName(loaded, seed)
            preloadAround(channel.id)
            } finally {
                advancing = false
                stepping = false
            }
        }
    }

    private fun lineupName(loaded: Channel, seed: Channel): String {
        val ruled = ChannelNames.present(loaded, nameConfig)
        if (ruled != loaded.name) return ruled
        if (seed.id == loaded.id && seed.name.isNotBlank() && seed.name != loaded.name) return seed.name
        return ruled.ifBlank { loaded.name }
    }

    private fun aliasProgram(title: String, raw: String, alias: String): String {
        if (raw.isBlank() || alias.equals(raw, ignoreCase = true)) return title
        return if (title.trim().equals(raw.trim(), ignoreCase = true)) alias else title
    }

    private fun aliasCaption(line: String?, raw: String, alias: String): String {
        if (line.isNullOrBlank() || raw.isBlank() || alias.equals(raw, ignoreCase = true)) return line.orEmpty()
        val split = line.lastIndexOf("  ")
        if (split < 0) return if (line.trim().equals(raw.trim(), ignoreCase = true)) alias else line
        val title = line.substring(split + 2)
        if (!title.trim().equals(raw.trim(), ignoreCase = true)) return line
        return line.substring(0, split + 2) + alias
    }

    private fun programLine(program: com.olympus.iptv.data.EpgProgram?, raw: String, alias: String): String? {
        program ?: return null
        return "${program.timeRangeLabel()}  ${aliasProgram(program.title, raw, alias)}"
    }

    internal fun mediaItem(channel: Channel, title: String = lineupName(channel, channel)): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(channel.streamUrl)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
        sidecar?.let { builder.setSubtitleConfigurations(listOf(it)) }
        if (channel.kind == ContentKind.LIVE) {
            // Never speed up past 1.00x (which eats the live buffer cushion),
            // and allow gentle 0.98x drift recovery to absorb jitter without spooling.
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.00f)
                    .build()
            )
        }
        return builder.build()
    }

    private fun newPlayer(): ExoPlayer {
        val mode = PlayerDecoder.normalize(videoDecoder)
        playersDecoder = mode
        decoderDirty = false
        val control = PlayerBuffers.live(DeviceForm.isLowRam(this))
        val http = StreamHeaders.httpFactory(this, { playlistId }, { current })
        val loadPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3) {
            override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val base = super.getRetryDelayMsFor(loadErrorInfo)
                return if (base == androidx.media3.common.C.TIME_UNSET) base else base.coerceAtMost(2_500L)
            }
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(http)
            .setLoadErrorHandlingPolicy(loadPolicy)
        val adaptiveFactory = androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(
            15_000,
            5_000,
            15_000,
            0.65f
        )
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this, adaptiveFactory).apply {
            setParameters(
                buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
            )
        }
        val liveSpeedControl = androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.98f)
            .setFallbackMaxPlaybackSpeed(1.00f)
            .build()
        return ExoPlayer.Builder(this)
            .setRenderersFactory(OffsetRenderersFactory(this, mode, audioDelay))
            .setTrackSelector(trackSelector)
            .setLoadControl(control)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                exo.videoChangeFrameRateStrategy = androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                MobileBitrateCap.apply(this, exo)
                exo.addListener(trackListener)
                watchPlaybackEvents(exo)
                applyNightMode(exo)
            }
    }

    private fun applyNightMode(exo: ExoPlayer) {
        leveler?.let { runCatching { it.release() } }
        leveler = null
        if (!nightMode) return
        leveler = AudioLeveler.attach(exo.audioSessionId)
    }

    internal fun chooseNightMode(enabled: Boolean) {
        nightMode = enabled
        player?.let { applyNightMode(it) }
        val app = application as OlympusIptvApp
        app.appScope.launch { runCatching { app.playlistStore.saveNightMode(enabled) } }
    }

    private fun discardSparePlayers() {
        clearSpare(1)
        clearSpare(-1)
        idlePlayer?.release()
        idlePlayer = null
        secondaryPlayer?.release()
        secondaryPlayer = null
    }

    private fun watchPlaybackEvents(exo: ExoPlayer) {
        val previousPlayer = watchedPlayer
        val previousWatch = playbackWatch
        val previousLoad = loadWatch
        if (previousPlayer != null && previousPlayer !== exo) {
            previousWatch?.let { runCatching { previousPlayer.removeListener(it) } }
            previousLoad?.let { runCatching { previousPlayer.removeAnalyticsListener(it) } }
        }
        if (exo === watchedPlayer && playbackWatch != null) return
        val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (player !== exo) return
                    val channel = current
                    if (channel != null &&
                        channel.kind == ContentKind.LIVE &&
                        channel.seriesId.isNullOrBlank()
                    ) {
                        val http = PlaybackTrace.httpStatus(error)
                        val catchUp = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || http == 404
                        if (!catchUp) PlaybackTrace.error(channel, error, recovering = true)
                        recoverLive(exo, channel, error)
                    } else {
                        PlaybackTrace.error(channel, error, recovering = false)
                        toastPlaybackIssue(error)
                    }
                }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== exo) return
                if (isPlaying) onBufferingEnded()
                if (transportSeekVisible()) {
                    paintSeekBar()
                    paintSeekPlayLabel()
                }
            }

            override fun onRenderedFirstFrame() {
                if (player !== exo) return
                lastVideoFrameAt = SystemClock.elapsedRealtime()
                onBufferingEnded()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (player !== exo) return
                val channel = current
                when (state) {
                    Player.STATE_BUFFERING -> onBufferingStarted()
                    Player.STATE_READY -> onBufferingEnded()
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        bufferStartedAt = 0L
                        bufferWatchToken += 1
                        if (state == Player.STATE_IDLE && !playbackErrorOpen()) {
                            showConnectingBadge(false)
                        }
                    }
                }
                if (state == Player.STATE_ENDED &&
                    channel != null &&
                    channel.kind == ContentKind.LIVE &&
                    channel.seriesId.isNullOrBlank() &&
                    !playbackErrorOpen()
                ) {
                    recoverLive(exo, channel, null)
                    return
                }
                if (state == Player.STATE_ENDED &&
                    playbackReady &&
                    channel != null &&
                    channel.streamUrl.isNotBlank() &&
                    (channel.kind != ContentKind.LIVE || !channel.seriesId.isNullOrBlank())
                ) {
                    playbackReady = false
                    offerNext(channel)
                }
                if (state == Player.STATE_READY && exo.playWhenReady) {
                    playbackReady = true
                    recoverAttempt = 0
                    armUpNext(exo, channel)
                    refreshStreamTracks()
                    if (binding.channelMeta.text == getString(R.string.playback_reconnecting)) {
                        refreshEpgQuietly()
                    }
                }
            }
        }
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        val analytics = object : AnalyticsListener {
            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int
            ) {
                if (player === exo && frameCount > 0) {
                    lastVideoFrameAt = SystemClock.elapsedRealtime()
                }
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                if (player !== exo || wasCanceled) return
                val status = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                PlaybackTrace.loadFailed(current, status, error.message)
            }
        }
        playbackWatch = listener
        loadWatch = analytics
        watchedPlayer = exo
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
    }

    private fun syncConnectingFromPlayer(exo: ExoPlayer) {
        when {
            exo.isPlaying || exo.playbackState == Player.STATE_READY -> onBufferingEnded()
            exo.playbackState == Player.STATE_BUFFERING -> onBufferingStarted()
            else -> showConnectingBadge(false)
        }
    }

    private fun onBufferingStarted() {
        if (bufferStartedAt == 0L) bufferStartedAt = SystemClock.elapsedRealtime()
        val token = ++bufferWatchToken
        val started = bufferStartedAt
        val live = current?.kind == ContentKind.LIVE && current?.seriesId.isNullOrBlank()
        // After first paint, live rebuffers briefly on jitter — never tear down a working stream.
        // Initial connect also waits briefly so READY/first-frame can clear without a flash.
        val pictureUp = hadPlaybackReady && live && player?.videoFormat != null
        if (pictureUp) showConnectingBadge(false)
        val delayShow = when {
            hadPlaybackReady && live -> LIVE_REBUFFER_BADGE_MS
            hadPlaybackReady -> REBUFFER_BADGE_MS
            else -> INITIAL_BADGE_MS
        }
        if (!pictureUp) hideHandler.postDelayed({
            if (isDestroyed || token != bufferWatchToken) return@postDelayed
            val active = player ?: return@postDelayed
            if (active.isPlaying || active.playbackState == Player.STATE_READY || active.videoFormat != null && live) {
                onBufferingEnded()
                return@postDelayed
            }
            if (active.playbackState != Player.STATE_BUFFERING) return@postDelayed
            showConnectingBadge(true)
        }, delayShow)
        if (!pictureUp) hideHandler.postDelayed({
            if (isDestroyed || token != bufferWatchToken) return@postDelayed
            val active = player ?: return@postDelayed
            if (active.playbackState != Player.STATE_BUFFERING || active.isPlaying) return@postDelayed
            val waited = SystemClock.elapsedRealtime() - started
            if (waited >= STILL_SPOOL_MS) {
                PlaybackTrace.stillBuffering(current, waited)
                paintConnectingBadge(waited)
            }
        }, STILL_SPOOL_MS)
        if (!pictureUp) hideHandler.postDelayed({
            if (isDestroyed || token != bufferWatchToken) return@postDelayed
            val active = player ?: return@postDelayed
            if (active.playbackState != Player.STATE_BUFFERING || active.isPlaying) return@postDelayed
            paintConnectingBadge(SystemClock.elapsedRealtime() - started)
        }, 2_000L)
        if (!pictureUp) hideHandler.postDelayed({
            if (isDestroyed || token != bufferWatchToken) return@postDelayed
            val active = player ?: return@postDelayed
            if (active.playbackState != Player.STATE_BUFFERING || active.isPlaying) return@postDelayed
            val channel = current ?: return@postDelayed
            if (live && active.videoFormat != null) {
                recoverFrozenLive(active, channel)
                return@postDelayed
            }
            if (recoverAttempt >= 2 && !DeviceForm.isTelevision(this) && videoDecoder != PlayerDecoder.SOFTWARE) {
                videoDecoder = PlayerDecoder.SOFTWARE
                decoderDirty = true
                recoverAttempt = 0
                runCatching { active.stop() }
                discardSparePlayers()
                runCatching { active.release() }
                if (player === active) player = null
                playCurrent()
                return@postDelayed
            }
            if (recoverAttempt >= 2 && DeviceForm.isTelevision(this)) {
                recoverAttempt = 0
                runCatching { active.stop() }
                discardSparePlayers()
                runCatching { active.release() }
                if (player === active) player = null
                playCurrent()
                return@postDelayed
            }
            recoverLive(active, channel, null)
        }, 12_000L)
    }

    private fun onBufferingEnded() {
        val started = bufferStartedAt
        bufferStartedAt = 0L
        bufferWatchToken += 1
        showConnectingBadge(false)
        if (binding.channelMeta.text == getString(R.string.playback_reconnecting)) {
            val meta = intent.getStringExtra(EXTRA_META).orEmpty()
            binding.channelMeta.text = meta.ifBlank { current?.name.orEmpty() }
            refreshEpgQuietly()
        }
        val firstReady = !hadPlaybackReady
        if (started <= 0L) {
            hadPlaybackReady = true
            if (firstReady) maybeEnterPipOnReady()
            return
        }
        val waited = SystemClock.elapsedRealtime() - started
        val initial = !hadPlaybackReady
        hadPlaybackReady = true
        val minLog = if (initial) INITIAL_SPOOL_LOG_MS else REBUFFER_LOG_MS
        if (waited >= minLog) PlaybackTrace.spool(current, initial, waited)
        if (firstReady) maybeEnterPipOnReady()
    }

    private fun maybeEnterPipOnReady() {
        if (!enterPipOnReady) return
        enterPipOnReady = false
        binding.root.post { enterPip(manual = false) }
    }

    private fun showConnectingBadge(show: Boolean) {
        if (!::binding.isInitialized) return
        val active = player
        val isHealthy = active != null && (active.isPlaying || active.playbackState == Player.STATE_READY)
        if (!show || isHealthy) {
            binding.connectingBadge.visibility = View.GONE
            binding.connectingBadge.setText(R.string.playback_connecting)
            if (binding.channelMeta.text == getString(R.string.playback_reconnecting)) {
                val meta = intent.getStringExtra(EXTRA_META).orEmpty()
                binding.channelMeta.text = meta.ifBlank { current?.name.orEmpty() }
                refreshEpgQuietly()
            }
        } else {
            paintConnectingBadge(
                if (bufferStartedAt > 0L) SystemClock.elapsedRealtime() - bufferStartedAt else 0L
            )
            binding.connectingBadge.visibility = View.VISIBLE
        }
    }

    private fun paintConnectingBadge(waitedMs: Long) {
        if (!::binding.isInitialized) return
        binding.connectingBadge.text = when {
            recoverAttempt >= 2 || waitedMs >= 8_000L ->
                getString(R.string.playback_connecting_poor)
            waitedMs >= STILL_SPOOL_MS ->
                getString(R.string.playback_connecting_slow)
            waitedMs >= 2_000L ->
                getString(R.string.playback_connecting_wait, (waitedMs / 1000L).toInt().coerceAtLeast(1))
            else -> getString(R.string.playback_connecting)
        }
    }

    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            refreshStreamTracks()
            matchFrameRate()
        }
    }

    private fun recoverLive(exo: ExoPlayer, channel: Channel, error: PlaybackException?) {
        val token = playbackToken
        recoverJob?.cancel()
        val behind = error?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        val http = PlaybackTrace.httpStatus(error)
        val catchUp = behind || http == 404
        recoverAttempt += 1
        if (catchUp) PlaybackTrace.liveCatchUp(channel, recoverAttempt, behind) else PlaybackTrace.recover(channel, recoverAttempt, behind)
        // 403/401 are provider denials — retrying forever freezes the session and spams the log.
        val hardFail = http == 401 || http == 403 || recoverAttempt >= MAX_LIVE_RECOVER
        if (error != null && !catchUp) noteStreamFailure(channel)
        if (hardFail) {
            val sibling = com.olympus.iptv.playlist.ChannelFailover.alternate(
                channel,
                (application as OlympusIptvApp).playbackQueue
            )
            if (sibling != null) {
                Toast.makeText(this, R.string.playback_backup, Toast.LENGTH_SHORT).show()
                playById(sibling.id)
                return
            }
            runCatching { exo.stop() }
            showConnectingBadge(false)
            toastPlaybackIssue(error)
            return
        }
        if (playbackErrorOpen()) return
        binding.channelMeta.text = getString(R.string.playback_reconnecting)
        binding.connectingBadge.setText(R.string.playback_reconnecting_pill)
        binding.connectingBadge.visibility = View.VISIBLE
        val wait = 1_000L shl (recoverAttempt - 1).coerceAtMost(3)
        recoverJob = lifecycleScope.launch {
            delay(wait.coerceAtMost(8_000L))
            if (token != playbackToken || player !== exo || isDestroyed) return@launch
            hadPlaybackReady = false
            bufferStartedAt = 0L
            bufferWatchToken += 1
            if (catchUp || channel.kind == ContentKind.LIVE) {
                reloadLiveEdge(exo, channel)
                return@launch
            }
            exo.playWhenReady = true
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun toastPlaybackIssue(error: PlaybackException?) {
        val http = PlaybackTrace.httpStatus(error)
        val advice = PlaybackTrace.advice(http, error?.errorCode ?: -1)
        val message = when (advice) {
            PlaybackTrace.ACCOUNT -> getString(R.string.playback_rejected)
            PlaybackTrace.BACKUP -> getString(R.string.playback_backup)
            else -> {
                val code = error?.errorCode ?: -1
                val provider = when {
                    error == null -> true
                    code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
                    code in 2_000..3_999 -> true
                    code in 6_000..6_999 -> true
                    code in 4_000..5_999 -> false
                    else -> true
                }
                if (provider) getString(R.string.playback_issue_provider) else getString(R.string.playback_issue_app)
            }
        }
        showPlaybackError(message, advice)
    }

    private fun showPlaybackError(message: String, advice: String = "") {
        if (!::binding.isInitialized) return
        showConnectingBadge(false)
        hidePlaybackMenu()
        binding.playbackErrorBody.text = message
        binding.playbackErrorCard.visibility = View.VISIBLE
        when (advice) {
            PlaybackTrace.ACCOUNT -> {
                binding.playbackErrorRetry.setText(R.string.playback_check_account)
                binding.playbackErrorRetry.setOnClickListener {
                    hidePlaybackError()
                    finish()
                }
            }
            PlaybackTrace.BACKUP -> {
                binding.playbackErrorRetry.setText(R.string.playback_switch_backup)
                binding.playbackErrorRetry.setOnClickListener {
                    hidePlaybackError()
                    switchToBackup()
                }
            }
            else -> {
                binding.playbackErrorRetry.setText(R.string.playback_error_retry)
                binding.playbackErrorRetry.setOnClickListener {
                    if (touchPrimary) Haptics.tick(binding.playbackErrorRetry)
                    hidePlaybackError()
                    playCurrent()
                }
            }
        }
        listOf(
            binding.playbackErrorClose,
            binding.playbackErrorNext,
            binding.playbackErrorRetry
        ).forEach { button ->
            button.isFocusable = true
            button.isFocusableInTouchMode = true
            button.isClickable = true
        }
        binding.playbackErrorNext.setOnClickListener {
            if (touchPrimary) Haptics.tick(binding.playbackErrorNext)
            hidePlaybackError()
            nextChannel()
        }
        binding.playbackErrorClose.setOnClickListener {
            hidePlaybackError()
            finish()
        }
        binding.playbackErrorRetry.post {
            if (binding.playbackErrorCard.visibility == View.VISIBLE) {
                binding.playbackErrorRetry.requestFocus()
            }
        }
    }

    private fun armStallWatch() {
        stallJob?.cancel()
        stallMarkPosition = Long.MIN_VALUE
        stallMarkAt = 0L
        stallHealthySince = 0L
        val watchedId = current?.id
        stallJob = lifecycleScope.launch {
            while (isActive) {
                delay(1_000L)
                val exo = player ?: continue
                val channel = current ?: continue
                if (channel.id != watchedId) return@launch
                if (!isLiveNow(channel) || !exo.playWhenReady || playbackErrorOpen()) {
                    stallMarkPosition = Long.MIN_VALUE
                    stallMarkAt = 0L
                    stallHealthySince = 0L
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (now < stallBusyUntil) {
                    stallMarkAt = 0L
                    continue
                }
                val playing = exo.playbackState == Player.STATE_READY && exo.isPlaying
                if (playing) {
                    showConnectingBadge(false)
                    if (::binding.isInitialized && binding.channelMeta.text == getString(R.string.playback_reconnecting)) {
                        val meta = intent.getStringExtra(EXTRA_META).orEmpty()
                        binding.channelMeta.text = meta.ifBlank { current?.name.orEmpty() }
                        refreshEpgQuietly()
                    }
                    if (stallHealthySince == 0L) stallHealthySince = now
                    stallMarkAt = 0L
                    if (now - stallHealthySince >= 15_000L) stallStrikes = 0
                    continue
                }
                stallHealthySince = 0L
                if (stallMarkAt == 0L) {
                    stallMarkAt = now
                    continue
                }
                val stuckMs = now - stallMarkAt
                if (stuckMs >= 3_500L && !radioOn) {
                    showConnectingBadge(true)
                }
                val trulyStuck = exo.playbackState == Player.STATE_BUFFERING ||
                    exo.playbackState == Player.STATE_IDLE ||
                    (exo.playbackState == Player.STATE_READY && !exo.isPlaying)
                if (trulyStuck && stuckMs >= 20_000L) {
                    recoverFrozenLive(exo, channel)
                }
            }
        }
    }

    private fun recoverFrozenLive(exo: ExoPlayer, channel: Channel) {
        val now = SystemClock.elapsedRealtime()
        if (player !== exo || current?.id != channel.id || isDestroyed) return
        if (!exo.playWhenReady || !isLiveNow(channel)) return
        stallBusyUntil = now + 10_000L
        stallStrikes += 1
        stallMarkAt = now
        stallMarkPosition = exo.currentPosition
        stallTried.add(channel.id)
        PlaybackTrace.recover(channel, stallStrikes, behindLive = true)
        if (::binding.isInitialized) {
            binding.channelMeta.text = getString(R.string.playback_reconnecting)
            paintConnectingBadge(now - bufferStartedAt.let { if (it > 0) it else now })
            binding.connectingBadge.setText(R.string.playback_reconnecting_pill)
            binding.connectingBadge.visibility = View.VISIBLE
        }
        if (!watchingCatchup && stallStrikes >= 2) {
            switchToBackup(stallTried) { switched ->
                if (!switched && !isDestroyed && player === exo && current?.id == channel.id) {
                    rebuildLivePlayer(exo, channel)
                }
            }
            return
        }
        reloadLiveEdge(exo, channel)
    }

    /** Fully tears down a wedged MediaCodec/ExoPlayer instance and brings up a clean player at the live edge. */
    private fun rebuildLivePlayer(stale: ExoPlayer, channel: Channel) {
        if (player !== stale || isDestroyed) return
        runCatching {
            val fresh = newPlayer()
            player = fresh
            binding.playerView.player = fresh
            runCatching { stale.stop() }
            runCatching { stale.release() }
            lastVideoFrameAt = 0L
            hadPlaybackReady = false
            bufferStartedAt = 0L
            bufferWatchToken += 1
            fresh.setMediaItem(mediaItem(channel))
            if (!watchingCatchup) fresh.seekToDefaultPosition()
            fresh.prepare()
            fresh.playWhenReady = true
            holdRadioSurface()
        }.onFailure { error ->
            PlaybackTrace.loadFailed(channel, null, error.message)
        }
    }

    private fun reloadLiveEdge(exo: ExoPlayer, channel: Channel) {
        if (player !== exo || isDestroyed) return
        runCatching {
            val item = exo.currentMediaItem ?: mediaItem(channel)
            exo.stop()
            exo.setMediaItem(item)
            if (!watchingCatchup) exo.seekToDefaultPosition()
            exo.prepare()
            exo.playWhenReady = true
        }.onFailure { error ->
            PlaybackTrace.loadFailed(channel, null, error.message)
        }
    }

    private fun switchToBackup(skip: Set<String> = emptySet(), onResult: ((Boolean) -> Unit)? = null) {
        val channel = current ?: run {
            onResult?.invoke(false)
            return
        }
        val savedId = playlistId ?: run {
            onResult?.invoke(false)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as OlympusIptvApp).catalogDb
            val group = channel.groupTitle ?: channel.categoryId
            val siblings = if (group.isNullOrBlank()) {
                emptyList()
            } else {
                db.readCategoryChannels(savedId, CatalogDb.LIVE, group)
            }
            var backup = com.olympus.iptv.playlist.ChannelFailover.alternate(channel, siblings, skip)
            if (backup == null && skip.size > 1) {
                backup = com.olympus.iptv.playlist.ChannelFailover.alternate(channel, siblings, setOf(channel.id))
            }
            if (backup == null) {
                withContext(Dispatchers.Main) { onResult?.invoke(false) }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (isDestroyed || current?.id != channel.id) {
                    onResult?.invoke(false)
                    return@withContext
                }
                val found = queue.indexOfFirst { it.id == backup.id }
                if (found >= 0) index = found
                stallTried.add(channel.id)
                stallTried.add(backup.id)
                current = backup
                playCurrent(failover = true)
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.failover_switched, backup.name),
                    Toast.LENGTH_SHORT
                ).show()
                onResult?.invoke(true)
            }
        }
    }

    private fun hidePlaybackError() {
        if (!::binding.isInitialized) return
        binding.playbackErrorCard.visibility = View.GONE
    }

    private fun refreshEpgQuietly() {
        val playing = current ?: return
        if (playing.kind != ContentKind.LIVE) return
        val savedId = playlistId ?: return
        lifecycleScope.launch {
            val playlist = savedPlaylist(savedId) ?: return@launch
            val channel = playing.copy(epgChannelId = playing.epgChannelId ?: playing.id)
            val epg = runCatching { epgRepo.nowNext(playlist, channel) }.getOrDefault(EpgNowNext())
            if (current?.id != playing.id) return@launch
            binding.channelMeta.text = epg.nowLine()
            binding.channelNext.text = epg.nextLine()
        }
    }

    private fun takeGuideWarm(channelId: String): ExoPlayer? {
        val app = application as OlympusIptvApp
        val warm = app.warmPlayer ?: return null
        app.warmPlayer = null
        val id = app.warmChannelId
        app.warmChannelId = null
        if (id != channelId) {
            warm.release()
            return null
        }
        warm.volume = 1f
        return warm
    }

    private fun takeSpare(id: String): ExoPlayer? {
        if (nextPreparedId == id) {
            val ready = nextPlayer
            nextPlayer = null
            nextPreparedId = null
            ready?.volume = 1f
            return ready
        }
        if (prevPreparedId == id) {
            val ready = prevPlayer
            prevPlayer = null
            prevPreparedId = null
            ready?.volume = 1f
            return ready
        }
        return null
    }

    private fun recycle(exo: ExoPlayer) {
        if (exo === player || exo === nextPlayer || exo === prevPlayer || exo === secondaryPlayer) return
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        idlePlayer?.release()
        idlePlayer = exo
    }

    private fun obtainSpare(): ExoPlayer {
        if (decoderDirty || playersDecoder != PlayerDecoder.normalize(videoDecoder)) {
            discardSparePlayers()
            decoderDirty = false
        }
        val idle = idlePlayer
        if (idle != null) {
            idlePlayer = null
            return idle
        }
        return newPlayer()
    }

    private fun prepareSpare(direction: Int, channel: Channel?) {
        // Phones: one player only — no next/prev pre-buffer to save decoder/RAM.
        if (touchPrimary || com.olympus.iptv.ui.DeviceForm.isTelevision(this)) {
            clearSpare(direction)
            return
        }
        if (channel == null || channel.kind != ContentKind.LIVE || channel.streamUrl.isBlank() || channel.id == current?.id) {
            clearSpare(direction)
            return
        }
        val preparedId = if (direction > 0) nextPreparedId else prevPreparedId
        if (preparedId == channel.id) return
        clearSpare(direction)
        val spare = obtainSpare()
        spare.volume = 0f
        spare.setMediaItem(mediaItem(channel))
        spare.prepare()
        spare.playWhenReady = false
        StreamSessions.set(channel.id, channel.name, if (direction > 0) "Next channel" else "Previous channel")
        if (direction > 0) {
            nextPlayer = spare
            nextPreparedId = channel.id
        } else {
            prevPlayer = spare
            prevPreparedId = channel.id
        }
    }

    private fun clearSpare(direction: Int) {
        val spare = if (direction > 0) nextPlayer else prevPlayer
        val prepared = if (direction > 0) nextPreparedId else prevPreparedId
        if (direction > 0) {
            nextPlayer = null
            nextPreparedId = null
        } else {
            prevPlayer = null
            prevPreparedId = null
        }
        if (!prepared.isNullOrBlank()) StreamSessions.remove(prepared)
        if (spare != null) recycle(spare)
    }

    private fun preloadAround(playingId: String) {
        val now = current ?: return
        if (now.kind != ContentKind.LIVE) return
        if (touchPrimary || com.olympus.iptv.ui.DeviceForm.isTelevision(this)) {
            clearSpare(1)
            clearSpare(-1)
            return
        }
        preloadJob?.cancel()
        preloadJob = lifecycleScope.launch {
            val lowRam = DeviceForm.isLowRam(this@PlayerActivity)
            delay(if (lowRam) 3_000L else 150L)
            if (!isActive || current?.id != playingId) return@launch
            val nextChannel = withContext(Dispatchers.IO) { adjacent(now, 1) }
            if (!isActive || current?.id != playingId) return@launch
            if (nextChannel != null) {
                warmEpg(nextChannel)
                if (!lowRam) {
                    prepareSpare(1, nextChannel)
                } else {
                    clearSpare(1)
                }
            } else {
                clearSpare(1)
            }
            clearSpare(-1)
        }
    }

    private fun warmEpg(channel: Channel) {
        if (channel.kind != ContentKind.LIVE) return
        val savedId = playlistId ?: return
        lifecycleScope.launch {
            val playlist = savedPlaylist(savedId) ?: return@launch
            runCatching { epgRepo.nowNext(playlist, channel.copy(epgChannelId = channel.epgChannelId ?: channel.id)) }
        }
    }

    private suspend fun adjacent(now: Channel, direction: Int): Channel? {
        if (zapMode == ZAP_LIST) {
            if (queue.size <= 1) return null
            val found = queue.indexOfFirst { it.id == now.id }
            val i = if (found >= 0) found else index
            val nextIndex = if (direction < 0) {
                if (i == 0) queue.lastIndex else i - 1
            } else {
                (i + 1) % queue.size
            }
            return hydrate(queue[nextIndex])
        }
        return neighbor(now, direction)
    }

    private suspend fun hydrate(channel: Channel): Channel {
        if (channel.streamUrl.isNotBlank()) return channel
        val savedId = playlistId ?: return channel
        val db = (application as OlympusIptvApp).catalogDb
        return withContext(Dispatchers.IO) {
            db.readChannel(savedId, bucketOf(channel), channel.id)
                ?: db.readChannel(savedId, CatalogDb.LIVE, channel.id)
                ?: db.readChannel(savedId, CatalogDb.VOD, channel.id)
                ?: db.readChannel(savedId, CatalogDb.SERIES, channel.id)
                ?: channel
        }
    }

    private suspend fun neighbor(channel: Channel, direction: Int): Channel? {
        val savedId = playlistId ?: return null
        ensureFilters(savedId)
        val category = if (zapMode == ZAP_GROUP) categoryId else null
        val app = application as OlympusIptvApp
        return withContext(Dispatchers.IO) {
            app.catalogDb.readNeighbor(
                savedId,
                bucketOf(channel),
                category,
                channel.number,
                channel.name,
                channel.id,
                direction,
                hiddenChannels,
                hiddenGroups,
                hiddenCountries,
                includeInternational
            ) { candidate ->
                LineupApplier.keep(candidate, hiddenChannels, hiddenGroups, hiddenCountries, includeInternational)
            }
        }
    }

    private suspend fun ensureFilters(savedId: String) {
        if (filtersReady) return
        val app = application as OlympusIptvApp
        val staged = app.stagedLineup?.takeIf { it.first == savedId }?.second
        val stored = withContext(Dispatchers.IO) { app.playlistStore.lineup(savedId) }
        val config = when {
            staged == null -> stored
            (stored.revision ?: 0L) > (staged.revision ?: 0L) -> stored.also { app.stagedLineup = savedId to it }
            else -> staged
        }
        nameConfig = config
        epgRepo.useNameEpg(config.nameEpg == true)
        hiddenChannels = config.hiddenChannels.orEmpty().toSet()
        hiddenGroups = config.hiddenGroups.orEmpty().toSet()
        hiddenCountries = config.hiddenCountries.orEmpty().toSet()
        includeInternational = config.includeInternational == true
        filtersReady = true
    }

    internal suspend fun savedPlaylist(savedId: String): SavedPlaylist? {
        cachedPlaylist?.let { return it }
        val store = (application as OlympusIptvApp).playlistStore
        val found = store.session.first()?.takeIf { it.id == savedId }
            ?: store.playlists.first().firstOrNull { it.id == savedId }
        cachedPlaylist = found
        return found
    }

    private fun bucketOf(channel: Channel): String = when {
        !channel.seriesId.isNullOrBlank() -> CatalogDb.SERIES
        channel.kind == ContentKind.LIVE -> CatalogDb.LIVE
        else -> CatalogDb.VOD
    }

    private fun rememberCurrent() {
        val savedId = playlistId ?: return
        val channel = current ?: queue.getOrNull(index) ?: return
        if (channel.id.isBlank()) return
        val app = application as OlympusIptvApp
        app.appScope.launch {
            runCatching {
                app.playlistStore.rememberChannel(savedId, channel.id)
                if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) {
                    app.playlistStore.rememberWatch(savedId, watchEntry(channel, 0L, 0L))
                }
            }
        }
    }

    internal suspend fun resumePosition(channel: Channel): Long {
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) return 0L
        val savedId = playlistId ?: return 0L
        return withContext(Dispatchers.IO) {
            (application as OlympusIptvApp).playlistStore.recent(savedId)
                .firstOrNull { it.channelId == channel.id }
                ?.takeIf { it.canResume() }
                ?.positionMs
                ?: 0L
        }
    }

    private var lastSavedWatchAt = -1L
    private var lastSavedWatchId: String? = null

    private fun watchPlayback(channel: Channel) {
        progressJob?.cancel()
        lastSavedWatchAt = -1L
        lastSavedWatchId = null
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) return
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(8_000)
                saveWatch(channel)
            }
        }
    }

    internal fun saveWatch(channel: Channel) {
        if (channel.streamUrl.startsWith("file:") || channel.streamUrl.startsWith("content:")) {
            val position = player?.currentPosition?.coerceAtLeast(0L) ?: return
            com.olympus.iptv.record.RecordingProgress.remember(this, channel.id, position)
        }
        val savedId = playlistId ?: return
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) return
        val exo = player ?: return
        val app = application as OlympusIptvApp
        val position = exo.currentPosition.coerceAtLeast(0L)
        val duration = exo.duration.takeIf { it > 0L } ?: 0L
        val finished = duration > 0L && position * 100L >= duration * 95L
        if (!finished &&
            lastSavedWatchId == channel.id &&
            lastSavedWatchAt >= 0L &&
            kotlin.math.abs(position - lastSavedWatchAt) < 30_000L
        ) {
            return
        }
        lastSavedWatchId = channel.id
        lastSavedWatchAt = position
        app.appScope.launch {
            val entry = watchEntry(channel, position, duration)
            runCatching { app.playlistStore.rememberWatch(savedId, entry) }
            val email = runCatching { app.playlistStore.session.first()?.email?.trim().orEmpty() }.getOrDefault("")
            if (email.isNotBlank()) {
                runCatching {
                    val deviceId = app.playlistStore.deviceId()
                    com.olympus.iptv.playlist.LineupClient(app).pushWatchProgress(email, deviceId, entry)
                }
            }
            if (channel.kind != ContentKind.LIVE || !channel.seriesId.isNullOrBlank()) {
                TvLauncherBridge.publishContinue(app, channel, savedId, position, duration)
            }
            if (finished) {
                runCatching { app.catalogDb.markWatched(savedId, channel.id, channel.seriesId, channel.season) }
            }
        }
    }

    private fun watchEntry(channel: Channel, position: Long, duration: Long) = WatchEntry(
        channelId = channel.id,
        seriesId = channel.seriesId,
        name = channel.name,
        section = when {
            !channel.seriesId.isNullOrBlank() -> "series"
            channel.kind == ContentKind.LIVE -> "live"
            else -> "vod"
        },
        positionMs = position,
        durationMs = duration,
        updatedAt = System.currentTimeMillis()
    )

    private fun refreshEpgForCurrent() {
        val playing = current ?: return
        if (playing.kind != ContentKind.LIVE) return
        val savedId = playlistId ?: return
        lifecycleScope.launch {
            val playlist = savedPlaylist(savedId) ?: return@launch
            val channel = playing.copy(epgChannelId = playing.epgChannelId ?: playing.id)
            val epg = runCatching { epgRepo.nowNext(playlist, channel) }.getOrDefault(EpgNowNext())
            val raw = providerName.ifBlank { channel.name }
            val alias = lineupName(channel, channel)
            binding.channelName.text = alias
            binding.channelMeta.text = programLine(epg.now, raw, alias) ?: "No EPG"
            binding.channelNext.text = epg.next?.let { "Next: ${programLine(it, raw, alias)}" }.orEmpty()
            offerCatchup(playlist, channel, epg.now)
            flashOverlay()
        }
    }

    private fun nextChannel() = step(1)

    private fun previousChannel() = step(-1)

    private fun toggleLastChannel() {
        val savedId = playlistId ?: return
        val now = current ?: return
        if (now.kind != ContentKind.LIVE || !now.seriesId.isNullOrBlank()) return
        if (stepping) return
        cancelNext()
        stepping = true
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            val previousId = withContext(Dispatchers.IO) {
                app.playlistStore.previousChannelId(savedId)
            }
            if (previousId.isNullOrBlank() || previousId == now.id) {
                stepping = false
                Toast.makeText(this@PlayerActivity, R.string.last_channel_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val listed = queue.indexOfFirst { it.id == previousId }
            if (listed >= 0) {
                index = listed
                current = queue[listed]
                playCurrent()
                return@launch
            }
            val loaded = withContext(Dispatchers.IO) {
                app.catalogDb.readChannel(savedId, CatalogDb.LIVE, previousId)
            }
            if (loaded == null || loaded.streamUrl.isBlank()) {
                stepping = false
                Toast.makeText(this@PlayerActivity, R.string.last_channel_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            current = loaded
            playCurrent()
        }
    }

    internal fun step(direction: Int) {
        if (stepping) return
        cancelNext()
        if (zapMode == ZAP_LIST) {
            if (queue.size <= 1) return
            index = if (direction < 0) {
                if (index == 0) queue.lastIndex else index - 1
            } else {
                (index + 1) % queue.size
            }
            current = queue.getOrNull(index)
            stepping = true
            if (touchPrimary) Haptics.tick(binding.root)
            current?.let { showZapOsd(it) }
            playCurrent()
            return
        }
        val now = current ?: return
        stepping = true
        lifecycleScope.launch {
            val next = runCatching { neighbor(now, direction) }.getOrNull()
            if (next == null) {
                stepping = false
                return@launch
            }
            current = next
            if (touchPrimary) Haptics.tick(binding.root)
            showZapOsd(next)
            playCurrent()
        }
    }

    private val hideZapBanner = Runnable {
        if (::binding.isInitialized && !isDestroyed) binding.zapBanner.visibility = View.GONE
    }
    private var zapToken = 0

    private fun showZapOsd(channel: Channel) {
        if (!::binding.isInitialized || isDestroyed) return
        val token = ++zapToken
        val alias = lineupName(channel, channel).ifBlank { channel.name }
        binding.zapBanner.visibility = View.VISIBLE
        binding.zapTitle.text = alias
        val cached = zapEpgPrefetch[channel.id]
        paintZapEpg(channel, alias, cached)
        binding.zapLogo.load(channel.logoUrl) {
            channelLogo((48 * resources.displayMetrics.density).toInt().coerceAtLeast(1))
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }
        hideHandler.removeCallbacks(hideZapBanner)
        hideHandler.postDelayed(hideZapBanner, 3_500L)
        flashOverlay()
        if (channel.kind != ContentKind.LIVE) return
        val savedId = playlistId ?: return
        channelScope().launch {
            val fromDisk = withContext(Dispatchers.IO) {
                val epgId = channel.epgChannelId ?: channel.id
                val programs = (application as OlympusIptvApp).catalogDb.readEpg(savedId, epgId)?.second.orEmpty()
                if (programs.isEmpty()) null else EpgUtils.nowNext(programs)
            }
            if (token != zapToken || isDestroyed) return@launch
            if (fromDisk != null) {
                zapEpgPrefetch[channel.id] = fromDisk
                paintZapEpg(channel, alias, fromDisk)
            }
            val playlist = savedPlaylist(savedId) ?: return@launch
            val lookup = channel.copy(epgChannelId = channel.epgChannelId ?: channel.id)
            val epg = runCatching { epgRepo.nowNext(playlist, lookup) }.getOrDefault(fromDisk ?: EpgNowNext())
            if (token != zapToken || isDestroyed) return@launch
            zapEpgPrefetch[channel.id] = epg
            paintZapEpg(channel, alias, epg)
            prefetchNeighborZapEpg(channel)
        }
    }

    private fun paintZapEpg(channel: Channel, alias: String, epg: EpgNowNext?) {
        if (!::binding.isInitialized || isDestroyed) return
        val raw = channel.name
        val now = epg?.now
        binding.zapNow.text = now?.let { aliasProgram(it.title, raw, alias) } ?: if (epg == null) "…" else getString(R.string.epg_none)
        binding.zapNext.text = epg?.next
            ?.let { getString(R.string.zap_up_next, aliasProgram(it.title, raw, alias)) }
            .orEmpty()
        val span = (now?.endMillis ?: 0L) - (now?.startMillis ?: 0L)
        binding.zapProgress.progress = if (now != null && span > 0L) {
            (((System.currentTimeMillis() - now.startMillis) * 1000L) / span).toInt().coerceIn(0, 1000)
        } else {
            0
        }
    }

    private fun prefetchNeighborZapEpg(channel: Channel) {
        val savedId = playlistId ?: return
        if (channel.kind != ContentKind.LIVE) return
        channelScope().launch {
            val neighbors = listOfNotNull(
                runCatching { neighbor(channel, -1) }.getOrNull(),
                runCatching { neighbor(channel, 1) }.getOrNull()
            )
            withContext(Dispatchers.IO) {
                for (next in neighbors) {
                    if (zapEpgPrefetch.containsKey(next.id)) continue
                    val epgId = next.epgChannelId ?: next.id
                    val programs = (application as OlympusIptvApp).catalogDb.readEpg(savedId, epgId)?.second.orEmpty()
                    if (programs.isNotEmpty()) {
                        zapEpgPrefetch[next.id] = EpgUtils.nowNext(programs)
                    }
                }
            }
        }
    }

    private fun flashOverlay() {
        showTransportChrome()
    }

    private var swallowMenuClick = false

    internal fun menuButtonFocused(): Boolean =
        binding.btnMenuBack.isFocused ||
            binding.btnAudio.isFocused ||
            binding.btnSubtitles.isFocused ||
            binding.btnRecord.isFocused ||
            binding.btnPicture.isFocused ||
            binding.btnLast.isFocused ||
            binding.btnGoLive.isFocused ||
            binding.btnMultiView.isFocused ||
            binding.btnSleep.isFocused ||
            binding.btnCast.isFocused ||
            binding.btnTrailer.isFocused ||
            binding.btnPip.isFocused ||
            binding.btnSkipIntro.isFocused

    private fun showPlaybackMenu() {
        paintGoLive()
        paintSleepLabel()
        if (touchPrimary) {
            binding.btnMenuBack.visibility = View.VISIBLE
            setPhoneSystemBarsHidden(false)
            layoutPhoneMenu()
        } else {
            binding.btnMenuBack.visibility = View.GONE
        }
        binding.playbackMenu.visibility = View.VISIBLE
        showTransportChrome()
        hideHandler.removeCallbacks(hideOverlay)
        hideHandler.postDelayed(hideOverlay, 8_000L)
        val shown = listOf(
            binding.btnMenuBack,
            binding.btnAudio,
            binding.btnSubtitles,
            binding.btnRecord,
            binding.btnPicture,
            binding.btnLast,
            binding.btnGoLive,
            binding.btnMultiView,
            binding.btnSleep,
            binding.btnCast,
            binding.btnTrailer,
            binding.btnPip
        ).filter { it.visibility == View.VISIBLE }
        shown.forEachIndexed { index, button ->
            val previous = shown[if (index == 0) 0 else index - 1]
            val next = shown[if (index == shown.lastIndex) index else index + 1]
            button.nextFocusLeftId = previous.id
            button.nextFocusRightId = next.id
            button.nextFocusUpId = button.id
            button.nextFocusDownId = button.id
        }
        if (!touchPrimary) shown.firstOrNull()?.requestFocus()
        ViewCompat.requestApplyInsets(binding.playbackMenu)
        ViewCompat.requestApplyInsets(binding.overlay)
    }

    private fun layoutPhoneMenu() {
        if (!touchPrimary) return
        val narrow = resources.configuration.screenWidthDp < 700
        if (!narrow) {
            binding.playbackMenu.orientation = LinearLayout.HORIZONTAL
            binding.playbackMenu.gravity = Gravity.CENTER
            return
        }
        binding.playbackMenu.orientation = LinearLayout.VERTICAL
        binding.playbackMenu.gravity = Gravity.CENTER_HORIZONTAL
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (i in 0 until binding.playbackMenu.childCount) {
            val child = binding.playbackMenu.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            params.width = LinearLayout.LayoutParams.MATCH_PARENT
            params.marginStart = 0
            params.topMargin = if (i == 0) 0 else gap
            child.layoutParams = params
        }
    }

    internal fun hidePlaybackMenu() {
        if (!::binding.isInitialized) return
        binding.playbackMenu.visibility = View.GONE
        if (touchPrimary && binding.overlay.visibility != View.VISIBLE) setPhoneSystemBarsHidden(true)
    }

    private fun isLiveNow(channel: Channel? = current): Boolean {
        val item = channel ?: return false
        if (catchupUrl != null) return false
        return item.kind == ContentKind.LIVE && item.seriesId.isNullOrBlank()
    }

    private fun configureSeekPane(channel: Channel) {
        // Seek/play chrome is transient — show briefly, then auto-hide.
        showTransportChrome()
        if (isLiveNow(channel) && touchPrimary) layoutPhonePortraitDock()
    }

    private fun paintSeekBar() {
        if (!::binding.isInitialized || seekDragging) return
        val exo = player ?: return
        val duration = exo.duration
        if (duration <= 0L) {
            binding.seekLabel.text = formatClock(exo.currentPosition.coerceAtLeast(0L))
            paintPhonePlayLabel()
            return
        }
        val position = exo.currentPosition.coerceIn(0L, duration)
        val progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
        binding.seekBar.progress = progress
        binding.phoneSeek.progress = progress
        binding.seekLabel.text = "${formatClock(position)} / ${formatClock(duration)}"
        paintPhonePlayLabel()
    }

    private fun layoutPhonePortraitDock() {
        if (!touchPrimary || !::binding.isInitialized) return
        val portrait = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (!portrait) {
            if (binding.phoneDock.visibility == View.VISIBLE &&
                binding.overlay.visibility != View.VISIBLE &&
                binding.playbackMenu.visibility != View.VISIBLE
            ) {
                binding.phoneDock.visibility = View.GONE
            }
            return
        }
        if (binding.overlay.visibility == View.VISIBLE || binding.playbackMenu.visibility == View.VISIBLE) {
            binding.phoneDock.visibility = View.VISIBLE
            InsetsPad.apply(
                binding.phoneDock,
                bottom = true,
                left = true,
                right = true
            )
            paintPhonePlayLabel()
        }
    }

    private fun paintPhonePlayLabel() {
        if (!touchPrimary || !::binding.isInitialized) return
        val playing = player?.playWhenReady == true
        binding.btnPhonePlay.setText(if (playing) R.string.player_pause else R.string.player_play)
    }

    private fun showTouchTipOnce() {
        if (!touchPrimary) return
        val prefs = playerPrefs()
        if (prefs.getBoolean("touch_tip_seen", false)) return
        binding.touchTip.visibility = View.VISIBLE
        binding.touchTip.postDelayed({
            if (!isDestroyed && ::binding.isInitialized) binding.touchTip.visibility = View.GONE
            prefs.edit().putBoolean("touch_tip_seen", true).apply()
        }, 4_500L)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (touchPrimary) {
            layoutPhonePortraitDock()
            current?.let { configureSeekPane(it) }
            if (binding.playbackMenu.visibility == View.VISIBLE) layoutPhoneMenu()
        }
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%d:%02d", minutes, seconds)
    }

    private fun paintGoLive() {
        if (!::binding.isInitialized) return
        val exo = player
        val live = isLiveNow()
        val behind = live && exo != null && exo.isCurrentMediaItemSeekable &&
            (exo.duration > 0L && exo.currentPosition < exo.duration - 3_000L || !exo.playWhenReady)
        binding.btnGoLive.visibility = if (behind) View.VISIBLE else View.GONE
    }

    private fun goLive() {
        val exo = player ?: return
        if (!isLiveNow()) return
        watchingCatchup = false
        exo.seekToDefaultPosition()
        exo.playWhenReady = true
        paintGoLive()
    }

    private fun togglePause() {
        val exo = player ?: return
        exo.playWhenReady = !exo.playWhenReady
        paintGoLive()
        paintSeekPlayLabel()
        if (isLiveNow() && !exo.playWhenReady) {
            hideHandler.removeCallbacks(seekTick)
            hideHandler.post(seekTick)
        }
        phoneControls?.publish()
    }

    private val hideCatchup = Runnable {
        catchupUrl = null
        if (::binding.isInitialized) binding.catchupStart.visibility = View.GONE
    }

    private fun offerCatchup(
        playlist: com.olympus.iptv.data.SavedPlaylist,
        channel: Channel,
        program: com.olympus.iptv.data.EpgProgram?
    ) {
        val url = if (program != null && channel.supportsCatchup()) {
            com.olympus.iptv.playlist.CatchupUrls.url(playlist, channel, program)
        } else {
            null
        }
        val show = CatchupPrompt.shouldOffer(System.currentTimeMillis(), program, url != null)
        catchupUrl = if (show) url else null
        if (!::binding.isInitialized) return
        binding.catchupStart.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        binding.catchupStart.setText(R.string.catchup_from_start)
        hideHandler.removeCallbacks(hideCatchup)
        hideHandler.postDelayed(hideCatchup, CatchupPrompt.DISMISS_MS)
    }

    private fun playCatchupFromStart() {
        val url = catchupUrl ?: return
        val channel = current ?: return
        val exo = player ?: return
        hideHandler.removeCallbacks(hideCatchup)
        catchupUrl = null
        binding.catchupStart.visibility = View.GONE
        binding.connectingBadge.setText(R.string.playback_catchup_rewind)
        binding.connectingBadge.visibility = View.VISIBLE
        watchingCatchup = true
        exo.setMediaItem(mediaItem(channel.copy(streamUrl = url)))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun noteStreamFailure(channel: Channel) {
        val savedId = playlistId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as OlympusIptvApp).catalogDb
            val failures = runCatching { db.recordStreamFailure(savedId, channel.id) }.getOrDefault(0)
            if (failures < 2) return@launch
            val group = channel.groupTitle ?: channel.categoryId ?: return@launch
            val siblings = db.readCategoryChannels(savedId, CatalogDb.LIVE, group)
            val backup = com.olympus.iptv.playlist.ChannelFailover.alternate(channel, siblings) ?: return@launch
            db.clearStreamFailure(savedId, backup.id)
            withContext(Dispatchers.Main) {
                if (isDestroyed || current?.id != channel.id) return@withContext
                val found = queue.indexOfFirst { it.id == backup.id }
                if (found >= 0) index = found
                current = backup
                playCurrent()
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.failover_switched, backup.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private var lastAppliedFrameRateHz = 0f

    internal fun matchFrameRate() {
        if (!autoFrameRate || Build.VERSION.SDK_INT < 30 || !::binding.isInitialized) return
        if (isLiveNow()) return
        val fps = player?.videoFormat?.frameRate ?: return
        val target = FrameRateMatch.targetHz(fps)
        if (target <= 0f || target == lastAppliedFrameRateHz) return
        val surfaceView = binding.playerView.videoSurfaceView as? android.view.SurfaceView ?: return
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return
        lastAppliedFrameRateHz = target
        runCatching {
            surface.setFrameRate(target, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    private fun switchMultiAudio(primary: Boolean): Boolean {
        if (!multiViewEnabled || binding.playerViewB.visibility != View.VISIBLE) return false
        audioOnPrimary = primary
        player?.volume = if (primary) 1f else 0f
        secondaryPlayer?.volume = if (primary) 0f else 1f
        paintActivePane()
        return true
    }

    private fun paintActivePane() {
        binding.playerView.foreground = paneBorder(audioOnPrimary)
        binding.playerViewB.foreground = paneBorder(!audioOnPrimary)
    }

    private fun paneBorder(active: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(if (active) 8 else 2, if (active) 0xFFE0B15A.toInt() else 0x33FFFFFF)
        }

    private fun applyRemote(command: com.olympus.iptv.portal.RemotePad.Command) {
        when (command.action) {
            "up" -> dispatchPlayerRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> dispatchPlayerRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> dispatchPlayerRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> dispatchPlayerRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "ok" -> dispatchPlayerRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
            "back" -> handlePlayerBack()
            "text" -> {
                val typed = command.text.trim()
                if (typed.startsWith("http://") || typed.startsWith("https://")) playPasted(typed)
                else zapByName(typed)
            }
            "play" -> playById(command.text)
        }
    }

    private fun dispatchPlayerRemoteKey(code: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            code,
            0,
            0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            android.view.InputDevice.SOURCE_DPAD
        )
        val up = KeyEvent(
            now,
            now + 10L,
            KeyEvent.ACTION_UP,
            code,
            0,
            0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            android.view.InputDevice.SOURCE_DPAD
        )
        val handledDown = dispatchKeyEvent(down)
        val handledUp = dispatchKeyEvent(up)
        if (handledDown) return
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> 0
        }
        if (direction != 0) {
            val overlayOpen = binding.playbackMenu.visibility == View.VISIBLE ||
                binding.subtitlePanel.visibility == View.VISIBLE ||
                playbackErrorOpen() ||
                transportShowing()
            if (overlayOpen) {
                val root = window.decorView as? android.view.ViewGroup
                val focused = currentFocus
                val next = focused?.focusSearch(direction)
                    ?: root?.let { android.view.FocusFinder.getInstance().findNextFocus(it, focused, direction) }
                if (next != null && next != focused) {
                    next.requestFocus()
                    return
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val exo = player
                if (exo != null && !isLiveNow() && exo.isCurrentMediaItemSeekable) {
                    val dur = exo.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    exo.seekTo((exo.currentPosition + 10_000L).coerceAtMost(dur))
                    showTransportChrome()
                } else {
                    showTransportChrome()
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                val exo = player
                if (exo != null && !isLiveNow() && exo.isCurrentMediaItemSeekable) {
                    exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                    showTransportChrome()
                }
            }
            return
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER && !handledUp) {
            currentFocus?.performClick()
        }
    }

    private fun playById(id: String) {
        val wanted = id.trim()
        if (wanted.isBlank()) return
        val bare = if (wanted.contains(':')) wanted.substringAfter(':') else wanted
        val found = queue.indexOfFirst { it.id == bare || it.id == wanted }
        if (found >= 0) {
            index = found
            playCurrent()
            return
        }
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                com.olympus.iptv.portal.RemotePad.resolveChannel(
                    application as OlympusIptvApp,
                    playlistId,
                    wanted
                )
            } ?: return@launch
            if (isDestroyed) return@launch
            val (savedId, channel) = resolved
            if (playlistId.isNullOrBlank()) playlistId = savedId
            current = channel
            queue = listOf(channel)
            index = 0
            playCurrent()
        }
    }

    private fun playPasted(url: String) {
        val base = current ?: return
        current = base.copy(id = "pasted", name = "Pasted stream", streamUrl = url, kind = ContentKind.LIVE)
        playCurrent()
    }

    private fun zapByName(query: String) {
        if (query.isBlank()) return
        val found = queue.indexOfFirst { it.name.contains(query, ignoreCase = true) }
        if (found < 0) {
            Toast.makeText(this, getString(R.string.remote_no_channel, query), Toast.LENGTH_SHORT).show()
            return
        }
        index = found
        playCurrent()
    }

    private fun rewindLiveFive(): Boolean {
        if (!isLiveNow()) return false
        if (nudgeTimeshift(-5 * 60_000L)) return true
        val channel = current ?: return false
        if (!channel.supportsCatchup()) return false
        val savedId = playlistId ?: return false
        lifecycleScope.launch {
            val playlist = savedPlaylist(savedId) ?: return@launch
            val now = System.currentTimeMillis()
            val epg = runCatching { epgRepo.nowNext(playlist, channel) }.getOrNull()?.now
            val start = (epg?.startMillis ?: (now - 5 * 60_000L)).coerceAtMost(now - 60_000L)
            val end = epg?.endMillis ?: (now + 30 * 60_000L)
            val program = com.olympus.iptv.data.EpgProgram(
                title = epg?.title ?: channel.name,
                startMillis = start,
                endMillis = end,
                description = epg?.description
            )
            val url = com.olympus.iptv.playlist.CatchupUrls.url(playlist, channel, program) ?: return@launch
            val exo = player ?: return@launch
            binding.connectingBadge.setText(R.string.playback_catchup_rewind)
            binding.connectingBadge.visibility = View.VISIBLE
            watchingCatchup = true
            exo.setMediaItem(mediaItem(channel.copy(streamUrl = url)))
            exo.prepare()
            exo.playWhenReady = true
        }
        return true
    }

    private fun nudgeTimeshift(deltaMs: Long): Boolean {
        val exo = player ?: return false
        if (!isLiveNow()) return false
        if (!exo.isCurrentMediaItemSeekable) return false
        val window = exo.currentTimeline.takeIf { !it.isEmpty }?.getWindow(exo.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        val start = window?.defaultPositionMs?.coerceAtLeast(0L) ?: 0L
        val end = if (exo.duration > 0L) exo.duration else exo.currentPosition
        val target = (exo.currentPosition + deltaMs).coerceIn(start, end)
        if (target == exo.currentPosition) return false
        exo.seekTo(target)
        exo.playWhenReady = true
        paintGoLive()
        hideHandler.removeCallbacks(seekTick)
        hideHandler.post(seekTick)
        return true
    }

    private fun toggleMultiView() {
        if (touchPrimary) return
        multiViewEnabled = !multiViewEnabled
        lifecycleScope.launch(Dispatchers.IO) {
            (application as OlympusIptvApp).playlistStore.saveMultiView(multiViewEnabled)
        }
        Toast.makeText(
            this,
            if (multiViewEnabled) R.string.multi_view_on else R.string.multi_view_off,
            Toast.LENGTH_SHORT
        ).show()
        syncMultiView(current)
    }

    private fun syncMultiView(channel: Channel?) {
        if (!::binding.isInitialized) return
        if (touchPrimary || !multiViewEnabled || !isLiveNow(channel)) {
            stopSecondary()
            return
        }
        val savedId = playlistId ?: run {
            stopSecondary()
            return
        }
        lifecycleScope.launch {
            val previousId = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.previousChannelId(savedId)
            }
            if (previousId.isNullOrBlank() || previousId == channel?.id) {
                stopSecondary()
                return@launch
            }
            val listed = queue.firstOrNull { it.id == previousId }
            val loaded = listed ?: withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).catalogDb.readChannel(savedId, CatalogDb.LIVE, previousId)
            }
            if (loaded == null || loaded.streamUrl.isBlank() || !isLiveNow(loaded)) {
                stopSecondary()
                return@launch
            }
            startSecondary(loaded)
        }
    }

    private fun startSecondary(channel: Channel) {
        clearSpare(1)
        clearSpare(-1)
        val primary = binding.playerView.layoutParams as android.widget.LinearLayout.LayoutParams
        val secondary = binding.playerViewB.layoutParams as android.widget.LinearLayout.LayoutParams
        primary.weight = 0.6f
        secondary.weight = 0.4f
        binding.playerView.layoutParams = primary
        binding.playerViewB.layoutParams = secondary
        binding.playerViewB.visibility = View.VISIBLE
        val exo = secondaryPlayer ?: obtainSpare().also {
            it.volume = 0f
            secondaryPlayer = it
        }
        binding.playerViewB.player = exo
        if (exo.currentMediaItem?.localConfiguration?.uri?.toString() != channel.streamUrl) {
            exo.setMediaItem(mediaItem(channel))
            exo.prepare()
        }
        exo.playWhenReady = true
        exo.volume = if (audioOnPrimary) 0f else 1f
        player?.volume = if (audioOnPrimary) 1f else 0f
        paintActivePane()
    }

    private fun stopSecondary() {
        if (!::binding.isInitialized) return
        binding.playerViewB.player = null
        binding.playerViewB.visibility = View.GONE
        val primary = binding.playerView.layoutParams as android.widget.LinearLayout.LayoutParams
        val secondary = binding.playerViewB.layoutParams as android.widget.LinearLayout.LayoutParams
        primary.weight = 1f
        secondary.weight = 0f
        binding.playerView.layoutParams = primary
        binding.playerViewB.layoutParams = secondary
        val exo = secondaryPlayer
        secondaryPlayer = null
        binding.playerView.foreground = null
        binding.playerViewB.foreground = null
        player?.volume = 1f
        audioOnPrimary = true
        if (exo != null) {
            exo.volume = 1f
            recycle(exo)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) noteRemoteInput()
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
        ) {
            handlePlayerBack()
            return true
        }
        if (playbackErrorOpen()) {
            if (centerKey(event.keyCode) ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                return super.dispatchKeyEvent(event)
            }
        }
        if (centerKey(event.keyCode)) {
            if (::miniGuide.isInitialized && miniGuide.isOpen()) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) miniGuide.tune()
                return true
            }
            if (binding.subtitlePanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
            if (menuButtonFocused() || binding.btnSeekPlay.isFocused) {
                swallowMenuClick = false
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount >= 8) {
                showPlayingActions()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (binding.catchupStart.visibility == View.VISIBLE && catchupUrl != null) {
                    playCatchupFromStart()
                    return true
                }
                if (binding.playbackMenu.visibility == View.VISIBLE) {
                    return super.dispatchKeyEvent(event)
                }
                if (!transportShowing()) {
                    showTransportChrome()
                } else {
                    togglePause()
                    showTransportChrome()
                }
                swallowMenuClick = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && swallowMenuClick) {
                swallowMenuClick = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openMiniGuide() {
        if (!::miniGuide.isInitialized) return
        hideTransportChrome(animated = false)
        miniGuide.show(current?.id)
        warmMiniGuide()
    }

    private fun warmMiniGuide() {
        val savedId = playlistId ?: return
        val slice = queue.take(12)
        lifecycleScope.launch {
            val playlist = savedPlaylist(savedId) ?: return@launch
            for (channel in slice) {
                if (!isActive) return@launch
                val epg = runCatching { epgRepo.nowNext(playlist, channel) }.getOrNull()?.now ?: continue
                miniTitles[channel.id] = epg.title
                val span = (epg.endMillis - epg.startMillis).coerceAtLeast(1L)
                val done = (System.currentTimeMillis() - epg.startMillis).coerceIn(0L, span)
                miniProgress[channel.id] = ((done * 1000L) / span).toInt()
            }
            if (::miniGuide.isInitialized && miniGuide.isOpen()) miniGuide.refresh()
        }
    }

    private fun noteRemoteInput() {
        lastInputAt = android.os.SystemClock.elapsedRealtime()
        idlePromptShown = false
    }

    private val idleWatch: Runnable = Runnable {
        if (isDestroyed) return@Runnable
        val now = android.os.SystemClock.elapsedRealtime()
        if (!idlePromptShown && now - lastInputAt >= 3L * 60L * 60L * 1000L && player?.isPlaying == true) {
            idlePromptShown = true
            showStillWatching()
        }
        hideHandler.postDelayed(idleWatch, 60_000L)
    }

    private fun showStillWatching() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.still_watching_title)
            .setMessage(R.string.still_watching_body)
            .setPositiveButton(R.string.still_watching_keep) { _, _ -> noteRemoteInput() }
            .setNegativeButton(R.string.still_watching_stop) { _, _ -> finish() }
            .create()
        com.olympus.iptv.ui.DialogChrome.dress(dialog)
        dialog.show()
    }

    private fun showPlayingActions() {
        val channel = current ?: return
        val labels = listOf(
            getString(R.string.player_channels),
            getString(R.string.record),
            getString(R.string.audio),
            getString(R.string.subtitles)
        )
        com.olympus.iptv.ui.GuideActionSheet.show(this, channel.name, labels) { which ->
            when (which) {
                0 -> openMiniGuide()
                1 -> binding.btnRecord.performClick()
                2 -> binding.btnAudio.performClick()
                3 -> binding.btnSubtitles.performClick()
            }
        }
    }

    private fun centerKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (playbackErrorOpen()) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> false
                else -> true
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                if (::miniGuide.isInitialized && miniGuide.isOpen()) {
                    miniGuide.move(-1)
                    return true
                }
                if (binding.playbackMenu.visibility == View.VISIBLE) return true
                if (transportShowing()) {
                    openMiniGuide()
                    return true
                }
                previousChannel(); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                if (::miniGuide.isInitialized && miniGuide.isOpen()) {
                    miniGuide.move(1)
                    return true
                }
                if (binding.playbackMenu.visibility == View.VISIBLE) return true
                if (transportShowing()) {
                    openMiniGuide()
                    return true
                }
                nextChannel(); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (switchMultiAudio(primary = true)) return true
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                if (binding.playbackMenu.visibility == View.VISIBLE) return false
                if (!transportShowing() && rewindLiveFive()) return true
                if (nudgeTimeshift(-10_000L)) return true
                requestChannelReturn()
                armBrowseBack()
                finish()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (binding.catchupStart.visibility == View.VISIBLE && catchupUrl != null) {
                    playCatchupFromStart()
                    return true
                }
                false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (switchMultiAudio(primary = false)) return true
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                if (binding.playbackMenu.visibility == View.VISIBLE) return false
                if (nudgeTimeshift(10_000L)) return true
                false
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                togglePause()
                showTransportChrome()
                true
            }
            KeyEvent.KEYCODE_INFO -> {
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                showPlaybackMenu(); true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                if (binding.playbackMenu.visibility == View.VISIBLE) hidePlaybackMenu()
                toggleLastChannel(); true
            }
            else -> {
                val digit = ChannelNumberJump.digit(keyCode)
                if (digit >= 0 && current?.kind == ContentKind.LIVE) {
                    onPlayerDigit(digit)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
        }
    }

    private var dialDigits = ""
    private val tuneDial = Runnable { tuneDialedNumber() }
    private val clearDialHud = Runnable {
        dialDigits = ""
        if (::binding.isInitialized) binding.playerDial.visibility = View.GONE
    }

    private fun onPlayerDigit(digit: Int) {
        if (dialDigits.length >= ChannelNumberJump.MAX_DIGITS) dialDigits = ""
        dialDigits = ChannelNumberJump.append(dialDigits, digit)
        binding.playerDial.text = dialDigits
        binding.playerDial.setShadowLayer(28f, 0f, 0f, getColor(R.color.accent))
        binding.playerDial.visibility = View.VISIBLE
        hideHandler.removeCallbacks(tuneDial)
        hideHandler.removeCallbacks(clearDialHud)
        hideHandler.postDelayed(tuneDial, ChannelNumberJump.DELAY_MS)
    }

    private fun tuneDialedNumber() {
        val typed = dialDigits
        dialDigits = ""
        val number = typed.toIntOrNull() ?: 0
        val at = ChannelNumberJump.indexFor(queue.map { it.number }, number, allowRowFallback = false)
        if (at !in queue.indices) {
            binding.playerDial.text = typed
            hideHandler.postDelayed(clearDialHud, 900L)
            return
        }
        binding.playerDial.visibility = View.GONE
        index = at
        playCurrent()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val playing = player?.playWhenReady == true || radioOn
        if (pipSwipeHome && canEnterPip() && playing && !DeviceForm.isTelevision(this)) {
            enterPip(manual = false)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        inPip = isInPictureInPictureMode
        pipPending = false
        if (isInPictureInPictureMode) {
            hidePlaybackMenu()
            binding.overlay.visibility = View.GONE
            binding.phoneDock.visibility = View.GONE
            player?.playWhenReady = true
        }
    }

    private fun openTrailer() {
        val channel = current ?: return
        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            Toast.makeText(this, R.string.tmdb_missing_key, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val preferTv = !channel.seriesId.isNullOrBlank() || channel.kind != ContentKind.VOD
            val trailer = withContext(Dispatchers.IO) {
                TitleRatings.trailerFor(channel.name, preferTv)
            }
            if (isDestroyed) return@launch
            if (trailer == null) {
                Toast.makeText(this@PlayerActivity, R.string.trailer_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val appIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("vnd.youtube:${trailer.key}")
            )
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=${trailer.key}")
            )
            runCatching { startActivity(appIntent) }.recoverCatching { startActivity(webIntent) }
                .onFailure {
                    Toast.makeText(this@PlayerActivity, R.string.trailer_missing, Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun enrichTmdb(channel: Channel, alias: String) {
        lifecycleScope.launch {
            val preferTv = !channel.seriesId.isNullOrBlank()
            val bundle = withContext(Dispatchers.IO) {
                runCatching {
                    TitleRatings.peek(channel.name, preferTv)
                        ?: TitleRatings.loadScores(channel.name, preferTv)
                }.getOrNull()
            } ?: return@launch
            if (isDestroyed || current?.id != channel.id) return@launch
            val line = bundle.scoreLine(2)
            if (line.isBlank()) return@launch
            val existing = binding.channelMeta.text?.toString().orEmpty()
            if (!existing.contains(bundle.ratings.firstOrNull()?.scoreLabel.orEmpty())) {
                binding.channelMeta.text =
                    if (existing.isBlank() || existing == alias) line else "$existing · $line"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        hookReconnect()
        if (streamsReleased && current != null && !isFinishing) {
            streamsReleased = false
            playCurrent()
        }
    }

    private fun hookReconnect() {
        if (networkWatcher != null) return
        val watcher = NetworkWatcher(this) { force -> runOnUiThread { reconnectNow(force) } }
        watcher.start()
        networkWatcher = watcher
    }

    private fun reconnectNow(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force) {
            acceptReconnectAt = 0L
            com.olympus.iptv.playlist.HttpClients.evictConnections()
        }
        if (now < acceptReconnectAt || (!force && now - lastReconnectAt < 1_500L)) return
        val exo = player ?: return
        if (!exo.playWhenReady || current == null || isDestroyed) return
        // Never interrupt a healthy playing stream on transient background network callbacks.
        if (!force && exo.isPlaying && exo.playbackState == Player.STATE_READY) return
        lastReconnectAt = now
        if (force) {
            exo.stop()
            exo.clearMediaItems()
        }
        MobileBitrateCap.apply(this, exo)
        playCurrent()
    }

    private fun unhookReconnect() {
        networkWatcher?.stop()
        networkWatcher = null
    }

    override fun onStop() {
        cancelNext()
        current?.let { saveWatch(it) }
        val keepPip = inPip || pipPending
        if (!keepPip) scrobbleStop()
        progressJob?.cancel()
        seekJob?.cancel()
        hideHandler.removeCallbacks(seekTick)
        super.onStop()
        if (!keepPip) {
            dropOpenStreams()
        }
    }

    private fun dropOpenStreams() {
        preloadJob?.cancel()
        discardSparePlayers()
        secondaryPlayer?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        player?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        StreamSessions.clear()
        streamsReleased = true
    }

    override fun onDestroy() {
        phoneControls?.release()
        phoneControls = null
        leveler?.let { runCatching { it.release() } }
        leveler = null
        unhookReconnect()
        scrobbleStop()
        resetChannelScopedWork()
        channelJob.cancel()
        progressJob?.cancel()
        recoverJob?.cancel()
        countdownJob?.cancel()
        nextLookup?.cancel()
        introJob?.cancel()
        subtitleJob?.cancel()
        seekJob?.cancel()
        hideHandler.removeCallbacks(hideOverlay)
        hideHandler.removeCallbacks(hideZapBanner)
        hideHandler.removeCallbacks(seekTick)
        hideHandler.removeCallbacks(sleepFire)
        preloadJob?.cancel()
        stopSecondary()
        secondaryPlayer?.release()
        secondaryPlayer = null
        nextPlayer?.release()
        prevPlayer?.release()
        idlePlayer?.release()
        player?.release()
        player = null
        StreamSessions.clear()
        super.onDestroy()
    }

    companion object {
        private const val INITIAL_SPOOL_LOG_MS = 1_500L
        private const val REBUFFER_LOG_MS = 400L
        private const val INITIAL_BADGE_MS = 450L
        private const val REBUFFER_BADGE_MS = 1_200L
        private const val LIVE_STALL_MS = 4_000L
        private const val LIVE_REBUFFER_BADGE_MS = 4_500L
        private const val STILL_SPOOL_MS = 2_500L
        private const val MAX_LIVE_RECOVER = 4
        private const val TRANSPORT_HIDE_MS = 3_000L
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_META = "meta"
        private const val EXTRA_NEXT = "next"
        const val EXTRA_START_AT = "start_at"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_ZAP = "zap"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_ENTER_PIP = "enter_pip"

        private var returnToChannels = false
        private var browseBackUntil = 0L

        fun armBrowseBack() {
            browseBackUntil = android.os.SystemClock.uptimeMillis() + 800L
        }

        fun consumeBrowseBack(): Boolean {
            val armed = android.os.SystemClock.uptimeMillis() <= browseBackUntil
            if (armed) browseBackUntil = 0L
            return armed
        }

        fun requestChannelReturn() {
            returnToChannels = true
        }

        fun takeChannelReturn(): Boolean {
            val value = returnToChannels
            returnToChannels = false
            return value
        }

        const val ZAP_LIST = "list"
        const val ZAP_GROUP = "group"
        const val ZAP_ALL = "all"

        fun intent(
            context: Context,
            channel: Channel,
            channelMeta: String,
            channelNext: String,
            startIndex: Int,
            playlistId: String?,
            zapMode: String = ZAP_LIST,
            categoryId: String? = null,
            enterPip: Boolean = false
        ): Intent {
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, channel.id)
                .putExtra(EXTRA_NAME, channel.name)
                .putExtra(EXTRA_URL, channel.streamUrl)
                .putExtra(EXTRA_LOGO, channel.logoUrl)
                .putExtra(EXTRA_META, channelMeta)
                .putExtra(EXTRA_NEXT, channelNext)
                .putExtra(EXTRA_INDEX, startIndex)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                .putExtra(EXTRA_ZAP, zapMode)
                .putExtra(EXTRA_CATEGORY, categoryId)
                .putExtra(EXTRA_ENTER_PIP, enterPip)
        }
    }
}
