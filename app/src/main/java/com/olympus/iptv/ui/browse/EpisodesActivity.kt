package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ShowNames
import com.olympus.iptv.databinding.ActivityEpisodesBinding
import com.olympus.iptv.databinding.ItemEpisodeBinding
import com.olympus.iptv.databinding.ItemSeasonBinding
import com.olympus.iptv.playlist.EpisodeCache
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.ui.player.SeriesPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpisodesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEpisodesBinding
    private val playlistId by lazy { intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty() }
    private val seriesId by lazy { intent.getStringExtra(EXTRA_SERIES_ID).orEmpty() }
    private val showName by lazy { intent.getStringExtra(EXTRA_NAME).orEmpty() }
    private val showLogo by lazy { intent.getStringExtra(EXTRA_LOGO) }
    private var episodes = emptyList<Channel>()
    private var watched = emptySet<String>()
    private var progressById = emptyMap<String, Int>()
    private var season = 1
    private val seasons = EpisodeSeasons()
    private val adapter = EpisodeAdapter()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpisodesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.olympus.iptv.ui.DeviceCompat.applySafeInsets(binding.root)
        if (playlistId.isBlank() || seriesId.isBlank()) {
            finish()
            return
        }
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.root,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
            binding.seasonList.isNestedScrollingEnabled = true
            binding.episodeList.isNestedScrollingEnabled = true
            binding.markSeason.isFocusableInTouchMode = false
        }
        binding.showTitle.text = showName
        binding.seasonList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.seasonList.itemAnimator = null
        binding.seasonList.adapter = seasons
        binding.episodeList.layoutManager = LinearLayoutManager(this)
        binding.episodeList.itemAnimator = null
        binding.episodeList.setHasFixedSize(true)
        binding.episodeList.adapter = adapter
        binding.episodeList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val last = layout.findLastVisibleItemPosition()
                if (last < 0) return
                val shown = episodes.filter { (it.season ?: 1) == season }
                val urls = com.olympus.iptv.ui.ImagePrefetcher.ahead(shown.map { it.logoUrl }, last + 1)
                if (urls.isEmpty()) return
                val px = (160 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                com.olympus.iptv.ui.ImagePrefetcher.warm(this@EpisodesActivity, urls, px)
            }
        })
        binding.markSeason.setOnClickListener { toggleSeason(season) }
        binding.playNextUnwatched.setOnClickListener {
            val shown = episodes.filter { (it.season ?: 1) == season }
            val next = shown.firstOrNull { it.id !in watched } ?: shown.firstOrNull() ?: return@setOnClickListener
            play(next)
        }
        binding.playNextUnwatched.nextFocusUpId = binding.seasonList.id
        bindArt(binding.showArt, showLogo, 56, 84)
        lifecycleScope.launch { loadEpisodes() }
    }

    private suspend fun loadEpisodes() {
        if (loading) return
        loading = true
        try {
            val app = application as OlympusIptvApp
            val saved = withContext(Dispatchers.IO) {
                runCatching { app.playlistStore.account(playlistId) }.getOrNull()
            }
            if (saved == null || isDestroyed) {
                if (!isDestroyed) finish()
                return
            }
            val cached = withContext(Dispatchers.IO) {
                runCatching { EpisodeCache.peek(filesDir, playlistId, seriesId) }.getOrNull()
            }
            watched = withContext(Dispatchers.IO) {
                runCatching { app.catalogDb.watchedEpisodes(playlistId, seriesId) }.getOrDefault(emptySet())
            }
            progressById = withContext(Dispatchers.IO) {
                runCatching {
                    app.playlistStore.recent(playlistId)
                        .mapNotNull { entry ->
                            val id = entry.channelId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val pos = entry.positionMs ?: return@mapNotNull null
                            if (pos <= 0L) return@mapNotNull null
                            val duration = entry.durationMs?.takeIf { it > 0L } ?: return@mapNotNull null
                            id to ((pos * 1000L) / duration).toInt().coerceIn(1, 999)
                        }
                        .toMap()
                }.getOrDefault(emptyMap())
            }
            if (isDestroyed) return
            val hadCache = !cached.isNullOrEmpty()
            if (hadCache) {
                presentEpisodes(cached.orEmpty(), focus = true)
            } else {
                binding.statusLine.visibility = View.VISIBLE
                binding.statusLine.text = getString(R.string.episodes_loading)
            }
            val fresh = withContext(Dispatchers.IO) {
                runCatching { EpisodeSequence.fetch(saved, seriesId) }.getOrDefault(emptyList())
            }
            if (isDestroyed) return
            if (fresh.isEmpty()) {
                if (!hadCache) {
                    binding.statusLine.visibility = View.VISIBLE
                    binding.statusLine.text = getString(R.string.episodes_none)
                    binding.seasonPane.visibility = View.GONE
                    binding.episodePane.visibility = View.GONE
                }
                return
            }
            withContext(Dispatchers.IO) {
                runCatching { EpisodeCache.store(filesDir, playlistId, seriesId, fresh) }
            }
            if (fresh == episodes) return
            presentEpisodes(fresh, focus = !hadCache)
        } catch (_: OutOfMemoryError) {
            runCatching { (application as OlympusIptvApp).catalogDb.releaseSearchExport() }
            if (!isDestroyed && episodes.isEmpty()) {
                binding.statusLine.visibility = View.VISIBLE
                binding.statusLine.text = getString(R.string.episodes_none)
            }
        } finally {
            loading = false
        }
    }

    private fun presentEpisodes(loaded: List<Channel>, focus: Boolean) {
        if (isDestroyed || loaded.isEmpty()) return
        episodes = loaded
        binding.statusLine.visibility = View.GONE
        binding.seasonPane.visibility = View.VISIBLE
        binding.episodePane.visibility = View.VISIBLE
        val selectedStillThere = loaded.any { (it.season ?: 1) == season }
        if (focus || !selectedStillThere) {
            val next = loaded.firstOrNull { it.id !in watched }
            season = next?.season ?: loaded.firstOrNull()?.season ?: 1
        }
        showBreakdown(focusEpisodes = focus)
    }

    override fun onResume() {
        super.onResume()
        if (episodes.isEmpty() || playlistId.isBlank() || seriesId.isBlank()) return
        lifecycleScope.launch {
            watched = withContext(Dispatchers.IO) {
                runCatching {
                    (application as OlympusIptvApp).catalogDb.watchedEpisodes(playlistId, seriesId)
                }.getOrDefault(emptySet())
            }
            if (!isDestroyed) showBreakdown(focusEpisodes = false)
        }
    }

    private fun seasonNumbers(): List<Int> = episodes.map { it.season ?: 1 }.distinct().sorted()

    private fun seasonDone(number: Int): Boolean {
        val inSeason = episodes.filter { (it.season ?: 1) == number }
        return inSeason.isNotEmpty() && inSeason.all { it.id in watched }
    }

    private fun showBreakdown(focusEpisodes: Boolean) {
        if (isDestroyed) return
        val numbers = seasonNumbers()
        seasons.submit(numbers)
        val shown = episodes.filter { (it.season ?: 1) == season }
        val next = shown.indexOfFirst { it.id !in watched }
        val complete = shown.isNotEmpty() && next < 0
        binding.seasonHeading.text = getString(R.string.season_number, season)
        binding.markSeason.text = getString(if (complete) R.string.season_clear else R.string.season_mark)
        binding.markSeason.isEnabled = shown.isNotEmpty()
        adapter.submit(shown)
        val seasonIndex = numbers.indexOf(season).coerceAtLeast(0)
        if (numbers.isNotEmpty()) binding.seasonList.scrollToPosition(seasonIndex)
        if (focusEpisodes && shown.isNotEmpty()) {
            val index = if (next >= 0) next else 0
            binding.episodeList.scrollToPosition(index)
            binding.episodeList.post {
                if (isDestroyed) return@post
                binding.episodeList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        } else if (numbers.isNotEmpty()) {
            binding.seasonList.post {
                if (isDestroyed) return@post
                binding.seasonList.findViewHolderForAdapterPosition(seasonIndex)?.itemView?.requestFocus()
            }
        }
    }

    private fun toggleSeason(number: Int) {
        val ids = episodes.filter { (it.season ?: 1) == number }.map { it.id }
        if (ids.isEmpty() || playlistId.isBlank()) return
        season = number
        val done = ids.all { it in watched }
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            withContext(Dispatchers.IO) {
                runCatching {
                    if (done) app.catalogDb.clearWatched(playlistId, ids)
                    else app.catalogDb.markWatchedAll(playlistId, seriesId, number, ids)
                }
            }
            if (isDestroyed) return@launch
            watched = withContext(Dispatchers.IO) {
                runCatching { app.catalogDb.watchedEpisodes(playlistId, seriesId) }.getOrDefault(emptySet())
            }
            if (!isDestroyed) showBreakdown(focusEpisodes = false)
        }
    }

    private fun offerEpisode(episode: Channel, number: Int) {
        val heading = ShowNames.episodeHeading(showName, episode.season ?: season, number, episode.name)
        val marked = episode.id in watched
        val label = getString(if (marked) R.string.episode_clear else R.string.episode_mark)
        AlertDialog.Builder(this)
            .setTitle(heading)
            .setItems(arrayOf(label)) { _, _ -> toggleEpisode(episode) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleEpisode(episode: Channel) {
        if (playlistId.isBlank() || episode.id.isBlank()) return
        val marked = episode.id in watched
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            withContext(Dispatchers.IO) {
                runCatching {
                    if (marked) app.catalogDb.clearWatched(playlistId, listOf(episode.id))
                    else app.catalogDb.markWatched(playlistId, episode.id, seriesId, episode.season ?: season)
                }
            }
            if (isDestroyed) return@launch
            watched = withContext(Dispatchers.IO) {
                runCatching { app.catalogDb.watchedEpisodes(playlistId, seriesId) }.getOrDefault(emptySet())
            }
            if (isDestroyed) return@launch
            showBreakdown(focusEpisodes = false)
            val index = adapter.indexOf(episode.id)
            if (index >= 0) {
                binding.episodeList.post {
                    if (isDestroyed) return@post
                    binding.episodeList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun play(episode: Channel) {
        if (episode.id.isBlank()) return
        val queue = episodes.filter { (it.season ?: 1) == season }
        if (queue.isEmpty()) return
        SeriesPlay.open(this, playlistId, queue, episode)
    }

    private fun bindArt(view: ImageView, url: String?, widthDp: Int, heightDp: Int) {
        val picture = url?.takeIf { it.isNotBlank() } ?: showLogo
        if (picture.isNullOrBlank()) {
            view.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }
        val density = view.resources.displayMetrics.density
        view.load(picture) {
            size((widthDp * density).toInt().coerceAtLeast(1), (heightDp * density).toInt().coerceAtLeast(1))
            scale(Scale.FIT)
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
            if (view === binding.showArt) {
                listener(onSuccess = { _, result ->
                    val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@listener
                    com.olympus.iptv.ui.BackdropGlow.apply(bitmap, binding.root)
                })
            }
        }
    }

    private inner class EpisodeSeasons : RecyclerView.Adapter<EpisodeSeasons.Holder>() {
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
                val done = seasonDone(number)
                val active = number == season
                val unwatched = episodes.count {
                    (it.season ?: 1) == number && it.id !in watched
                }
                row.seasonName.text = getString(R.string.season_number, number)
                row.seasonCheck.visibility = if (done) View.VISIBLE else View.GONE
                if (!done && unwatched > 0) {
                    row.seasonUnwatched.text = getString(R.string.season_unwatched, unwatched)
                    row.seasonUnwatched.visibility = View.VISIBLE
                } else {
                    row.seasonUnwatched.visibility = View.GONE
                }
                row.root.isSelected = active
                row.root.setOnClickListener {
                    if (season == number) {
                        showBreakdown(focusEpisodes = true)
                        return@setOnClickListener
                    }
                    season = number
                    showBreakdown(focusEpisodes = true)
                }
                row.root.setOnLongClickListener {
                    toggleSeason(number)
                    true
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

        fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            items.getOrNull(position)?.let { holder.bind(it, position) }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val row: ItemEpisodeBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(episode: Channel, position: Int) {
                val number = episode.number ?: (position + 1)
                val marked = episode.id in watched
                row.episodeTitle.text = ShowNames.episodeHeading(
                    showName,
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
                row.episodeCheck.visibility = if (marked) View.VISIBLE else View.GONE
                val progress = progressById[episode.id] ?: 0
                if (!marked && progress > 0) {
                    row.episodeProgress.progress = progress
                    row.episodeProgress.visibility = View.VISIBLE
                } else {
                    row.episodeProgress.progress = 0
                    row.episodeProgress.visibility = View.GONE
                }
                bindArt(row.episodeArt, episode.logoUrl, 168, 94)
                row.root.setOnClickListener { play(episode) }
                row.root.setOnLongClickListener {
                    offerEpisode(episode, number)
                    true
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_SERIES_ID = "series_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LOGO = "logo"

        fun intent(context: Context, playlistId: String, show: Channel): Intent {
            val seriesId = show.seriesId?.takeIf { it.isNotBlank() } ?: show.id
            return Intent(context, EpisodesActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                .putExtra(EXTRA_SERIES_ID, seriesId)
                .putExtra(EXTRA_NAME, show.name)
                .putExtra(EXTRA_LOGO, show.logoUrl)
        }
    }
}
