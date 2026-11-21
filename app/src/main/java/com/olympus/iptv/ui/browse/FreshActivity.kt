package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.data.Titles
import com.olympus.iptv.databinding.ActivityFreshBinding
import com.olympus.iptv.databinding.ItemFreshBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.FreshPlayable
import com.olympus.iptv.playlist.Watchmode
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.FocusMotion
import com.olympus.iptv.ui.Haptics
import com.olympus.iptv.ui.InsetsPad
import com.olympus.iptv.ui.channelLogo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays recently added ("Just Added / New") programs in an Emby-style poster grid:
 * - Clean full-bleed poster art (no "TV Series" top ribbon)
 * - Top-right green pill badge (episode/unwatched count or NEW)
 * - Focus/hover Play circle + quick Watch / Favorite / More actions
 * - Centered bold title + centered year/network subtitle below
 * - Opens [MediaDetailActivity] on selection.
 */
class FreshActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFreshBinding
    private var playlistId = ""
    private var cards: List<CatalogDb.FreshTitle> = emptyList()
    private val favoriteKeys = HashSet<String>()
    private lateinit var adapter: FreshAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreshBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val touchPrimary = DeviceForm.isTouchPrimary(this)
        if (touchPrimary) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            InsetsPad.apply(binding.root, top = true, bottom = true, left = true, right = true)
            binding.btnBack.isFocusableInTouchMode = false
        }
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        val span = embyGridColumns()
        adapter = FreshAdapter(
            touchPrimary = touchPrimary,
            isFavorite = { card -> cardKey(card) in favoriteKeys },
            onClick = { openEmbyDetail(it) },
            onToggleFavorite = { toggleFavorite(it) },
            onToggleWatched = { markWatched(it) },
            onMore = { showCardMenu(it) }
        )
        binding.freshList.layoutManager = GridLayoutManager(this, span)
        binding.freshList.adapter = adapter
        load()
    }

    private fun embyGridColumns(): Int {
        val width = resources.configuration.screenWidthDp
        return when {
            DeviceForm.isTelevision(this) || width >= 1000 -> 6
            width >= 600 -> 4
            else -> 2
        }
    }

    private fun cardKey(card: CatalogDb.FreshTitle): String {
        val prefix = if (card.bucket == CatalogDb.SERIES) Favorites.SERIES else Favorites.VOD
        return "$prefix:${card.id}"
    }

    private fun load() {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val favs = withContext(Dispatchers.IO) { app.playlistStore.favoriteIds(playlistId) }
            favoriteKeys.clear()
            favoriteKeys.addAll(favs)
            val cached = app.freshCards(playlistId)
            if (!cached.isNullOrEmpty()) {
                cards = cached
                adapter.submit(cards)
                binding.freshStatus.visibility = View.GONE
                return@launch
            }
            binding.freshStatus.visibility = View.VISIBLE
            binding.freshStatus.text = getString(R.string.fresh_loading)
            val loaded = withContext(Dispatchers.IO) {
                val file = File(filesDir, "watchmode-week.json")
                val premieres = if (Watchmode.needsRefresh(file)) Watchmode.refresh(file) else Watchmode.load(file)
                val catalog = app.catalogDb.titlesForPremieres(playlistId, Watchmode.lookupPrefixes(premieres))
                val matched = Watchmode.match(premieres, catalog)
                val account = app.playlistStore.account(playlistId)
                if (account == null) emptyList()
                else FreshPlayable.filter(filesDir, account, app.catalogDb, matched)
            }
            if (isDestroyed) return@launch
            binding.freshStatus.visibility = View.GONE
            cards = loaded
            if (cards.isEmpty()) {
                binding.freshStatus.visibility = View.VISIBLE
                binding.freshStatus.text = getString(R.string.fresh_empty)
            } else {
                adapter.submit(cards)
            }
        }
    }

    private fun openEmbyDetail(card: CatalogDb.FreshTitle) {
        startActivity(
            MediaDetailActivity.intent(
                context = this,
                playlistId = playlistId,
                itemId = card.id,
                bucket = card.bucket,
                name = card.name,
                logoUrl = card.logoUrl,
                service = card.service,
                season = card.season ?: 1
            )
        )
    }

    private fun toggleFavorite(card: CatalogDb.FreshTitle) {
        val app = application as OlympusIptvApp
        val key = cardKey(card)
        val adding = key !in favoriteKeys
        if (adding) favoriteKeys.add(key) else favoriteKeys.remove(key)
        adapter.notifyDataSetChanged()
        lifecycleScope.launch(Dispatchers.IO) {
            app.playlistStore.setFavorite(playlistId, key, adding)
        }
        Toast.makeText(
            this,
            if (adding) "Added to Favorites" else "Removed from Favorites",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun markWatched(card: CatalogDb.FreshTitle) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch(Dispatchers.IO) {
            app.catalogDb.markWatched(playlistId, card.id, if (card.bucket == CatalogDb.SERIES) card.id else null, card.season)
        }
        Toast.makeText(this, "Marked watched", Toast.LENGTH_SHORT).show()
    }

    private fun showCardMenu(card: CatalogDb.FreshTitle) {
        val ch = Channel(
            id = card.id,
            name = card.name,
            streamUrl = "",
            logoUrl = card.logoUrl,
            kind = ContentKind.VOD,
            seriesId = if (card.bucket == CatalogDb.SERIES) card.id else null
        )
        val options = arrayOf("Open Details", "Add to Watch Queue")
        AlertDialog.Builder(this)
            .setTitle(Titles.clean(card.name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openEmbyDetail(card)
                    1 -> WatchQueue.itemFor(ch, card.bucket == CatalogDb.SERIES)?.let {
                        WatchQueue.offer(this, playlistId, it)
                    }
                }
            }
            .show()
    }

    private class FreshAdapter(
        private val touchPrimary: Boolean,
        private val isFavorite: (CatalogDb.FreshTitle) -> Boolean,
        private val onClick: (CatalogDb.FreshTitle) -> Unit,
        private val onToggleFavorite: (CatalogDb.FreshTitle) -> Unit,
        private val onToggleWatched: (CatalogDb.FreshTitle) -> Unit,
        private val onMore: (CatalogDb.FreshTitle) -> Unit
    ) : RecyclerView.Adapter<FreshAdapter.Holder>() {
        private val items = ArrayList<CatalogDb.FreshTitle>()

        fun submit(next: List<CatalogDb.FreshTitle>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFreshBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val b: ItemFreshBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(card: CatalogDb.FreshTitle) {
                b.freshTitle.text = Titles.clean(card.name).ifBlank { card.name }
                val sub = listOf(card.detail, card.service).firstOrNull { it.isNotBlank() }.orEmpty()
                b.freshService.text = sub
                b.freshService.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

                // Top-right green pill badge (episode/season count or NEW)
                val badgeNum = Regex("\\d+").find(card.detail)?.value
                when {
                    !badgeNum.isNullOrBlank() -> {
                        b.freshCountBadge.visibility = View.VISIBLE
                        b.freshCountBadge.text = badgeNum
                    }
                    card.bucket == CatalogDb.SERIES -> {
                        b.freshCountBadge.visibility = View.VISIBLE
                        b.freshCountBadge.text = "NEW"
                    }
                    else -> b.freshCountBadge.visibility = View.GONE
                }

                b.btnCardFavorite.setImageResource(
                    if (isFavorite(card)) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                )
                b.btnCardFavorite.setOnClickListener { onToggleFavorite(card) }
                b.btnCardWatched.setOnClickListener { onToggleWatched(card) }
                b.btnCardMore.setOnClickListener { onMore(card) }

                val px = (240 * b.root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                b.freshPoster.load(card.logoUrl) {
                    channelLogo(px)
                    placeholder(R.drawable.ic_channel_placeholder)
                    error(R.drawable.ic_channel_placeholder)
                }
                b.root.setOnClickListener {
                    if (touchPrimary) Haptics.tick(b.root)
                    onClick(card)
                }
                b.root.setOnLongClickListener {
                    onMore(card)
                    true
                }
                b.root.setOnFocusChangeListener { _, hasFocus ->
                    b.freshHoverOverlay.visibility = if (hasFocus) View.VISIBLE else View.GONE
                    if (!touchPrimary) FocusMotion.animate(b.root, hasFocus)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, FreshActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
