package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var season = 1
    private var seriesJob: Job? = null
    private lateinit var cacheFile: File
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreshBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (playlistId.isBlank()) {
            finish()
            return
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
        binding.detailSeasonList.adapter = seasonAdapter
        binding.detailEpisodeList.layoutManager = LinearLayoutManager(this)
        binding.detailEpisodeList.itemAnimator = null
        binding.detailEpisodeList.setHasFixedSize(true)
        binding.detailEpisodeList.adapter = episodeAdapter
        binding.freshStatus.visibility = View.VISIBLE
        binding.freshStatus.text = getString(R.string.fresh_loading)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leaveFresh()
            }
        })
        lifecycleScope.launch { load() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) leaveFresh()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun leaveFresh() {
        if (binding.freshDetail.visibility == View.VISIBLE) hideDetail() else finish()
    }

    private suspend fun load() {
        val app = application as OlympusIptvApp
        try {
            val ready = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
            if (isDestroyed) return
            if (!ready.isNullOrEmpty()) showCards(ready)
            app.warmFresh(playlistId)
            app.freshJob?.join()
            if (isDestroyed) return
            val latest = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
            if (isDestroyed) return
            when {
                !latest.isNullOrEmpty() -> showCards(latest)
                rows.isNotEmpty() -> return
                app.freshFailure == WatchmodeException.Kind.MISSING_KEY -> showStatus(R.string.fresh_need_key)
                app.freshFailure == WatchmodeException.Kind.REJECTED -> showStatus(R.string.fresh_rejected)
                app.freshFailure != null -> showStatus(R.string.fresh_failed)
                else -> showStatus(R.string.fresh_empty)
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
            if (isDestroyed) return@post
            val grid = binding.freshList.layoutManager as? GridLayoutManager
            val last = grid?.findLastVisibleItemPosition() ?: -1
            if (last >= 0) prefetchFrom(last + 1)
            binding.freshList.requestFocus()
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
        seriesEpisodes = emptyList()
        season = 1
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
            binding.detailPlay.requestFocus()
        }
        if (waiting) loadDescription(title)
    }

    private fun hideDetail() {
        seriesJob?.cancel()
        seriesJob = null
        shown = null
        seriesEpisodes = emptyList()
        seasonAdapter.submit(emptyList())
        episodeAdapter.submit(emptyList())
        binding.detailSeriesPane.visibility = View.GONE
        binding.detailPlay.visibility = View.VISIBLE
        binding.freshDetail.visibility = View.GONE
        binding.freshList.requestFocus()
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
            val cached = withContext(Dispatchers.IO) {
                runCatching { EpisodeCache.peek(filesDir, playlistId, seriesId) }.getOrNull()
            }
            if (!cached.isNullOrEmpty() && shown?.id == title.id) presentSeries(cached, focus = true)
            val fresh = withContext(Dispatchers.IO) {
                runCatching { EpisodeSequence.fetch(saved, seriesId) }.getOrDefault(emptyList())
            }
            if (isDestroyed || shown?.id != title.id) return@launch
            if (fresh.isEmpty()) {
                if (seriesEpisodes.isEmpty()) {
                    binding.detailEpisodeStatus.visibility = View.VISIBLE
                    binding.detailEpisodeStatus.text = getString(R.string.episodes_none)
                }
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { EpisodeCache.store(filesDir, playlistId, seriesId, fresh) }
            }
            if (shown?.id == title.id) presentSeries(fresh, focus = seriesEpisodes.isEmpty())
        }
    }

    private fun presentSeries(loaded: List<Channel>, focus: Boolean) {
        if (isDestroyed || loaded.isEmpty()) return
        seriesEpisodes = loaded
        binding.detailEpisodeStatus.visibility = View.GONE
        val numbers = seasonNumbers()
        if (numbers.isEmpty()) return
        if (season !in numbers) season = numbers.first()
        seasonAdapter.submit(numbers)
        showSeasonEpisodes(focusSeasons = focus)
    }

    private fun seasonNumbers(): List<Int> =
        seriesEpisodes.map { it.season ?: 1 }.distinct().sorted()

    private fun showSeasonEpisodes(focusSeasons: Boolean) {
        val numbers = seasonNumbers()
        val shownEps = seriesEpisodes.filter { (it.season ?: 1) == season }
        episodeAdapter.submit(shownEps)
        seasonAdapter.notifyDataSetChanged()
        val seasonIndex = numbers.indexOf(season).coerceAtLeast(0)
        if (numbers.isNotEmpty()) binding.detailSeasonList.scrollToPosition(seasonIndex)
        if (focusSeasons && numbers.isNotEmpty()) {
            binding.detailSeasonList.post {
                if (isDestroyed) return@post
                binding.detailSeasonList.findViewHolderForAdapterPosition(seasonIndex)?.itemView?.requestFocus()
            }
        } else if (shownEps.isNotEmpty()) {
            binding.detailEpisodeList.post {
                if (isDestroyed) return@post
                binding.detailEpisodeList.findViewHolderForAdapterPosition(0)
                    ?.itemView
                    ?.findViewById<View>(R.id.episodePlay)
                    ?.requestFocus()
                    ?: binding.detailEpisodeList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
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
        val queue = seriesEpisodes.filter { (it.season ?: 1) == season }
        if (queue.isEmpty()) return
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
        view.load(url) {
            size(
                (widthDp * density).toInt().coerceAtLeast(1),
                (heightDp * density).toInt().coerceAtLeast(1)
            )
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
                binding.root.setOnClickListener { showDetail(title) }
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
                    if (season == number) {
                        showSeasonEpisodes(focusSeasons = false)
                        return@setOnClickListener
                    }
                    season = number
                    showSeasonEpisodes(focusSeasons = false)
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
                bindArt(row.episodeArt, episode.logoUrl ?: shown?.logoUrl, 168, 94)
                row.episodePlay.setOnClickListener { playEpisode(episode) }
                row.root.setOnClickListener { playEpisode(episode) }
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
