package com.olympus.iptv.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
import java.util.Locale
import com.olympus.iptv.record.RecordPrompt
import com.olympus.iptv.playlist.EpgUtils
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

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private var queue: List<Channel> = emptyList()
    private var index: Int = 0
    private var current: Channel? = null
    private var playlistId: String? = null
    private var zapMode: String = ZAP_LIST
    private var categoryId: String? = null
    private var stepping = false
    private var playJob: Job? = null
    private var progressJob: Job? = null
    private var nextPlayer: ExoPlayer? = null
    private var prevPlayer: ExoPlayer? = null
    private var idlePlayer: ExoPlayer? = null
    private var nextPreparedId: String? = null
    private var prevPreparedId: String? = null
    private var preloadJob: Job? = null
    private var recoverJob: Job? = null
    private var countdownJob: Job? = null
    private var nextLookup: Job? = null
    private var introJob: Job? = null
    private var subtitleJob: Job? = null
    /** Cancels in-flight EPG / catch-up / subtitle work when the user zaps away. */
    private var channelJob = SupervisorJob()
    private val zapEpgPrefetch = object : LinkedHashMap<String, EpgNowNext>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpgNowNext>?): Boolean = size > 8
    }
    private var sidecar: MediaItem.SubtitleConfiguration? = null
    private var pendingLanguage: String? = null
    private var preferredAudio: String = PlaylistStore.DEFAULT_AUDIO_LANGUAGE
    private var audioAppliedFor: String? = null
    private var preferredSubtitle: String = PlaylistStore.SUBTITLE_OFF
    private var subtitleAppliedFor: String? = null
    private var nextIsEpisode = false
    private var panelEmbedded = emptyList<StreamChoice>()
    private var pendingNext: Channel? = null
    private var resolvedSeries: List<Channel>? = null
    private var countdownFor: String? = null
    private var nextHanded = false
    private var advancing = false
    private var playbackReady = false
    private var playbackToken = 0
    private var recoverAttempt = 0
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
    private var pictureMode = PlaylistStore.PICTURE_FIT
    private var multiViewEnabled = false
    private var secondaryPlayer: ExoPlayer? = null
    private var seekDragging = false
    private var seekJob: Job? = null
    private var touchPrimary = false
    private var menuBaseBottom = 0
    private var overlayBaseTop = 0
    private var sleepMinutes = 0
    private var skipIntroSeries = emptySet<String>()
    private var videoDecoder = PlayerDecoder.HARDWARE
    private var playersDecoder = PlayerDecoder.HARDWARE
    private var decoderDirty = false
    private var pipSwipeHome = true
    private var scrobble = PlaylistStore.ScrobbleConfig(
        provider = Scrobbler.NONE,
        simklToken = "",
        simklClientId = "",
        traktToken = "",
        traktClientId = ""
    )
    private var scrobbleChannel: Channel? = null
    private var scrobbleJob: Job? = null
    private var scrobbleStarted = false
    private var inPip = false
    private var pipPending = false
    private var enterPipOnReady = false
    private val sleepFire = Runnable {
        if (!isDestroyed) {
            player?.playWhenReady = false
            finish()
        }
    }
    private val epgRepo: EpgRepository
        get() = (application as OlympusIptvApp).epgRepository

    private val hideHandler = Handler(Looper.getMainLooper())
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
            val mode = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.pictureMode()
            }
            if (isDestroyed) return@launch
            pictureMode = mode
            applyPicture(mode)
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
            seedNext = intent.getStringExtra(EXTRA_NEXT)
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
    }

    private fun setPhoneSystemBarsHidden(hide: Boolean) {
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

    private fun togglePlaybackChrome() {
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

    private fun showTransportChrome() {
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
    }

    private fun channelScope() = lifecycleScope + channelJob

    private fun playCurrent(seedMeta: String? = null, seedNext: String? = null, startAt: Long? = null) {
        val seed = current ?: queue.getOrNull(index)
        if (seed == null) {
            finish()
            return
        }
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
            val app = application as OlympusIptvApp
            app.lastPlayed = channel
            app.lastPlayedPlaylistId = playlistId
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
            binding.channelMeta.text = aliasCaption(seedMeta, loaded.name, alias).ifBlank { "Loading EPG…" }
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
                warmed.addListener(trackListener)
                watchPlaybackEvents(warmed)
                binding.playerView.player = warmed
                if (playAt > 0L) warmed.seekTo(playAt)
                warmed.playWhenReady = true
                // Warm/spare is often already READY — no state callback fires, so clear Connecting here.
                syncConnectingFromPlayer(warmed)
                binding.playerView.post { syncConnectingFromPlayer(warmed) }
                if (previous != null && previous !== warmed) recycle(previous)
            } else {
                val exo = player ?: return@launch
                watchPlaybackEvents(exo)
                exo.setMediaItem(mediaItem(channel))
                if (playAt > 0L) exo.seekTo(playAt)
                exo.prepare()
                exo.playWhenReady = true
                binding.playerView.post { syncConnectingFromPlayer(exo) }
                binding.playerView.postDelayed({
                    val active = player
                    if (active === exo) syncConnectingFromPlayer(exo)
                }, 800L)
            }
            configureSeekPane(channel)
            syncMultiView(channel)
            watchPlayback(channel)
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

    private fun mediaItem(channel: Channel, title: String = lineupName(channel, channel)): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(channel.streamUrl)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
        sidecar?.let { builder.setSubtitleConfigurations(listOf(it)) }
        if (channel.kind == ContentKind.LIVE) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(8_000)
                    .setMinOffsetMs(2_000)
                    .setMaxOffsetMs(24_000)
                    .build()
            )
        }
        return builder.build()
    }

    private fun newPlayer(): ExoPlayer {
        val mode = PlayerDecoder.normalize(videoDecoder)
        playersDecoder = mode
        decoderDirty = false
        val control = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_500, 15_000, 1_000, 2_000)
            .setTargetBufferBytes(6 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Olympus-IPTV")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        return ExoPlayer.Builder(this)
            .setRenderersFactory(PlayerDecoder.renderersFactory(this, mode))
            .setLoadControl(control)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
            .also { exo ->
                exo.addListener(trackListener)
                watchPlaybackEvents(exo)
            }
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
                        PlaybackTrace.error(channel, error, recovering = true)
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
                    refreshStreamTracks()
                    if (binding.channelMeta.text == getString(R.string.playback_reconnecting)) {
                        refreshEpgQuietly()
                    }
                }
            }
        }
        val analytics = object : AnalyticsListener {
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
        // After first paint, live rebuffers constantly — do not cover a working picture.
        // Initial connect also waits briefly so READY/first-frame can clear without a flash.
        val delayShow = when {
            hadPlaybackReady && live -> LIVE_REBUFFER_BADGE_MS
            hadPlaybackReady -> REBUFFER_BADGE_MS
            else -> INITIAL_BADGE_MS
        }
        hideHandler.postDelayed({
            if (isDestroyed || token != bufferWatchToken) return@postDelayed
            val active = player ?: return@postDelayed
            if (active.isPlaying || active.playbackState == Player.STATE_READY) {
                onBufferingEnded()
                return@postDelayed
            }
            if (active.playbackState != Player.STATE_BUFFERING) return@postDelayed
            showConnectingBadge(true)
        }, delayShow)
        hideHandler.postDelayed({
            if (isDestroyed || token != bufferWatchToken) return@postDelayed
            val active = player ?: return@postDelayed
            if (active.playbackState != Player.STATE_BUFFERING || active.isPlaying) return@postDelayed
            val waited = SystemClock.elapsedRealtime() - started
            if (waited >= STILL_SPOOL_MS) {
                PlaybackTrace.stillBuffering(current, waited)
                paintConnectingBadge(waited)
            }
        }, STILL_SPOOL_MS)
        hideHandler.postDelayed({
            if (isDestroyed || token != bufferWatchToken) return@postDelayed
            val active = player ?: return@postDelayed
            if (active.playbackState != Player.STATE_BUFFERING || active.isPlaying) return@postDelayed
            paintConnectingBadge(SystemClock.elapsedRealtime() - started)
        }, 2_000L)
    }

    private fun onBufferingEnded() {
        val started = bufferStartedAt
        bufferStartedAt = 0L
        bufferWatchToken += 1
        showConnectingBadge(false)
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
        if (show) {
            paintConnectingBadge(
                if (bufferStartedAt > 0L) SystemClock.elapsedRealtime() - bufferStartedAt else 0L
            )
            binding.connectingBadge.visibility = View.VISIBLE
        } else {
            binding.connectingBadge.visibility = View.GONE
            binding.connectingBadge.setText(R.string.playback_connecting)
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
        }
    }

    private fun recoverLive(exo: ExoPlayer, channel: Channel, error: PlaybackException?) {
        val token = playbackToken
        recoverJob?.cancel()
        val behind = error?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        val http = (error?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: (error?.cause?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        recoverAttempt += 1
        PlaybackTrace.recover(channel, recoverAttempt, behind)
        // 403/401 are provider denials — retrying forever freezes the session and spams the log.
        val hardFail = http == 401 || http == 403 || recoverAttempt >= MAX_LIVE_RECOVER
        if (hardFail) {
            runCatching { exo.stop() }
            toastPlaybackIssue(error)
            return
        }
        if (playbackErrorOpen()) return
        binding.channelMeta.text = getString(R.string.playback_reconnecting)
        recoverJob = lifecycleScope.launch {
            delay((600L + (recoverAttempt - 1) * 400L).coerceAtMost(3_000L))
            if (token != playbackToken || player !== exo || isDestroyed) return@launch
            hadPlaybackReady = false
            bufferStartedAt = 0L
            bufferWatchToken += 1
            exo.playWhenReady = true
            if (behind) {
                exo.seekToDefaultPosition()
                exo.prepare()
            } else {
                exo.setMediaItem(mediaItem(channel))
                exo.prepare()
            }
            exo.playWhenReady = true
        }
    }

    private fun toastPlaybackIssue(error: PlaybackException?) {
        val code = error?.errorCode ?: -1
        val provider = when {
            error == null -> true
            code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
            code in 2_000..3_999 -> true
            code in 6_000..6_999 -> true
            code in 4_000..5_999 -> false
            else -> true
        }
        val message = if (provider) {
            getString(R.string.playback_issue_provider)
        } else {
            getString(R.string.playback_issue_app)
        }
        showPlaybackError(message)
    }

    private fun showPlaybackError(message: String) {
        if (!::binding.isInitialized) return
        showConnectingBadge(false)
        hidePlaybackMenu()
        binding.playbackErrorBody.text = message
        binding.playbackErrorCard.visibility = View.VISIBLE
        listOf(
            binding.playbackErrorClose,
            binding.playbackErrorNext,
            binding.playbackErrorRetry
        ).forEach { button ->
            button.isFocusable = true
            button.isFocusableInTouchMode = true
            button.isClickable = true
        }
        binding.playbackErrorRetry.setOnClickListener {
            if (touchPrimary) Haptics.tick(binding.playbackErrorRetry)
            hidePlaybackError()
            playCurrent()
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
            return ready
        }
        if (prevPreparedId == id) {
            val ready = prevPlayer
            prevPlayer = null
            prevPreparedId = null
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
        if (touchPrimary) {
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
        spare.playWhenReady = false
        spare.setMediaItem(mediaItem(channel))
        spare.prepare()
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
        if (direction > 0) {
            nextPlayer = null
            nextPreparedId = null
        } else {
            prevPlayer = null
            prevPreparedId = null
        }
        if (spare != null) recycle(spare)
    }

    private fun preloadAround(playingId: String) {
        val now = current ?: return
        if (now.kind != ContentKind.LIVE) return
        if (touchPrimary) {
            clearSpare(1)
            clearSpare(-1)
            return
        }
        preloadJob?.cancel()
        preloadJob = lifecycleScope.launch {
            val nextChannel = withContext(Dispatchers.IO) { adjacent(now, 1) }
            val prevChannel = withContext(Dispatchers.IO) { adjacent(now, -1) }
            if (!isActive || current?.id != playingId) return@launch
            if (nextChannel != null) {
                warmEpg(nextChannel)
                prepareSpare(1, nextChannel)
            } else {
                clearSpare(1)
            }
            if (prevChannel != null) {
                prepareSpare(-1, prevChannel)
            } else {
                clearSpare(-1)
            }
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

    private suspend fun savedPlaylist(savedId: String): SavedPlaylist? {
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

    private suspend fun resumePosition(channel: Channel): Long {
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

    private fun saveWatch(channel: Channel) {
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
            runCatching { app.playlistStore.rememberWatch(savedId, watchEntry(channel, position, duration)) }
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
            flashOverlay()
        }
    }

    private fun offerNext(channel: Channel) {
        if (countdownFor == channel.id) return
        saveWatch(channel)
        countdownFor = channel.id
        nextHanded = false
        introJob?.cancel()
        nextIsEpisode = !channel.seriesId.isNullOrBlank()
        val seriesQueued = queue.getOrNull(index + 1)?.takeIf {
            it.id != channel.id &&
                !channel.seriesId.isNullOrBlank() &&
                it.seriesId == channel.seriesId &&
                it.streamUrl.isNotBlank()
        }
        val listQueued = queue.getOrNull(index + 1)?.takeIf {
            it.id != channel.id && it.streamUrl.isNotBlank()
        }
        pendingNext = seriesQueued ?: listQueued?.takeIf { channel.seriesId.isNullOrBlank() }
        resolvedSeries = null
        showCountdown(pendingNext)
        if (pendingNext == null && !channel.seriesId.isNullOrBlank()) {
            nextLookup = lifecycleScope.launch {
                val savedId = playlistId
                val seriesId = channel.seriesId
                val saved = if (savedId.isNullOrBlank() || seriesId.isNullOrBlank()) null else savedPlaylist(savedId)
                val episodes = if (saved == null || seriesId.isNullOrBlank()) emptyList()
                else EpisodeSequence.load(filesDir, saved, seriesId)
                if (!isActive || countdownFor != channel.id || nextHanded) return@launch
                val ordered = EpisodeSequence.ordered(episodes)
                resolvedSeries = ordered
                val next = EpisodeSequence.nextAfter(ordered, channel)
                pendingNext = next
                nextIsEpisode = next != null
                if (next == null) {
                    val after = queue.getOrNull(index + 1)?.takeIf {
                        it.id != channel.id && it.streamUrl.isNotBlank()
                    }
                    pendingNext = after
                    nextIsEpisode = false
                }
                if (pendingNext == null) {
                    countdownJob?.cancel()
                    hideNextPanel()
                    return@launch
                }
                styleNextPanel(pendingNext)
            }
        } else if (pendingNext == null) {
            hideNextPanel()
            return
        }
        countdownJob = lifecycleScope.launch {
            for (second in 10 downTo 1) {
                if (!isActive || nextHanded) return@launch
                binding.nextCount.text = getString(
                    if (nextIsEpisode) R.string.next_episode_in else R.string.next_title_in,
                    second
                )
                delay(1_000)
            }
            nextLookup?.join()
            val next = pendingNext
            if (next == null || nextHanded) {
                if (!nextHanded) hideNextPanel()
                return@launch
            }
            openNext(next, skipIntro = shouldAutoSkip(next))
        }
    }

    private fun showCountdown(next: Channel?) {
        binding.nextPanel.visibility = View.VISIBLE
        binding.nextCount.visibility = View.VISIBLE
        styleNextPanel(next)
        val focus = if (binding.btnSkipIntro.visibility == View.VISIBLE) {
            binding.btnSkipIntro
        } else {
            binding.btnPlayNext
        }
        focus.post { focus.requestFocus() }
    }

    private fun styleNextPanel(next: Channel?) {
        if (next == null) {
            binding.nextName.visibility = View.GONE
        } else {
            binding.nextName.visibility = View.VISIBLE
            binding.nextName.text = if (nextIsEpisode) {
                EpisodeSequence.heading("", next)
            } else {
                next.name
            }
        }
        binding.btnSkipIntro.visibility = if (nextIsEpisode && next != null) View.VISIBLE else View.GONE
        binding.btnPlayNext.visibility = if (!nextIsEpisode && next != null) View.VISIBLE else View.GONE
        binding.btnNextMenu.visibility = View.VISIBLE
        binding.btnSkipIntro.nextFocusDownId = binding.btnNextMenu.id
        binding.btnPlayNext.nextFocusDownId = binding.btnNextMenu.id
        binding.btnNextMenu.nextFocusUpId =
            if (binding.btnSkipIntro.visibility == View.VISIBLE) binding.btnSkipIntro.id else binding.btnPlayNext.id
    }

    private fun playPendingNext() {
        lifecycleScope.launch {
            nextLookup?.join()
            val next = pendingNext
            if (next == null) {
                hideNextPanel()
                return@launch
            }
            openNext(next, skipIntro = false)
        }
    }

    private fun escapeToMenu() {
        cancelNext()
        armBrowseBack()
        finish()
    }

    private fun showSkipButton() {
        binding.nextPanel.visibility = View.VISIBLE
        binding.nextCount.visibility = View.GONE
        binding.nextName.visibility = View.GONE
        binding.btnSkipIntro.visibility = View.VISIBLE
        binding.btnPlayNext.visibility = View.GONE
        binding.btnNextMenu.visibility = View.GONE
        binding.btnSkipIntro.post { binding.btnSkipIntro.requestFocus() }
        introJob?.cancel()
        introJob = lifecycleScope.launch {
            while (isActive) {
                val exo = player
                if (exo == null) break
                val duration = exo.duration
                if (duration > 0L && duration < EpisodeSequence.INTRO_MS + 30_000L) break
                if (exo.currentPosition >= EpisodeSequence.INTRO_MS) break
                delay(400)
            }
            hideNextPanel()
        }
    }

    private fun onSkipIntro() {
        lifecycleScope.launch {
            rememberSkipIntroFor(current?.seriesId ?: pendingNext?.seriesId)
            if (nextHanded) {
                seekIntro()
                return@launch
            }
            nextLookup?.join()
            val next = pendingNext
            if (next == null) {
                hideNextPanel()
                return@launch
            }
            openNext(next, skipIntro = true)
        }
    }

    private fun rememberSkipIntroFor(seriesId: String?) {
        val id = seriesId?.trim().orEmpty()
        if (id.isEmpty() || id in skipIntroSeries) return
        skipIntroSeries = skipIntroSeries + id
        (application as OlympusIptvApp).appScope.launch {
            runCatching { (application as OlympusIptvApp).playlistStore.rememberSkipIntro(id) }
        }
    }

    private fun shouldAutoSkip(channel: Channel?): Boolean =
        GuideFeel.autoSkipIntro(channel?.seriesId, skipIntroSeries)

    private fun openNext(next: Channel, skipIntro: Boolean) {
        if (nextHanded) return
        nextHanded = true
        countdownJob?.cancel()
        val full = resolvedSeries
        if (full != null && full.any { it.id == next.id }) {
            queue = full
            (application as OlympusIptvApp).playbackQueue = full
        }
        val at = queue.indexOfFirst { it.id == next.id }
        if (at >= 0) {
            index = at
        } else {
            queue = listOf(next)
            index = 0
        }
        current = next
        stepping = true
        val auto = skipIntro || shouldAutoSkip(next)
        lifecycleScope.launch {
            val resume = resumePosition(next)
            val start = if (auto && resume < EpisodeSequence.INTRO_MS) EpisodeSequence.INTRO_MS else resume
            playCurrent(startAt = start)
            if (auto || start >= EpisodeSequence.INTRO_MS) hideNextPanel() else showSkipButton()
        }
    }

    private fun seekIntro() {
        val exo = player ?: return
        val duration = exo.duration
        if (duration > 0L && duration < EpisodeSequence.INTRO_MS + 30_000L) {
            hideNextPanel()
            return
        }
        exo.seekTo(EpisodeSequence.INTRO_MS)
        hideNextPanel()
    }

    private fun hideNextPanel() {
        binding.nextPanel.visibility = View.GONE
    }

    private fun cancelNext() {
        countdownJob?.cancel()
        nextLookup?.cancel()
        introJob?.cancel()
        pendingNext = null
        nextHanded = true
        hideNextPanel()
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

    private fun step(direction: Int) {
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

    private fun menuButtonFocused(): Boolean =
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

    private fun hidePlaybackMenu() {
        if (!::binding.isInitialized) return
        binding.playbackMenu.visibility = View.GONE
        if (touchPrimary && binding.overlay.visibility != View.VISIBLE) setPhoneSystemBarsHidden(true)
    }

    private fun openAudio() {
        val exo = player ?: return
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.audio)
        binding.subtitlePanel.visibility = View.VISIBLE
        binding.subtitleRows.removeAllViews()
        val choices = streamChoices(exo, C.TRACK_TYPE_AUDIO)
        binding.subtitleStatus.text = if (choices.isEmpty()) getString(R.string.audio_none) else ""
        choices.forEach { choice ->
            addSubtitleRow(choice.label) {
                selectTrack(choice, C.TRACK_TYPE_AUDIO)
                rememberAudio(choice)
                closeSubtitlePanel()
            }
        }
        binding.subtitleRows.getChildAt(0)?.requestFocus()
    }

    private fun openSleepTimer() {
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.sleep_timer)
        binding.subtitlePanel.visibility = View.VISIBLE
        binding.subtitleRows.removeAllViews()
        binding.subtitleStatus.text = if (sleepMinutes > 0) {
            getString(R.string.sleep_armed)
        } else {
            ""
        }
        val choices = listOf(
            0 to R.string.sleep_off,
            30 to R.string.sleep_30,
            60 to R.string.sleep_60,
            90 to R.string.sleep_90
        )
        var focusIndex = 0
        choices.forEachIndexed { index, (minutes, label) ->
            val text = if (minutes == sleepMinutes) {
                getString(R.string.track_on, getString(label))
            } else {
                getString(label)
            }
            if (minutes == sleepMinutes) focusIndex = index
            addSubtitleRow(text) {
                armSleepTimer(minutes)
                closeSubtitlePanel()
            }
        }
        if (touchPrimary) {
            addSubtitleRow(
                getString(
                    if (rememberedPhoneOrientation() == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        R.string.player_orientation_lock
                    } else {
                        R.string.player_orientation_free
                    }
                )
            ) {
                togglePhoneOrientationLock()
                closeSubtitlePanel()
            }
        }
        binding.subtitleRows.getChildAt(focusIndex)?.requestFocus()
    }

    private fun armSleepTimer(minutes: Int) {
        hideHandler.removeCallbacks(sleepFire)
        sleepMinutes = minutes
        if (minutes <= 0) {
            paintSleepLabel()
            return
        }
        hideHandler.postDelayed(sleepFire, minutes * 60_000L)
        paintSleepLabel()
        Toast.makeText(this, R.string.sleep_armed, Toast.LENGTH_SHORT).show()
    }

    private fun paintSleepLabel() {
        if (!::binding.isInitialized) return
        binding.btnSleep.text = if (sleepMinutes > 0) {
            getString(R.string.sleep_timer) + " · " + sleepMinutes
        } else {
            getString(R.string.sleep_timer)
        }
    }

    private fun playerPrefs() = getSharedPreferences("olympus_player", MODE_PRIVATE)

    private fun rememberedPhoneOrientation(): Int {
        val locked = playerPrefs().getInt("phone_orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        return if (locked == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
            locked == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ) {
            locked
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun togglePhoneOrientationLock() {
        val prefs = playerPrefs()
        val current = rememberedPhoneOrientation()
        val next = when {
            current != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        prefs.edit().putInt("phone_orientation", next).apply()
        requestedOrientation = next
    }

    private fun openPicture() {
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.picture)
        binding.subtitlePanel.visibility = View.VISIBLE
        binding.subtitleRows.removeAllViews()
        binding.subtitleStatus.text = ""
        val choices = listOf(
            PlaylistStore.PICTURE_FIT to R.string.picture_fit,
            PlaylistStore.PICTURE_FILL to R.string.picture_fill,
            PlaylistStore.PICTURE_STRETCH to R.string.picture_stretch
        )
        var focusIndex = 0
        choices.forEachIndexed { index, (mode, label) ->
            val text = if (mode == pictureMode) {
                getString(R.string.track_on, getString(label))
            } else {
                getString(label)
            }
            if (mode == pictureMode) focusIndex = index
            addSubtitleRow(text) {
                pictureMode = mode
                applyPicture(mode)
                lifecycleScope.launch(Dispatchers.IO) {
                    (application as OlympusIptvApp).playlistStore.savePictureMode(mode)
                }
                closeSubtitlePanel()
            }
        }
        binding.subtitleRows.getChildAt(focusIndex)?.requestFocus()
    }

    private fun applyPicture(mode: String) {
        if (!::binding.isInitialized) return
        val resize = when (mode) {
            PlaylistStore.PICTURE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PlaylistStore.PICTURE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = resize
    }

    private fun openSubtitles() {
        val channel = current ?: return
        val exo = player
        val series = !channel.seriesId.isNullOrBlank() && channel.streamUrl.isNotBlank()
        panelEmbedded = if (exo == null) emptyList() else streamChoices(exo, C.TRACK_TYPE_TEXT)
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.subtitles)
        binding.subtitlePanel.visibility = View.VISIBLE
        if (!series) {
            subtitleJob?.cancel()
            showSubtitleHits(emptyList(), waiting = false)
            if (panelEmbedded.isEmpty() && sidecar == null) {
                binding.subtitleStatus.text = getString(R.string.subtitles_none)
            }
            return
        }
        binding.subtitleStatus.text = getString(R.string.subtitles_searching)
        showSubtitleHits(emptyList(), waiting = true)
        subtitleJob?.cancel()
        subtitleJob = channelScope().launch {
            val savedId = playlistId
            val seriesId = channel.seriesId
            val show = withContext(Dispatchers.IO) {
                if (savedId.isNullOrBlank() || seriesId.isNullOrBlank()) channel.name
                else {
                    (application as OlympusIptvApp).catalogDb
                        .readChannel(savedId, CatalogDb.SERIES, seriesId)?.name ?: channel.name
                }
            }
            val query = Subtitles.query(show, channel)
            if (query == null || !isActive) {
                binding.subtitleStatus.text = if (panelEmbedded.isEmpty()) getString(R.string.subtitles_none) else ""
                return@launch
            }
            val opened = async(Dispatchers.IO) { Subtitles.openSearch(query) }
            val listed = async(Dispatchers.IO) { Subtitles.tvSearch(filesDir, query) }
            val first = opened.await()
            if (!isActive) return@launch
            showSubtitleHits(first, waiting = true)
            val rest = listed.await()
            if (!isActive) return@launch
            showSubtitleHits(first + rest, waiting = false)
        }
    }

    private fun showSubtitleHits(hits: List<Subtitles.Hit>, waiting: Boolean) {
        binding.subtitleRows.removeAllViews()
        panelEmbedded.forEach { choice ->
            addSubtitleRow(choice.label) { chooseEmbeddedText(choice) }
        }
        if (sidecar != null || panelEmbedded.any { it.selected }) {
            addSubtitleRow(getString(R.string.subtitles_off)) { disableText() }
        }
        hits.forEach { hit -> addSubtitleRow(hit.label) { chooseSubtitle(hit) } }
        binding.subtitleStatus.text = when {
            hits.isEmpty() && waiting -> getString(R.string.subtitles_searching)
            hits.isEmpty() && panelEmbedded.isEmpty() && sidecar == null -> getString(R.string.subtitles_none)
            else -> ""
        }
        if (binding.subtitleRows.childCount > 0) {
            binding.subtitleRows.getChildAt(0)?.requestFocus()
        }
    }

    private fun chooseEmbeddedText(choice: StreamChoice) {
        pendingLanguage = choice.language
        rememberSubtitle(choice.language)
        if (sidecar != null) {
            clearSidecar()
            return
        }
        selectTrack(choice, C.TRACK_TYPE_TEXT)
        closeSubtitlePanel()
    }

    private fun disableText() {
        pendingLanguage = null
        rememberSubtitle(PlaylistStore.SUBTITLE_OFF)
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        if (sidecar != null) clearSidecar() else closeSubtitlePanel()
    }

    private fun selectTrack(choice: StreamChoice, type: Int) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.index)))
            .build()
    }

    private fun refreshStreamTracks() {
        val exo = player ?: return
        val channel = current
        val audio = streamChoices(exo, C.TRACK_TYPE_AUDIO)
        val text = streamChoices(exo, C.TRACK_TYPE_TEXT)
        val series = channel != null && !channel.seriesId.isNullOrBlank() && channel.streamUrl.isNotBlank()
        binding.btnAudio.visibility = if (audio.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnSubtitles.visibility = if (series || text.isNotEmpty()) View.VISIBLE else View.GONE
        if (binding.playbackMenu.visibility == View.VISIBLE && !menuButtonFocused()) {
            listOf(
                binding.btnAudio,
                binding.btnSubtitles,
                binding.btnRecord,
                binding.btnPicture,
                binding.btnLast,
                binding.btnGoLive,
                binding.btnMultiView
            )
                .firstOrNull { it.visibility == View.VISIBLE }
                ?.requestFocus()
        }
        applyPreferredAudio(channel, audio)
        applyPreferredSubtitle(channel, text)
        val wanted = pendingLanguage ?: return
        val match = text.firstOrNull { it.language == wanted } ?: return
        pendingLanguage = null
        selectTrack(match, C.TRACK_TYPE_TEXT)
    }

    private fun applyPreferredAudio(channel: Channel?, audio: List<StreamChoice>) {
        if (audio.isEmpty() || channel == null || channel.id.isBlank()) return
        if (audioAppliedFor == channel.id) return
        audioAppliedFor = channel.id
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val channelLang = withContext(Dispatchers.IO) { store.audioForChannel(channel.id) }
            val global = preferredAudio.ifBlank {
                withContext(Dispatchers.IO) { store.audioLanguage() }.also { preferredAudio = it }
            }
            val match = pickAudio(audio, channelLang ?: global) ?: return@launch
            if (!match.selected) selectTrack(match, C.TRACK_TYPE_AUDIO)
        }
    }

    private fun applyPreferredSubtitle(channel: Channel?, text: List<StreamChoice>) {
        if (text.isEmpty() || channel == null || channel.id.isBlank()) return
        if (subtitleAppliedFor == channel.id) return
        subtitleAppliedFor = channel.id
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val channelLang = withContext(Dispatchers.IO) { store.subtitleForChannel(channel.id) }
            val global = preferredSubtitle.ifBlank {
                withContext(Dispatchers.IO) { store.subtitleLanguage() }.also { preferredSubtitle = it }
            }
            val wanted = channelLang ?: global
            if (wanted.isBlank() || wanted == PlaylistStore.SUBTITLE_OFF) {
                val exo = player ?: return@launch
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                return@launch
            }
            val match = pickAudio(text, wanted) ?: return@launch
            if (!match.selected) selectTrack(match, C.TRACK_TYPE_TEXT)
        }
    }

    private fun rememberAudio(choice: StreamChoice) {
        val language = choice.language?.trim().orEmpty()
        if (language.isBlank()) return
        preferredAudio = language
        audioAppliedFor = current?.id
        val channelId = current?.id.orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val store = (application as OlympusIptvApp).playlistStore
            if (channelId.isNotBlank()) store.saveAudioForChannel(channelId, language)
            else store.saveAudioLanguage(language)
        }
    }

    private fun rememberSubtitle(language: String?) {
        val chosen = language?.trim().orEmpty().ifBlank { PlaylistStore.SUBTITLE_OFF }
        preferredSubtitle = chosen
        subtitleAppliedFor = current?.id
        val channelId = current?.id.orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val store = (application as OlympusIptvApp).playlistStore
            if (channelId.isNotBlank()) store.saveSubtitleForChannel(channelId, chosen)
            else store.saveSubtitleLanguage(chosen)
        }
    }

    private fun pickAudio(choices: List<StreamChoice>, wanted: String): StreamChoice? {
        val prefs = linkedSetOf(wanted, PlaylistStore.DEFAULT_AUDIO_LANGUAGE, "en", "eng")
        for (pref in prefs) {
            choices.firstOrNull { languageMatches(it.language, pref) }?.let { return it }
        }
        return null
    }

    private fun languageMatches(track: String?, want: String): Boolean {
        if (track.isNullOrBlank() || want.isBlank()) return false
        val a = track.replace('_', '-').lowercase(Locale.US)
        val b = want.replace('_', '-').lowercase(Locale.US)
        if (a == b) return true
        if (a.startsWith("$b-") || b.startsWith("$a-")) return true
        return a.substringBefore('-') == b.substringBefore('-')
    }

    private fun streamChoices(exo: Player, type: Int): List<StreamChoice> {
        val out = ArrayList<StreamChoice>()
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val language = format.language?.takeIf { it.isNotBlank() && !it.equals("und", true) }
                val spoken = language?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() }
                val extra = format.label?.takeIf { label ->
                    label.isNotBlank() && !label.equals(spoken, true) && !label.equals(language, true)
                }
                val base = listOfNotNull(spoken, extra).joinToString(" · ").ifBlank {
                    getString(R.string.track_n, out.size + 1)
                }
                val label = if (group.isTrackSelected(index)) getString(R.string.track_on, base) else base
                out += StreamChoice(group, index, label, language ?: format.label, group.isTrackSelected(index))
            }
        }
        return out
    }

    private data class StreamChoice(
        val group: Tracks.Group,
        val index: Int,
        val label: String,
        val language: String?,
        val selected: Boolean
    )

    private fun addSubtitleRow(label: String, onPick: () -> Unit) {
        val row = Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.text_primary))
            background = getDrawable(R.drawable.bg_focusable)
            textSize = 15f
            isFocusable = true
            setPadding(24, 12, 24, 12)
            setOnClickListener { onPick() }
        }
        val margin = (6 * resources.displayMetrics.density).toInt()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = margin }
        binding.subtitleRows.addView(row)
    }

    private fun chooseSubtitle(hit: Subtitles.Hit) {
        binding.subtitleStatus.text = getString(R.string.subtitles_saving)
        subtitleJob?.cancel()
        subtitleJob = channelScope().launch {
            val saved = withContext(Dispatchers.IO) { Subtitles.download(filesDir, hit) }
            if (!isActive) return@launch
            if (saved == null) {
                binding.subtitleStatus.text = getString(R.string.subtitles_failed)
                return@launch
            }
            rememberSubtitle("en")
            applySidecar(saved)
            closeSubtitlePanel()
            Toast.makeText(this@PlayerActivity, R.string.subtitles_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySidecar(saved: Subtitles.Saved) {
        val exo = player ?: return
        val channel = current ?: return
        val at = exo.currentPosition.coerceAtLeast(0L)
        playbackReady = false
        sidecar = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(saved.file))
            .setMimeType(saved.mime)
            .setLanguage("en")
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()
        exo.setMediaItem(mediaItem(channel), at)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun clearSidecar() {
        val exo = player ?: return
        val channel = current ?: return
        val at = exo.currentPosition.coerceAtLeast(0L)
        playbackReady = false
        sidecar = null
        exo.setMediaItem(mediaItem(channel), at)
        exo.prepare()
        exo.playWhenReady = true
        closeSubtitlePanel()
    }

    private fun closeSubtitlePanel() {
        subtitleJob?.cancel()
        if (::binding.isInitialized) binding.subtitlePanel.visibility = View.GONE
        if (touchPrimary &&
            ::binding.isInitialized &&
            binding.playbackMenu.visibility != View.VISIBLE &&
            binding.overlay.visibility != View.VISIBLE
        ) {
            setPhoneSystemBarsHidden(true)
        }
    }

    private fun offerRecording() {
        val channel = current ?: return
        val savedId = playlistId ?: return
        lifecycleScope.launch {
            val playlist = savedPlaylist(savedId) ?: return@launch
            val epg = runCatching { epgRepo.nowNext(playlist, channel) }.getOrNull()
            if (isDestroyed) return@launch
            RecordPrompt.show(this@PlayerActivity, savedId, channel, epg?.now, epg?.next)
        }
    }

    private fun isLiveNow(channel: Channel? = current): Boolean =
        channel != null && channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()

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
        if (exo != null) {
            exo.volume = 1f
            recycle(exo)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
            if (binding.subtitlePanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
            if (menuButtonFocused() || binding.btnSeekPlay.isFocused) {
                swallowMenuClick = false
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
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
                if (binding.playbackMenu.visibility == View.VISIBLE) return true
                previousChannel(); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                if (binding.playbackMenu.visibility == View.VISIBLE) return true
                nextChannel(); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (binding.subtitlePanel.visibility == View.VISIBLE) return false
                if (binding.playbackMenu.visibility == View.VISIBLE) return false
                if (nudgeTimeshift(-10_000L)) return true
                requestChannelReturn()
                armBrowseBack()
                finish()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
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
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun canEnterPip(): Boolean {
        if (!touchPrimary) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun enterPip(manual: Boolean) {
        if (!canEnterPip()) {
            if (manual) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        hidePlaybackMenu()
        binding.overlay.visibility = View.GONE
        pipPending = true
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
            .onFailure {
                pipPending = false
                if (manual) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
            }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipSwipeHome && canEnterPip() && player?.isPlaying == true) {
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

    private fun scrobbleStart(channel: Channel) {
        scrobbleJob?.cancel()
        stopScrobbleIfStarted()
        if (scrobble.provider == Scrobbler.NONE || scrobble.activeToken().isBlank()) return
        scrobbleChannel = channel
        scrobbleStarted = false
        val config = scrobble
        scrobbleJob = lifecycleScope.launch {
            delay(1_500L)
            if (!isActive || isDestroyed || current?.id != channel.id) return@launch
            scrobbleStarted = true
            withContext(Dispatchers.IO) {
                runCatching {
                    Scrobbler.start(config.provider, config.activeToken(), config.activeClientId(), channel)
                }
            }
        }
    }

    private fun scrobbleStop() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        stopScrobbleIfStarted()
    }

    private fun stopScrobbleIfStarted() {
        val channel = scrobbleChannel ?: return
        val started = scrobbleStarted
        scrobbleChannel = null
        scrobbleStarted = false
        if (!started) return
        if (scrobble.provider == Scrobbler.NONE || scrobble.activeToken().isBlank()) return
        val config = scrobble
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                Scrobbler.stop(config.provider, config.activeToken(), config.activeClientId(), channel)
            }
        }
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
            player?.playWhenReady = false
            nextPlayer?.playWhenReady = false
            prevPlayer?.playWhenReady = false
            secondaryPlayer?.playWhenReady = false
        }
    }

    override fun onDestroy() {
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
        super.onDestroy()
    }

    companion object {
        private const val INITIAL_SPOOL_LOG_MS = 1_500L
        private const val REBUFFER_LOG_MS = 400L
        private const val INITIAL_BADGE_MS = 450L
        private const val REBUFFER_BADGE_MS = 1_200L
        private const val LIVE_REBUFFER_BADGE_MS = 4_500L
        private const val STILL_SPOOL_MS = 2_500L
        private const val MAX_LIVE_RECOVER = 3
        private const val TRANSPORT_HIDE_MS = 3_000L
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_META = "meta"
        private const val EXTRA_NEXT = "next"
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
