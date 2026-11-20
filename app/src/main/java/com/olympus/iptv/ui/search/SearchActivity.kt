package com.olympus.iptv.ui.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ChannelNames
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.WatchEntry
import com.olympus.iptv.databinding.ActivitySearchBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.CountryIndex
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.ui.player.SeriesPlay
import com.olympus.iptv.ui.SoftKeyboard
import com.olympus.iptv.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: SearchTileAdapter
    private val favoriteIds = mutableSetOf<String>()
    private var hiddenCountries = emptySet<String>()
    private var hiddenGroups = emptySet<String>()
    private var hiddenChannels = emptySet<String>()
    private var channelNames = emptyMap<String, String>()
    private var groupNames = emptyMap<String, String>()
    private var lineupConfig = LineupConfig()
    private var playlistId: String? = null
    private var recentEntries: List<WatchEntry> = emptyList()
    private var searchPreview: ExoPlayer? = null
    private var searchPreviewId: String? = null
    private var searchJob: Job? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchWait: Runnable? = null
    private var focusResults = false
    private val voiceSearch = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isBlank()) return@registerForActivityResult
        binding.inputSearch.setText(spoken)
        binding.inputSearch.setSelection(spoken.length)
        submitSearch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OlympusIptvApp
        if (app.guideSource?.liveChannels.isNullOrEmpty() && app.guidePlaylistId.isNullOrBlank()) {
            finish()
            return
        }
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.post {
            val seeded = intent.getStringExtra("query")?.trim().orEmpty()
            if (seeded.isNotBlank()) {
                binding.inputSearch.setText(seeded)
                submitSearch()
            }
        }
        val touchPrimary = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)
        if (touchPrimary) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.root,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
            binding.resultGrid.isNestedScrollingEnabled = true
            binding.btnBack.isFocusableInTouchMode = false
            binding.btnSearch.isFocusableInTouchMode = false
            binding.btnHideKeyboard.isFocusableInTouchMode = false
        }

        val span = if (touchPrimary) {
            2
        } else {
            (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 220f)
                .toInt()
                .coerceIn(3, 6)
        }
        val layout = GridLayoutManager(this, span)
        adapter = SearchTileAdapter(
            isFavorite = { channel, section ->
                Favorites.keyFor(channel, section) in favoriteIds ||
                    (section == Favorites.LIVE && channel.id in favoriteIds)
            },
            onFavorite = { channel, section -> toggleFavorite(channel, section) },
            onClick = { channel, index, queue -> play(channel, index, queue) },
            onLongClick = { channel, section ->
                val savedId = playlistId
                val item = if (savedId == null) null else com.olympus.iptv.ui.browse.WatchQueue.itemFor(
                    channel,
                    section == com.olympus.iptv.data.Favorites.SERIES
                )
                if (savedId != null && item != null) {
                    com.olympus.iptv.ui.browse.WatchQueue.offer(this, savedId, item)
                }
            },
            watchProgress = { channel -> watchProgressOf(channel) }
        )
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) span else 1
        }
        binding.resultGrid.layoutManager = layout
        binding.resultGrid.adapter = adapter
        binding.resultGrid.setHasFixedSize(true)
        binding.resultGrid.itemAnimator = null
        binding.resultGrid.setItemViewCacheSize(8)
        binding.resultGrid.recycledViewPool.setMaxRecycledViews(1, 16)
        binding.resultGrid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                val idle = newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
                adapter.setScrolling(!idle)
                if (!idle) return
                val first = layout.findFirstVisibleItemPosition()
                val last = layout.findLastVisibleItemPosition()
                adapter.refreshLogos(first, last)
                adapter.prefetch(recyclerView.context, last + 1, 12)
            }
        })

        binding.btnBack.setOnClickListener { finish() }
        SoftKeyboard.keepOffUntilAsked(this, binding.inputSearch)
        binding.btnSearch.setOnClickListener { submitSearch() }
        binding.btnVoice.setOnClickListener { startVoiceSearch() }
        if (!touchPrimary && voiceReady()) {
            binding.inputSearch.hint = getString(R.string.search_say)
            binding.btnVoice.requestFocus()
        }
        binding.btnHideKeyboard.setOnClickListener { hideKeyboard() }
        paintRecentSearches()
        binding.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.inputSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = scheduleSearch(s?.toString().orEmpty())
        })
        playlistId = app.guidePlaylistId
        val staged = app.stagedLineup?.takeIf { it.first == playlistId }?.second
        if (staged != null) applyConfig(staged)
        lifecycleScope.launch {
            val id = playlistId
            if (id != null) {
                if (staged == null) {
                    applyConfig(withContext(Dispatchers.IO) { app.playlistStore.lineup(id) })
                }
                favoriteIds.clear()
                favoriteIds.addAll(withContext(Dispatchers.IO) { app.playlistStore.favoriteIds(id) })
            }
            if (!isFinishing) showMatches(binding.inputSearch.text?.toString().orEmpty())
        }
        binding.inputSearch.requestFocus()
        paintSearchNowPlaying()
    }

    override fun onResume() {
        super.onResume()
        val id = playlistId
        if (id != null) {
            lifecycleScope.launch {
                recentEntries = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    (application as OlympusIptvApp).playlistStore.recent(id)
                }
                if (!isFinishing) adapter.notifyDataSetChanged()
            }
        }
        paintSearchNowPlaying()
    }

    override fun onPause() {
        searchPreview?.playWhenReady = false
        super.onPause()
    }

    override fun onDestroy() {
        searchPreview?.release()
        searchPreview = null
        searchPreviewId = null
        super.onDestroy()
    }

    private fun watchProgressOf(channel: Channel): Int? {
        val spot = if (!channel.seriesId.isNullOrBlank() && channel.streamUrl.isBlank()) {
            recentEntries.firstOrNull { it.seriesId == channel.seriesId && it.canResume() }
        } else {
            recentEntries.firstOrNull { it.channelId == channel.id && it.canResume() }
        } ?: return null
        val position = spot.positionMs ?: return null
        val duration = spot.durationMs ?: return null
        if (duration <= 0L) return null
        return ((position * 1000L) / duration).toInt().coerceIn(1, 1000)
    }

    private fun paintSearchNowPlaying() {
        if (!::binding.isInitialized) return
        val app = application as OlympusIptvApp
        val channel = app.lastPlayed
        val match = channel != null && !channel.streamUrl.isBlank() &&
            (app.lastPlayedPlaylistId == null || app.lastPlayedPlaylistId == app.guidePlaylistId)
        if (!match || channel == null) {
            binding.nowPlayingBar.visibility = View.GONE
            return
        }
        val live = channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()
        binding.nowPlayingLabel.text = if (live) {
            getString(R.string.preview_live_return, channel.name)
        } else {
            getString(R.string.now_playing) + " · " + channel.name
        }
        binding.nowPlayingBar.visibility = View.VISIBLE
        if (live && !com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) {
            startSearchPreview(channel)
        } else {
            stopSearchPreview()
        }
        val open = View.OnClickListener {
            openPlayer(channel, 0, listOf(channel))
        }
        binding.nowPlayingBar.setOnClickListener(open)
        binding.nowPlayingResume.setOnClickListener(open)
    }

    private fun startSearchPreview(channel: Channel) {
        if (channel.streamUrl.isBlank()) return
        binding.searchPreview.visibility = View.VISIBLE
        if (searchPreviewId == channel.id && searchPreview != null) {
            searchPreview?.playWhenReady = true
            return
        }
        val player = searchPreview ?: ExoPlayer.Builder(this).build().also {
            it.volume = 0f
            searchPreview = it
            binding.searchPreview.player = it
        }
        searchPreviewId = channel.id
        player.setMediaItem(MediaItem.fromUri(channel.streamUrl))
        player.prepare()
        player.playWhenReady = true
    }

    private fun stopSearchPreview() {
        searchPreviewId = null
        searchPreview?.stop()
        searchPreview?.clearMediaItems()
        if (::binding.isInitialized) binding.searchPreview.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
        ) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyConfig(config: LineupConfig) {
        hiddenCountries = config.hiddenCountries.orEmpty().toSet()
        hiddenGroups = config.hiddenGroups.orEmpty().toSet()
        hiddenChannels = config.hiddenChannels.orEmpty().toSet()
        channelNames = config.channelNames.orEmpty()
        groupNames = config.groupNames.orEmpty()
        lineupConfig = config
    }

    private fun blocked(channel: Channel): Boolean {
        if (channel.id in hiddenChannels) return true
        val groupId = channel.categoryId ?: channel.groupTitle
        if (groupId != null && groupId in hiddenGroups) return true
        if (hiddenCountries.isEmpty()) return false
        val ids = listOfNotNull(
            channel.countryId?.takeIf { it != CountryIndex.OTHER_ID },
            CountryIndex.idFor(channel.groupTitle),
            CountryIndex.idFor(channel.name)
        )
        return ids.any { it in hiddenCountries }
    }

    private fun submitSearch() {
        focusResults = true
        searchWait?.let { searchHandler.removeCallbacks(it) }
        val query = binding.inputSearch.text?.toString().orEmpty()
        rememberSearch(query)
        showMatches(query)
    }

    private fun recentPrefs() = getSharedPreferences("olympus_search_recent", MODE_PRIVATE)

    private fun recentQueries(): List<String> =
        recentPrefs().getString("q", "")
            .orEmpty()
            .split('\u001f')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun rememberSearch(raw: String) {
        val query = raw.trim()
        if (query.length < 2) return
        val next = (listOf(query) + recentQueries().filter { !it.equals(query, ignoreCase = true) })
            .take(8)
        recentPrefs().edit().putString("q", next.joinToString("\u001f")).apply()
        paintRecentSearches()
    }

    private fun paintRecentSearches() {
        if (!::binding.isInitialized) return
        val row = binding.recentSearchRow
        row.removeAllViews()
        val queries = recentQueries()
        if (queries.isEmpty()) {
            binding.recentSearches.visibility = View.GONE
            return
        }
        binding.recentSearches.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val label = TextView(this).apply {
            text = getString(R.string.search_recent)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, 0, (10 * density).toInt(), 0)
        }
        row.addView(label)
        queries.forEach { query ->
            val chip = TextView(this).apply {
                text = query
                setTextColor(0xFFE0B15A.toInt())
                textSize = 14f
                background = getDrawable(R.drawable.bg_search_pill)
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    binding.inputSearch.setText(query)
                    binding.inputSearch.setSelection(query.length)
                    submitSearch()
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = (8 * density).toInt()
            row.addView(chip, params)
        }
    }

    private fun hideKeyboard() {
        SoftKeyboard.hide(this)
        binding.resultGrid.post {
            val favorite = binding.resultGrid.findViewHolderForAdapterPosition(1)?.itemView
                ?.findViewById<View>(R.id.favorite)
            favorite?.requestFocus() ?: binding.btnHideKeyboard.requestFocus()
        }
    }

    private fun toggleFavorite(channel: Channel, section: String) {
        val id = playlistId ?: return
        val key = Favorites.keyFor(channel, section)
        val adding = key !in favoriteIds && !(section == Favorites.LIVE && channel.id in favoriteIds)
        if (adding) {
            favoriteIds.add(key)
        } else {
            favoriteIds.remove(key)
            favoriteIds.remove(Favorites.key(channel))
            if (section == Favorites.LIVE) favoriteIds.remove(channel.id)
        }
        adapter.notifyDataSetChanged()
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val base = app.playlistStore.favoritesRevision(id)
            app.playlistStore.setFavorite(id, key, adding)
            if (!adding && section == Favorites.LIVE) app.playlistStore.setFavorite(id, channel.id, false)
            val email = app.playlistStore.session.first()?.email?.trim().orEmpty()
            if (email.isBlank()) return@launch
            LineupClient(app).pushFavorites(
                email,
                app.playlistStore.deviceId(),
                app.playlistStore.favorites(id),
                app.playlistStore.favoritesRevision(id),
                base
            )
        }
    }

    private fun voiceReady(): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return intent.resolveActivity(packageManager) != null
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_voice))
        }
        try {
            voiceSearch.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.search_voice_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleSearch(rawQuery: String) {
        searchWait?.let { searchHandler.removeCallbacks(it) }
        val run = Runnable { showMatches(rawQuery) }
        searchWait = run
        // FTS path is fast; debounce keystrokes so D-pad typing doesn't flood queries.
        if (rawQuery.isBlank()) searchHandler.post(run)
        else searchHandler.postDelayed(run, 150L)
    }

    private fun showMatches(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()
        if (query.isEmpty()) {
            adapter.submit(emptyList())
            binding.resultCount.text = ""
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = getString(R.string.search_prompt)
            binding.resultGrid.visibility = View.INVISIBLE
            return
        }
        searchJob = lifecycleScope.launch {
            val app = application as OlympusIptvApp
            val found = withContext(Dispatchers.IO) {
                coroutineScope {
                    val live = async { libraryMatches(app, CatalogDb.LIVE, query) { true } }
                    val movies = async { libraryMatches(app, CatalogDb.VOD, query) { it.seriesId.isNullOrBlank() } }
                    val shows = async { libraryMatches(app, CatalogDb.SERIES, query) { true } }
                    Triple(live.await(), movies.await(), shows.await())
                }
            }
            if (!isActive) return@launch
            val rows = buildList {
                addSection(getString(R.string.search_tv), found.first, Favorites.LIVE)
                addSection(getString(R.string.search_movies), found.second, Favorites.VOD)
                addSection(getString(R.string.search_shows), found.third, Favorites.SERIES)
            }
            adapter.submit(rows)
            val total = found.first.size + found.second.size + found.third.size
            binding.resultCount.text = getString(R.string.search_count, total)
            val empty = rows.isEmpty()
            binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
            binding.emptyText.text = getString(R.string.search_empty)
            binding.resultGrid.visibility = if (empty) View.INVISIBLE else View.VISIBLE
            if (focusResults) {
                focusResults = false
                if (!empty) hideKeyboard()
            }
        }
    }

    private fun takeMatching(channels: List<Channel>, query: String): List<Channel> {
        val out = ArrayList<Channel>(PER_SECTION)
        for (channel in channels) {
            if (blocked(channel) || !matches(channel, query)) continue
            out.add(renamed(channel))
            if (out.size == PER_SECTION) break
        }
        return out
    }

    private fun libraryMatches(
        app: OlympusIptvApp,
        bucket: String,
        query: String,
        include: (Channel) -> Boolean
    ): List<Channel> {
        val id = app.guidePlaylistId ?: return emptyList()
        val db = app.catalogDb
        val found = ArrayList<Channel>(PER_SECTION)
        val seen = HashSet<String>()
        fun add(channel: Channel) {
            if (found.size >= PER_SECTION || !seen.add(channel.id)) return
            if (!include(channel) || blocked(channel)) return
            found.add(renamed(channel))
        }
        db.searchChannels(id, bucket, query, PER_SECTION * 2).forEach { add(tagged(bucket, it)) }
        val renamedIds = channelNames.filterValues { it.contains(query, ignoreCase = true) }.keys
        if (found.size < PER_SECTION && renamedIds.isNotEmpty()) {
            db.readChannelsByIds(id, bucket, renamedIds).forEach { add(tagged(bucket, it)) }
        }
        return found
    }

    private fun tagged(bucket: String, channel: Channel): Channel {
        return if (bucket == CatalogDb.SERIES && channel.seriesId.isNullOrBlank()) {
            channel.copy(seriesId = channel.id)
        } else {
            channel
        }
    }

    private fun MutableList<SearchEntry>.addSection(title: String, channels: List<Channel>, sectionKey: String) {
        if (channels.isEmpty()) return
        add(SearchEntry(header = title))
        channels.forEachIndexed { index, channel ->
            add(SearchEntry(channel = channel, indexInSection = index, section = channels, sectionKey = sectionKey))
        }
    }

    private fun renamed(channel: Channel): Channel {
        val name = ChannelNames.present(channel, lineupConfig)
        val groupId = channel.categoryId ?: channel.groupTitle
        val group = groupId?.let {
            com.olympus.iptv.data.GroupRename.shown(it, channel.groupTitle.orEmpty(), lineupConfig)
                .takeIf { renamed -> renamed != channel.groupTitle }
        }
        if (name == channel.name && group == null) return channel
        return channel.copy(name = name, groupTitle = group ?: channel.groupTitle)
    }

    private fun matches(channel: Channel, query: String): Boolean {
        val haystack = buildString {
            append(ChannelNames.present(channel, lineupConfig))
            append(' ')
            append(channel.groupTitle.orEmpty())
            append(' ')
            val groupId = channel.categoryId ?: channel.groupTitle
            groupId?.let { groupNames[it] }?.let {
                append(it)
                append(' ')
            }
        }.lowercase()
        return query.lowercase()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .all { token -> haystack.contains(token) }
    }

    private fun play(channel: Channel, index: Int, queue: List<Channel>) {
        if (!channel.seriesId.isNullOrBlank() && channel.streamUrl.isBlank()) {
            val seriesId = channel.seriesId
            lifecycleScope.launch {
                val saved = (application as OlympusIptvApp).playlistStore.session.first() ?: return@launch
                val app = application as OlympusIptvApp
                val episodes = EpisodeSequence.load(filesDir, saved, seriesId, app.appScope)
                if (episodes.isEmpty()) return@launch
                val spot = withContext(Dispatchers.IO) {
                    app.playlistStore.recent(saved.id).firstOrNull { it.seriesId == seriesId && it.canResume() }
                }
                val ordered = EpisodeSequence.ordered(episodes)
                val episode = ordered.firstOrNull { it.id == spot?.channelId } ?: ordered.first()
                SeriesPlay.open(this@SearchActivity, saved.id, ordered, episode)
            }
            return
        }
        openPlayer(channel, index, queue)
    }

    private fun openPlayer(channel: Channel, index: Int, queue: List<Channel>) {
        val app = application as OlympusIptvApp
        app.playbackQueue = queue
        startActivity(
            PlayerActivity.intent(
                this,
                channel = channel,
                channelMeta = channel.groupTitle.orEmpty(),
                channelNext = "",
                startIndex = index,
                playlistId = app.guidePlaylistId
            )
        )
    }

    companion object {
        private const val PER_SECTION = 40
        private val WHITESPACE = Regex("\\s+")
    }
}
