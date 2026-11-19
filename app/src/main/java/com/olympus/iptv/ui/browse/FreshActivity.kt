package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.databinding.ActivityFreshBinding
import com.olympus.iptv.databinding.ItemFreshBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.Watchmode
import com.olympus.iptv.playlist.WatchmodeException
import com.olympus.iptv.playlist.XtreamClient
import com.olympus.iptv.ui.player.PlayerActivity
import com.olympus.iptv.ui.prefetchLogos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FreshActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFreshBinding
    private val playlistId by lazy { intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty() }
    private val rows = ArrayList<CatalogDb.FreshTitle>()
    private val adapter = FreshAdapter()
    private var shown: CatalogDb.FreshTitle? = null
    private lateinit var cacheFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreshBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cacheFile = File(filesDir, "watchmode-week.json")
        binding.freshList.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.freshList.adapter = adapter
        binding.freshList.itemAnimator = null
        binding.freshList.setItemViewCacheSize(COLUMNS * 2)
        (binding.freshList.layoutManager as GridLayoutManager).initialPrefetchItemCount = COLUMNS
        binding.freshList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val grid = recyclerView.layoutManager as? GridLayoutManager ?: return
                prefetchFrom(grid.findLastVisibleItemPosition() + 1)
            }
        })
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
        val ready = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
        if (!ready.isNullOrEmpty()) showCards(ready)
        app.warmFresh(playlistId)
        app.freshJob?.join()
        if (isFinishing) return
        val latest = withContext(Dispatchers.IO) { app.freshCards(playlistId) }
        when {
            !latest.isNullOrEmpty() -> showCards(latest)
            rows.isNotEmpty() -> return
            app.freshFailure == WatchmodeException.Kind.MISSING_KEY -> showStatus(R.string.fresh_need_key)
            app.freshFailure == WatchmodeException.Kind.REJECTED -> showStatus(R.string.fresh_rejected)
            app.freshFailure != null -> showStatus(R.string.fresh_failed)
            else -> showStatus(R.string.fresh_empty)
        }
    }

    private fun showCards(found: List<CatalogDb.FreshTitle>) {
        val same = rows.size == found.size && rows.indices.all { rows[it] == found[it] }
        if (!same) {
            rows.clear()
            rows.addAll(found)
            adapter.notifyDataSetChanged()
        }
        binding.freshStatus.visibility = View.GONE
        binding.freshList.post {
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
        val px = (160 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        prefetchLogos(this, urls, px)
    }

    private fun showStatus(message: Int) {
        binding.freshStatus.visibility = View.VISIBLE
        binding.freshStatus.text = getString(message)
    }

    private fun showDetail(title: CatalogDb.FreshTitle) {
        shown = title
        binding.freshDetail.visibility = View.VISIBLE
        binding.detailService.text = if (title.day.isBlank()) {
            title.service
        } else {
            getString(R.string.fresh_when, title.service, title.day)
        }
        binding.detailName.text = title.name
        binding.detailPlay.text = getString(
            if (title.bucket == CatalogDb.SERIES) R.string.fresh_episodes else R.string.fresh_play
        )
        binding.detailPlay.setOnClickListener { open(title) }
        bindArt(binding.detailLogo, title.logoUrl, 220, 330)
        val waiting = title.detail.startsWith("Premieres ") || title.detail.matches(SEASON_ONLY)
        binding.detailBody.text = if (waiting) getString(R.string.fresh_detail_loading) else title.detail
        binding.detailPlay.requestFocus()
        if (waiting) loadDescription(title)
    }

    private fun hideDetail() {
        shown = null
        binding.freshDetail.visibility = View.GONE
        binding.freshList.requestFocus()
    }

    private fun loadDescription(title: CatalogDb.FreshTitle) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val plot = withContext(Dispatchers.IO) {
                Watchmode.synopsis(cacheFile, title.watchmodeId)
                    ?: app.playlistStore.account(playlistId)?.let { saved ->
                        runCatching {
                            XtreamClient().titleLine(saved, title.bucket == CatalogDb.SERIES, title.id)
                        }.getOrNull()
                    }
            }
            if (plot.isNullOrBlank() || isFinishing) {
                if (shown?.id == title.id && shown?.service == title.service) {
                    binding.detailBody.text = title.detail
                }
                return@launch
            }
            val season = title.detail.takeIf { it.startsWith("Season ") }?.substringBefore('.')?.trim()
            val body = if (season.isNullOrBlank() || plot.startsWith(season)) plot else "$season. $plot"
            val index = rows.indexOfFirst { it.id == title.id && it.bucket == title.bucket && it.service == title.service }
            if (index >= 0) rows[index] = rows[index].copy(detail = body)
            if (shown?.id == title.id && shown?.service == title.service) binding.detailBody.text = body
        }
    }

    private fun open(title: CatalogDb.FreshTitle) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val channel = withContext(Dispatchers.IO) {
                app.catalogDb.readChannel(playlistId, title.bucket, title.id)
            } ?: return@launch
            if (title.bucket == CatalogDb.SERIES || !channel.seriesId.isNullOrBlank()) {
                startActivity(EpisodesActivity.intent(this@FreshActivity, playlistId, channel))
            } else {
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
            }
        }
    }

    private fun bindArt(view: android.widget.ImageView, url: String?, widthDp: Int, heightDp: Int) {
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
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemFreshBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val binding: ItemFreshBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(title: CatalogDb.FreshTitle) {
                binding.freshName.text = title.name
                binding.freshGroup.text = if (title.day.isBlank()) title.service else getString(R.string.fresh_when, title.service, title.day)
                bindArt(binding.freshLogo, title.logoUrl, 160, 220)
                binding.root.setOnClickListener { showDetail(title) }
                binding.root.setOnLongClickListener {
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

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val COLUMNS = 4
        private val SEASON_ONLY = Regex("Season \\d+\\.")

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, FreshActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
