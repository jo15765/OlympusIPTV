package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.ShowNames
import com.olympus.iptv.databinding.ActivityFreshBinding
import com.olympus.iptv.databinding.ItemFreshBinding
import com.olympus.iptv.databinding.ItemFreshEpisodeBinding
import com.olympus.iptv.databinding.ItemSeasonBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.EpisodeCache
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.playlist.Watchmode
import com.olympus.iptv.playlist.WatchmodeException
import com.olympus.iptv.playlist.XtreamClient
import com.olympus.iptv.ui.channelLogo
import com.olympus.iptv.ui.player.PlayerActivity
import com.olympus.iptv.ui.player.SeriesPlay
import com.olympus.iptv.ui.prefetchLogos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FreshActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFreshBinding
    private val playlistId by lazy { intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty() }
    private val rows = ArrayList<CatalogDb.FreshTitle>()
    private val adapter = FreshAdapter()
    private val seasonAdapter = SeasonAdapter()
    private val episodeAdapter = EpisodeAdapter()
    private var shown: CatalogDb.FreshTitle? = null
    private var seriesEpisodes = emptyList<Channel>()
    private var episodesBySeason: Map<Int, List<Channel>> = emptyMap()
    private var seasonNumbers = emptyList<Int>()
    private var season = 1
    private var seriesJob: Job? = null
    private var seasonSwitchToken = 0
    private var listFocusPos = 0
    private lateinit var cacheFile: File
    private var opening = false
    private var focusGuard: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreshBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (playlistId.isBlank()) {
            finish()
            return
        }
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.freshMain,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.freshDetail,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
        }
        cacheFile = File(filesDir, "watchmode-week.json")
        binding.freshList.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.freshList.adapter = adapter
        binding.freshList.itemAnimator = null
        binding.freshList.setHasFixedSize(true)
        binding.freshList.setItemViewCacheSize(COLUMNS * 2)
        (binding.freshList.layoutManager as GridLayoutManager).initialPrefetchItemCount = COLUMNS
        binding.freshList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val grid = recyclerView.layoutManager as? GridLayoutManager ?: return
                prefetchFrom(grid.findLastVisibleItemPosition() + 1)
            }
        })
        binding.detailSeasonList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.detailSeasonList.itemAnimator = null
        binding.detailSeasonList.setHasFixedSize(true)
        binding.detailSeasonList.adapter = seasonAdapter
        binding.detailEpisodeList.layoutManager = LinearLayoutManager(this)
        binding.detailEpisodeList.itemAnimator = null
        binding.detailEpisodeList.adapter = episodeAdapter
        binding.freshStatus.visibility = View.VISIBLE
        binding.freshStatus.text = getString(R.string.fresh_loading)
        binding.btnBack.setOnClickListener { leaveFresh() }
        binding.detailBack.setOnClickListener { leaveFresh() }
        // D-pad from content may move to Back; Back is the only intentional exit control.
        binding.btnBack.nextFocusDownId = binding.freshList.id
        binding.detailBack.nextFocusDownId = binding.detailSeasonList.id
        binding.freshList.nextFocusUpId = binding.btnBack.id
        binding.detailSeasonList.nextFocusUpId = binding.detailBack.id
        binding.detailPlay.nextFocusUpId = binding.detailBack.id
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (PlayerActivity.consumeBrowseBack()) return
                leaveFresh()
            }
        })
        attachFocusGuard()
        lifecycleScope.launch { load() }
    }

    override fun onDestroy() {
        detachFocusGuard()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!PlayerActivity.consumeBrowseBack()) leaveFresh()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            if (trapDpad(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun detailOpen(): Boolean = binding.freshDetail.visibility == View.VISIBLE

    private fun activeBack(): View = if (detailOpen()) binding.detailBack else binding.btnBack

    private fun focusAllowed(view: View?): Boolean {
        if (view == null) return false
        if (view === activeBack()) return true
        return focusIsIn(if (detailOpen()) binding.freshDetail else binding.freshMain, view)
    }

    private fun focusIsIn(root: View, start: View? = currentFocus): Boolean {
        var view = start ?: return false
        while (true) {
            if (view === root) return true
            view = view.parent as? View ?: return false
        }
    }

    private fun trapDpad(keyCode: Int): Boolean {
        val focused = currentFocus ?: run {
            reclaimFreshFocus()
            return true
        }
        if (!focusAllowed(focused)) {
            reclaimFreshFocus()
            return true
        }
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        val next = focused.focusSearch(direction)
        if (focusAllowed(next)) return false
        // Edge of the page — keep focus where it is (Back key/button is the exit).
        return true
    }

    private fun reclaimFreshFocus() {
        if (isDestroyed) return
        if (detailOpen()) {
            if (binding.detailSeriesPane.visibility == View.VISIBLE) {
                val seasonHolder = binding.detailSeasonList.findViewHolderForAdapterPosition(
                    seasonNumbers.indexOf(season).coerceAtLeast(0)
                )
                if (seasonHolder?.itemView?.requestFocus() == true) return
                if (binding.detailEpisodeList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true) {
                    return
                }
            }
            if (binding.detailPlay.visibility == View.VISIBLE && binding.detailPlay.requestFocus()) return
            binding.detailBack.requestFocus()
            return
        }
        focusFreshCard(listFocusPos)
    }

    private fun attachFocusGuard() {
        if (focusGuard != null) return
        val guard = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (isDestroyed) return@OnGlobalFocusChangeListener
            if (focusAllowed(newFocus)) return@OnGlobalFocusChangeListener
            binding.root.post {
                if (!isDestroyed && !focusAllowed(currentFocus)) reclaimFreshFocus()
            }
        }
        focusGuard = guard
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(guard)
    }

    private fun detachFocusGuard() {
        val guard = focusGuard ?: return
        focusGuard = null
        val observer = binding.root.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(guard)
    }

    private fun leaveFresh() {
        if (PlayerActivity.consumeBrowseBack()) return
        if (binding.freshDetail.visibility == View.VISIBLE) hideDetail() else finish()
    }

    private suspend fun load() {
        val app = application as OlympusIptvApp
        try {
            val ready = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
            if (isDestroyed) return
            if (!ready.isNullOrEmpty()) {
                showCards(ready)
            } else {
                showStatus(R.string.fresh_loading)
            }
            // Warm in background — do not block the grid on Watchmode / playable checks.
            app.warmFresh(playlistId)
            val job = app.freshJob
            if (job != null) {
                lifecycleScope.launch {
                    job.join()
                    if (isDestroyed) return@launch
                    val latest = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
                    if (isDestroyed) return@launch
                    when {
                        !latest.isNullOrEmpty() -> showCards(latest)
                        rows.isNotEmpty() -> return@launch
                        app.freshFailure == WatchmodeException.Kind.MISSING_KEY -> showStatus(R.string.fresh_need_key)
                        app.freshFailure == WatchmodeException.Kind.REJECTED -> showStatus(R.string.fresh_rejected)
                        app.freshFailure != null -> showStatus(R.string.fresh_failed)
                        else -> showStatus(R.string.fresh_empty)
                    }
                }
            } else if (ready.isNullOrEmpty()) {
                when {
                    app.freshFailure == WatchmodeException.Kind.MISSING_KEY -> showStatus(R.string.fresh_need_key)
                    app.freshFailure == WatchmodeException.Kind.REJECTED -> showStatus(R.string.fresh_rejected)
                    app.freshFailure != null -> showStatus(R.string.fresh_failed)
                    else -> showStatus(R.string.fresh_empty)
                }
            }
        } catch (_: OutOfMemoryError) {
            runCatching { app.catalogDb.releaseSearchExport() }
            if (!isDestroyed && rows.isEmpty()) showStatus(R.string.fresh_failed)
        }
    }

    private fun showCards(found: List<CatalogDb.FreshTitle>) {
        if (isDestroyed) return
        val same = rows.size == found.size && rows.indices.all { rows[it] == found[it] }
        if (!same) {
            rows.clear()
            rows.addAll(found)
            adapter.notifyDataSetChanged()
        }
        binding.freshStatus.visibility = View.GONE
        binding.freshList.post {
            if (isDestroyed || binding.freshDetail.visibility == View.VISIBLE) return@post
            val grid = binding.freshList.layoutManager as? GridLayoutManager
            val last = grid?.findLastVisibleItemPosition() ?: -1
            if (last >= 0) prefetchFrom(last + 1)
            if (currentFocus == null || !focusIsIn(binding.freshList)) {
                focusFreshCard(listFocusPos)
            }
        }
    }

    private fun focusFreshCard(position: Int) {
        if (isDestroyed || rows.isEmpty()) return
        val index = position.coerceIn(0, rows.lastIndex)
        listFocusPos = index
        binding.freshList.scrollToPosition(index)
        binding.freshList.post {
            if (isDestroyed || binding.freshDetail.visibility == View.VISIBLE) return@post
            val target = binding.freshList.findViewHolderForAdapterPosition(index)?.itemView
            if (target != null) {
                target.requestFocus()
            } else {
                binding.freshList.post {
                    if (!isDestroyed && binding.freshDetail.visibility != View.VISIBLE) {
                        binding.freshList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun prefetchFrom(start: Int) {
        if (start < 0 || start >= rows.size) return
        val urls = ArrayList<String>(COLUMNS)
        val end = (start + COLUMNS).coerceAtMost(rows.size)
        for (index in start until end) {
            rows[index].logoUrl?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        if (urls.isEmpty()) return
        val px = (160 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        prefetchLogos(this, urls, px)
    }

    private fun showStatus(message: Int) {
        if (isDestroyed) return
        binding.freshStatus.visibility = View.VISIBLE
        binding.freshStatus.text = getString(message)
    }

    private fun showDetail(title: CatalogDb.FreshTitle) {
        seriesJob?.cancel()
        seriesJob = null
        clearSeriesState()
        shown = title
        binding.freshDetail.visibility = View.VISIBLE
        binding.detailService.text = if (title.day.isBlank()) {
            title.service
        } else {
            getString(R.string.fresh_when, title.service, title.day)
        }
        binding.detailName.text = title.name
        bindArt(binding.detailLogo, title.logoUrl, 160, 230)
        val waiting = title.detail.startsWith("Premieres ") || title.detail.matches(SEASON_ONLY)
        binding.detailBody.text = if (waiting) getString(R.string.fresh_detail_loading) else title.detail
        val series = title.bucket == CatalogDb.SERIES
        if (series) {
            binding.detailPlay.visibility = View.GONE
            binding.detailSeriesPane.visibility = View.VISIBLE
            binding.detailEpisodeStatus.visibility = View.VISIBLE
            binding.detailEpisodeStatus.text = getString(R.string.episodes_loading)
            seasonAdapter.submit(emptyList())
            episodeAdapter.submit(emptyList())
            loadSeries(title)
        } else {
            binding.detailSeriesPane.visibility = View.GONE
            binding.detailPlay.visibility = View.VISIBLE
            binding.detailPlay.text = getString(R.string.fresh_play)
            binding.detailPlay.setOnClickListener { openMovie(title) }
            binding.detailPlay.post {
                if (!isDestroyed && shown?.id == title.id) binding.detailPlay.requestFocus()
            }
        }
        if (waiting) loadDescription(title)
    }

    private fun hideDetail() {
        seriesJob?.cancel()
        seriesJob = null
        seasonSwitchToken += 1
        shown = null
        clearSeriesState()
        seasonAdapter.submit(emptyList())
        episodeAdapter.submit(emptyList())
        binding.detailSeriesPane.visibility = View.GONE
        binding.detailPlay.visibility = View.VISIBLE
        binding.detailEpisodeStatus.visibility = View.GONE
        binding.freshDetail.visibility = View.GONE
        focusFreshCard(listFocusPos)
    }

    private fun clearSeriesState() {
        seriesEpisodes = emptyList()
        episodesBySeason = emptyMap()
        seasonNumbers = emptyList()
        season = 1
    }

    private fun loadDescription(title: CatalogDb.FreshTitle) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val plot = withContext(Dispatchers.IO) {
                runCatching {
                    Watchmode.synopsis(cacheFile, title.watchmodeId)
                        ?: app.playlistStore.account(playlistId)?.let { saved ->
                            XtreamClient().titleLine(saved, title.bucket == CatalogDb.SERIES, title.id)
                        }
                }.getOrNull()
            }
            if (isDestroyed || isFinishing) return@launch
            if (plot.isNullOrBlank()) {
                if (shown?.id == title.id && shown?.service == title.service) {
                    binding.detailBody.text = title.detail
                }
                return@launch
            }
            val seasonLine = title.detail.takeIf { it.startsWith("Season ") }?.substringBefore('.')?.trim()
            val body = if (seasonLine.isNullOrBlank() || plot.startsWith(seasonLine)) plot else "$seasonLine. $plot"
            val index = rows.indexOfFirst { it.id == title.id && it.bucket == title.bucket && it.service == title.service }
            if (index >= 0) rows[index] = rows[index].copy(detail = body)
            if (shown?.id == title.id && shown?.service == title.service) binding.detailBody.text = body
        }
    }

    private fun loadSeries(title: CatalogDb.FreshTitle) {
        val app = application as OlympusIptvApp
        seriesJob = lifecycleScope.launch {
            val channel = withContext(Dispatchers.IO) {
                runCatching { app.catalogDb.readChannel(playlistId, title.bucket, title.id) }.getOrNull()
            }
            if (isDestroyed || shown?.id != title.id) return@launch
            if (channel == null) {
                binding.detailEpisodeStatus.visibility = View.VISIBLE
                binding.detailEpisodeStatus.text = getString(R.string.channel_missing)
                return@launch
            }
            val seriesId = channel.seriesId?.takeIf { it.isNotBlank() } ?: channel.id
            val saved = withContext(Dispatchers.IO) {
                runCatching { app.playlistStore.account(playlistId) }.getOrNull()
            }
            if (saved == null || isDestroyed || shown?.id != title.id) return@launch
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    EpisodeSequence.load(filesDir, saved, seriesId, refresh = lifecycleScope)
                }.getOrDefault(emptyList())
            }
            if (isDestroyed || shown?.id != title.id) return@launch
            if (loaded.isEmpty()) {
                binding.detailEpisodeStatus.visibility = View.VISIBLE
                binding.detailEpisodeStatus.text = getString(R.string.episodes_none)
                return@launch
            }
            presentSeries(loaded, focusSeasons = true)
            // If a background refresh lands, refresh the open detail once.
            lifecycleScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(2_500L)
                val refreshed = runCatching {
                    EpisodeCache.peek(filesDir, playlistId, seriesId)
                }.getOrNull()
                if (refreshed.isNullOrEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && shown?.id == title.id && refreshed.size != seriesEpisodes.size) {
                        presentSeries(refreshed, focusSeasons = false)
                    }
                }
            }
        }
    }

    private fun presentSeries(loaded: List<Channel>, focusSeasons: Boolean) {
        if (isDestroyed || loaded.isEmpty()) return
        val playable = FreshSeriesNav.playable(loaded)
        val grouped = FreshSeriesNav.bySeason(playable)
        val numbers = FreshSeriesNav.seasons(playable)
        val prefer = shown?.season
        if (numbers.isEmpty()) {
            seriesEpisodes = emptyList()
            episodesBySeason = emptyMap()
            seasonNumbers = emptyList()
            seasonAdapter.submit(emptyList())
            episodeAdapter.submit(emptyList())
            binding.detailEpisodeStatus.visibility = View.VISIBLE
            binding.detailEpisodeStatus.text = if (prefer != null && prefer > 0) {
                getString(R.string.fresh_season_missing, prefer)
            } else {
                getString(R.string.episodes_none)
            }
            return
        }
        if (prefer != null && prefer > 0 && prefer !in numbers) {
            seriesEpisodes = playable
            episodesBySeason = grouped
            seasonNumbers = numbers
            seasonAdapter.submit(numbers)
            episodeAdapter.submit(emptyList())
            binding.detailEpisodeStatus.visibility = View.VISIBLE
            binding.detailEpisodeStatus.text = getString(R.string.fresh_season_missing, prefer)
            if (focusSeasons) focusSeasonChip(0)
            return
        }
        seriesEpisodes = playable
        episodesBySeason = grouped
        season = FreshSeriesNav.pickSeason(numbers, season, prefer)
        binding.detailEpisodeStatus.visibility = View.GONE
        val seasonsChanged = !FreshSeriesNav.sameSeasons(seasonNumbers, numbers)
        seasonNumbers = numbers
        if (seasonsChanged) {
            seasonAdapter.submit(numbers)
        } else {
            seasonAdapter.refreshSelection()
        }
        val episodes = grouped[season].orEmpty()
        episodeAdapter.submit(episodes)
        if (focusSeasons) {
            focusSeasonChip(numbers.indexOf(season).coerceAtLeast(0))
        }
    }

    private fun selectSeason(number: Int) {
        if (isDestroyed || number !in seasonNumbers) return
        if (number == season) return
        val previous = season
        season = number
        val token = ++seasonSwitchToken
        val episodes = episodesBySeason[number].orEmpty()
        val previousIndex = seasonNumbers.indexOf(previous)
        val nextIndex = seasonNumbers.indexOf(number)
        if (previousIndex >= 0) seasonAdapter.notifyItemChanged(previousIndex)
        if (nextIndex >= 0) seasonAdapter.notifyItemChanged(nextIndex)
        // Keep the episode list in sync with the season chip immediately (avoid Play on stale rows).
        episodeAdapter.submit(episodes)
        binding.detailEpisodeStatus.visibility =
            if (episodes.isEmpty()) View.VISIBLE else View.GONE
        if (episodes.isEmpty()) {
            binding.detailEpisodeStatus.text = getString(R.string.fresh_season_missing, number)
        }
        binding.detailEpisodeList.post {
            if (isDestroyed || token != seasonSwitchToken || shown == null) return@post
            if (nextIndex >= 0) {
                binding.detailSeasonList.scrollToPosition(nextIndex)
                binding.detailSeasonList.findViewHolderForAdapterPosition(nextIndex)?.itemView?.requestFocus()
            }
        }
    }

    private fun focusSeasonChip(index: Int) {
        if (index < 0) return
        binding.detailSeasonList.scrollToPosition(index)
        binding.detailSeasonList.post {
            if (isDestroyed || binding.freshDetail.visibility != View.VISIBLE) return@post
            val holder = binding.detailSeasonList.findViewHolderForAdapterPosition(index)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                binding.detailSeasonList.post {
                    if (!isDestroyed) {
                        binding.detailSeasonList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun openMovie(title: CatalogDb.FreshTitle) {
        if (opening || playlistId.isBlank() || title.id.isBlank()) return
        opening = true
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            try {
                val channel = withContext(Dispatchers.IO) {
                    runCatching { app.catalogDb.readChannel(playlistId, title.bucket, title.id) }.getOrNull()
                }
                if (isDestroyed || isFinishing) return@launch
                if (channel == null) {
                    Toast.makeText(this@FreshActivity, R.string.channel_missing, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val playing = if (channel.kind == ContentKind.LIVE) channel.copy(kind = ContentKind.VOD) else channel
                app.playbackQueue = listOf(playing)
                startActivity(
                    PlayerActivity.intent(
                        this@FreshActivity,
                        channel = playing,
                        channelMeta = title.detail,
                        channelNext = "",
                        startIndex = 0,
                        playlistId = playlistId
                    )
                )
            } finally {
                opening = false
            }
        }
    }

    private fun playEpisode(episode: Channel) {
        if (opening || playlistId.isBlank() || episode.id.isBlank()) return
        if (episode.streamUrl.isBlank()) {
            Toast.makeText(this, R.string.fresh_episode_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val wantSeason = episode.season ?: season
        val queue = FreshSeriesNav.playQueue(seriesEpisodes, wantSeason)
        if (queue.isEmpty() || queue.none { it.id == episode.id }) {
            Toast.makeText(this, R.string.fresh_episode_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        opening = true
        try {
            SeriesPlay.open(this, playlistId, queue, episode)
        } finally {
            opening = false
        }
    }

    private fun bindArt(view: ImageView, url: String?, widthDp: Int, heightDp: Int) {
        if (url.isNullOrBlank()) {
            view.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }
        val density = view.resources.displayMetrics.density
        val px = (widthDp.coerceAtLeast(heightDp) * density).toInt().coerceAtLeast(1)
        view.load(url) {
            channelLogo(px)
            scale(Scale.FIT)
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }
    }

    private inner class FreshAdapter : RecyclerView.Adapter<FreshAdapter.Holder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long {
            val row = rows.getOrNull(position) ?: return position.toLong()
            return (row.bucket + '\u0000' + row.id + '\u0000' + row.service).hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemFreshBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            rows.getOrNull(position)?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val binding: ItemFreshBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(title: CatalogDb.FreshTitle) {
                binding.freshName.text = title.name
                binding.freshGroup.text = if (title.day.isBlank()) {
                    title.service
                } else {
                    getString(R.string.fresh_when, title.service, title.day)
                }
                bindArt(binding.freshLogo, title.logoUrl, 160, 220)
                binding.root.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) listFocusPos = pos
                    }
                }
                binding.root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) listFocusPos = pos
                    showDetail(title)
                }
                binding.root.setOnLongClickListener {
                    if (playlistId.isBlank()) return@setOnLongClickListener true
                    WatchQueue.offer(
                        this@FreshActivity,
                        playlistId,
                        com.olympus.iptv.data.QueueItem(
                            bucket = title.bucket,
                            id = title.id,
                            name = title.name,
                            logoUrl = title.logoUrl,
                            seriesId = if (title.bucket == CatalogDb.SERIES) title.id else null
                        )
                    )
                    true
                }
            }
        }
    }

    private inner class SeasonAdapter : RecyclerView.Adapter<SeasonAdapter.Holder>() {
        private val numbers = ArrayList<Int>()

        fun submit(list: List<Int>) {
            numbers.clear()
            numbers.addAll(list)
            notifyDataSetChanged()
        }

        fun refreshSelection() {
            if (numbers.isEmpty()) return
            notifyItemRangeChanged(0, numbers.size)
        }

        override fun getItemId(position: Int): Long =
            numbers.getOrNull(position)?.toLong() ?: position.toLong()

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemSeasonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            numbers.getOrNull(position)?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = numbers.size

        inner class Holder(private val row: ItemSeasonBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(number: Int) {
                row.seasonName.text = getString(R.string.season_number, number)
                row.seasonCheck.visibility = View.GONE
                row.root.isSelected = number == season
                row.root.setOnClickListener {
                    val index = bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    selectSeason(number)
                }
            }
        }
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        private val items = ArrayList<Channel>()

        fun submit(list: List<Channel>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long =
            items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemFreshEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            items.getOrNull(position)?.let { holder.bind(it, position) }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val row: ItemFreshEpisodeBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(episode: Channel, position: Int) {
                val number = episode.number ?: (position + 1)
                val title = shown?.name.orEmpty()
                row.episodeTitle.text = ShowNames.episodeHeading(
                    title,
                    episode.season ?: season,
                    number,
                    episode.name
                )
                val meta = listOfNotNull(
                    episode.aired?.takeIf { it.isNotBlank() },
                    episode.runtime?.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                row.episodeMeta.text = meta
                row.episodeMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
                val plot = episode.blurb.orEmpty()
                row.episodePlot.text = plot
                row.episodePlot.visibility = if (plot.isBlank()) View.GONE else View.VISIBLE
                val art = episode.logoUrl?.takeIf { it.isNotBlank() } ?: shown?.logoUrl
                if (row.episodeArt.tag != art) {
                    row.episodeArt.tag = art
                    bindArt(row.episodeArt, art, 168, 94)
                }
                val play = View.OnClickListener { playEpisode(episode) }
                row.root.setOnClickListener(play)
                row.episodePlay.setOnClickListener(play)
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val COLUMNS = 4
        private val SEASON_ONLY = Regex("Season \\d+\\.")

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, FreshActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
