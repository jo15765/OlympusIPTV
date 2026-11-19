package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
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
    private var season = 1
    private var showingEpisodes = false
    private val seasons = EpisodeSeasons()
    private val adapter = EpisodeAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpisodesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.showTitle.text = showName
        binding.seasonList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.seasonList.adapter = seasons
        binding.episodeList.layoutManager = LinearLayoutManager(this)
        binding.episodeList.adapter = adapter
        binding.markSeason.setOnClickListener { toggleSeason(season) }
        bindArt(binding.showArt, showLogo, 72, 108)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingEpisodes) {
                    showSeasons(focus = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            val saved = withContext(Dispatchers.IO) { app.playlistStore.account(playlistId) }
            if (saved == null) {
                finish()
                return@launch
            }
            val cached = withContext(Dispatchers.IO) {
                EpisodeCache.peek(filesDir, playlistId, seriesId)
            }
            watched = withContext(Dispatchers.IO) { app.catalogDb.watchedEpisodes(playlistId, seriesId) }
            if (isFinishing) return@launch
            val hadCache = !cached.isNullOrEmpty()
            if (hadCache) {
                presentEpisodes(cached, focus = true)
            } else {
                binding.statusLine.visibility = View.VISIBLE
                binding.statusLine.text = getString(R.string.episodes_loading)
            }
            val fresh = EpisodeSequence.fetch(saved, seriesId)
            if (isFinishing) return@launch
            if (fresh.isEmpty()) {
                if (!hadCache) {
                    binding.statusLine.visibility = View.VISIBLE
                    binding.statusLine.text = getString(R.string.episodes_none)
                    binding.seasonPane.visibility = View.GONE
                }
                return@launch
            }
            withContext(Dispatchers.IO) { EpisodeCache.store(filesDir, playlistId, seriesId, fresh) }
            if (fresh == episodes) return@launch
            presentEpisodes(fresh, focus = !hadCache)
        }
    }

    private fun presentEpisodes(loaded: List<Channel>, focus: Boolean) {
        episodes = loaded
        binding.statusLine.visibility = View.GONE
        binding.seasonPane.visibility = View.VISIBLE
        val selectedStillThere = loaded.any { (it.season ?: 1) == season }
        if (focus || !selectedStillThere) {
            val next = loaded.firstOrNull { it.id !in watched }
            season = next?.season ?: loaded.firstOrNull()?.season ?: 1
        }
        if (showingEpisodes) showSeason(focusNext = false) else showSeasons(focus = focus)
    }

    override fun onResume() {
        super.onResume()
        if (episodes.isEmpty() || playlistId.isBlank()) return
        lifecycleScope.launch {
            watched = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).catalogDb.watchedEpisodes(playlistId, seriesId)
            }
            if (showingEpisodes) showSeason(focusNext = false) else showSeasons(focus = false)
        }
    }

    private fun seasonNumbers(): List<Int> = episodes.map { it.season ?: 1 }.distinct().sorted()

    private fun seasonDone(number: Int): Boolean {
        val inSeason = episodes.filter { (it.season ?: 1) == number }
        return inSeason.isNotEmpty() && inSeason.all { it.id in watched }
    }

    private fun showSeasons(focus: Boolean) {
        showingEpisodes = false
        binding.episodePane.visibility = View.GONE
        binding.seasonPane.visibility = View.VISIBLE
        val numbers = seasonNumbers()
        seasons.submit(numbers)
        if (!focus || numbers.isEmpty()) return
        val index = numbers.indexOf(season).coerceAtLeast(0)
        binding.seasonList.scrollToPosition(index)
        binding.seasonList.post {
            binding.seasonList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }

    private fun showSeason(focusNext: Boolean) {
        showingEpisodes = true
        binding.seasonPane.visibility = View.GONE
        binding.episodePane.visibility = View.VISIBLE
        val shown = episodes.filter { (it.season ?: 1) == season }
        val next = shown.indexOfFirst { it.id !in watched }
        val complete = shown.isNotEmpty() && next < 0
        binding.seasonHeading.text = getString(R.string.season_number, season)
        binding.markSeason.text = getString(if (complete) R.string.season_clear else R.string.season_mark)
        binding.markSeason.isEnabled = shown.isNotEmpty()
        adapter.submit(shown)
        if (focusNext && shown.isNotEmpty()) {
            val index = if (next >= 0) next else 0
            binding.episodeList.scrollToPosition(index)
            binding.episodeList.post {
                binding.episodeList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
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
                if (done) app.catalogDb.clearWatched(playlistId, ids)
                else app.catalogDb.markWatchedAll(playlistId, seriesId, number, ids)
            }
            if (isFinishing) return@launch
            watched = withContext(Dispatchers.IO) { app.catalogDb.watchedEpisodes(playlistId, seriesId) }
            if (showingEpisodes) showSeason(focusNext = false) else showSeasons(focus = false)
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
        if (playlistId.isBlank()) return
        val marked = episode.id in watched
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            withContext(Dispatchers.IO) {
                if (marked) app.catalogDb.clearWatched(playlistId, listOf(episode.id))
                else app.catalogDb.markWatched(playlistId, episode.id, seriesId, episode.season ?: season)
            }
            if (isFinishing) return@launch
            watched = withContext(Dispatchers.IO) { app.catalogDb.watchedEpisodes(playlistId, seriesId) }
            showSeason(focusNext = false)
            val index = adapter.indexOf(episode.id)
            if (index >= 0) {
                binding.episodeList.post {
                    binding.episodeList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun play(episode: Channel) {
        val queue = episodes.filter { (it.season ?: 1) == season }
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

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(numbers[position])

        override fun getItemCount(): Int = numbers.size

        inner class Holder(private val row: ItemSeasonBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(number: Int) {
                val done = seasonDone(number)
                row.seasonName.text = getString(R.string.season_number, number)
                row.seasonCheck.visibility = if (done) View.VISIBLE else View.GONE
                bindArt(row.seasonArt, showLogo, 116, 162)
                row.root.setOnClickListener {
                    season = number
                    showSeason(focusNext = true)
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

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position)

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
