package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.data.Titles
import com.olympus.iptv.databinding.ActivityMediaDetailBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.playlist.MediaDetailMetadata
import com.olympus.iptv.ui.cast.CastBridge
import com.olympus.iptv.ui.player.PlayerActivity
import com.olympus.iptv.ui.player.SeriesPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaDetailBinding
    private var playlistId: String = ""
    private var itemId: String = ""
    private var bucket: String = CatalogDb.SERIES
    private var rawName: String = ""
    private var posterUrl: String? = null
    private var serviceHint: String = ""
    private var preferredSeason: Int = 1

    private var channel: Channel? = null
    private var allEpisodes: List<Channel> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var selectedSeason: Int = 1
    private var detail: MediaDetailMetadata.MediaDetail? = null
    private var episodeMeta: Map<Int, MediaDetailMetadata.EpisodeInfo> = emptyMap()
    private var isFavorite = false
    private var isWatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        bucket = intent.getStringExtra(EXTRA_BUCKET) ?: CatalogDb.SERIES
        rawName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        posterUrl = intent.getStringExtra(EXTRA_LOGO)
        serviceHint = intent.getStringExtra(EXTRA_SERVICE).orEmpty()
        preferredSeason = intent.getIntExtra(EXTRA_SEASON, 1).coerceAtLeast(1)
        selectedSeason = preferredSeason

        if (playlistId.isBlank() || itemId.isBlank()) {
            finish()
            return
        }

        val isSeries = bucket == CatalogDb.SERIES
        binding.heroBannerTag.text = if (isSeries) "TV Series" else "Movie"
        binding.heroBannerTag.visibility = View.VISIBLE

        val cleanTitle = Titles.clean(rawName).ifBlank { rawName }
        binding.heroTitleText.text = cleanTitle
        binding.metaLine.text = serviceHint
        binding.heroPoster.load(posterUrl) {
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }
        binding.heroBackdrop.load(posterUrl)

        binding.episodeCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false).apply {
            initialPrefetchItemCount = 6
        }
        binding.episodeCarousel.setHasFixedSize(true)
        binding.episodeCarousel.itemAnimator = null
        binding.episodeCarousel.setItemViewCacheSize(6)
        binding.castCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false).apply {
            initialPrefetchItemCount = 6
        }
        binding.castCarousel.setHasFixedSize(true)
        binding.castCarousel.itemAnimator = null
        binding.castCarousel.setItemViewCacheSize(6)

        binding.btnDetailBack.setOnClickListener { finish() }
        binding.btnDetailCast.setOnClickListener { CastBridge.showChooser(this, channel) }
        binding.btnDetailSettings.setOnClickListener { finish() }
        binding.posterCard.setOnClickListener { playPrimary() }
        binding.btnHeroPlay.setOnClickListener { playPrimary() }
        binding.btnHeroTrailer.setOnClickListener { openTrailer() }
        binding.btnHeroFavorite.setOnClickListener { toggleFavorite() }
        binding.btnHeroWatched.setOnClickListener { toggleWatched() }
        binding.btnHeroMore.setOnClickListener { showMoreMenu() }
        binding.seasonDropdownBtn.setOnClickListener { pickSeason() }

        binding.btnHeroPlay.requestFocus()
        loadAll()
    }

    private fun loadAll() {
        val app = application as OlympusIptvApp
        val isSeries = bucket == CatalogDb.SERIES
        lifecycleScope.launch {
            val loadedChannel = withContext(Dispatchers.IO) {
                app.catalogDb.readChannel(playlistId, bucket, itemId)
                    ?: app.catalogDb.readChannel(playlistId, CatalogDb.VOD, itemId)
                    ?: app.catalogDb.readChannel(playlistId, CatalogDb.SERIES, itemId)
            } ?: Channel(
                id = itemId,
                name = rawName,
                streamUrl = "",
                logoUrl = posterUrl,
                kind = if (isSeries) ContentKind.VOD else ContentKind.VOD,
                seriesId = if (isSeries) itemId else null
            )
            channel = loadedChannel
            val favKey = Favorites.keyFor(loadedChannel, if (isSeries) Favorites.SERIES else Favorites.VOD)
            isFavorite = withContext(Dispatchers.IO) {
                favKey in app.playlistStore.favoriteIds(playlistId)
            }
            paintFavoriteIcon()

            val meta = withContext(Dispatchers.IO) {
                MediaDetailMetadata.loadDetail(rawName.ifBlank { loadedChannel.name }, isSeries)
            }
            if (isDestroyed) return@launch
            detail = meta
            if (meta != null) paintHeroMeta(meta)

            if (isSeries) {
                val saved = withContext(Dispatchers.IO) {
                    app.playlistStore.session.first()?.takeIf { it.id == playlistId }
                        ?: app.playlistStore.playlists.first().firstOrNull { it.id == playlistId }
                }
                if (saved != null) {
                    val eps = EpisodeSequence.load(filesDir, saved, itemId, app.appScope)
                    allEpisodes = EpisodeSequence.ordered(eps)
                    seasons = allEpisodes.mapNotNull { it.season }.distinct().sorted()
                    if (seasons.isNotEmpty()) {
                        if (selectedSeason !in seasons) selectedSeason = seasons.first()
                        bindSeason(selectedSeason)
                    }
                }
            }
        }
    }

    private fun paintHeroMeta(meta: MediaDetailMetadata.MediaDetail) {
        if (!meta.backdropUrl.isNullOrBlank()) {
            binding.heroBackdrop.load(meta.backdropUrl)
        }
        if (!meta.posterUrl.isNullOrBlank()) {
            binding.heroPoster.load(meta.posterUrl) {
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }
        }
        if (!meta.logoUrl.isNullOrBlank()) {
            binding.heroClearLogo.visibility = View.VISIBLE
            binding.heroTitleText.visibility = View.GONE
            binding.heroClearLogo.load(meta.logoUrl)
        } else {
            binding.heroClearLogo.visibility = View.GONE
            binding.heroTitleText.visibility = View.VISIBLE
            binding.heroTitleText.text = meta.title
        }

        if (!meta.rating.isNullOrBlank()) {
            binding.metaRating.visibility = View.VISIBLE
            binding.metaRating.text = "★ ${meta.rating}"
        }
        val parts = ArrayList<String>()
        meta.years?.let { parts.add(it) }
        (meta.network ?: serviceHint.takeIf { it.isNotBlank() })?.let { parts.add(it) }
        meta.seasonCount?.let { parts.add(if (it == 1) "1 Season" else "$it Seasons") }
        binding.metaLine.text = parts.joinToString("   ")

        if (!meta.contentRating.isNullOrBlank()) {
            binding.metaContentRating.visibility = View.VISIBLE
            binding.metaContentRating.text = meta.contentRating
        }
        if (meta.genres.isNotEmpty()) {
            binding.metaGenres.text = meta.genres.take(3).joinToString(", ")
        }
        if (meta.overview.isNotBlank()) {
            binding.heroOverview.text = meta.overview
        }

        if (meta.cast.isNotEmpty()) {
            binding.castSection.visibility = View.VISIBLE
            binding.castCarousel.adapter = CastAdapter(meta.cast)
        }
    }

    private suspend fun bindSeason(season: Int) {
        selectedSeason = season
        binding.episodesSection.visibility = View.VISIBLE
        binding.seasonDropdownBtn.text = if (seasons.size > 1) "Season $season  ⌄" else "Season $season"
        val seasonEps = allEpisodes.filter { (it.season ?: 1) == season }
        val app = application as OlympusIptvApp
        val watchedIds = withContext(Dispatchers.IO) {
            app.catalogDb.watchedEpisodes(playlistId, itemId)
        }
        val tmdbId = detail?.tmdbId ?: 0
        episodeMeta = if (tmdbId > 0) {
            withContext(Dispatchers.IO) { MediaDetailMetadata.loadSeasonEpisodes(tmdbId, season) }
        } else {
            emptyMap()
        }
        if (isDestroyed) return
        binding.episodeCarousel.adapter = EpisodeCarouselAdapter(
            episodes = seasonEps,
            metaByNumber = episodeMeta,
            watchedIds = watchedIds,
            fallbackBackdrop = detail?.backdropUrl ?: posterUrl,
            onPlayEpisode = { ep ->
                SeriesPlay.open(this, playlistId, allEpisodes, ep)
            }
        )
    }

    private fun pickSeason() {
        if (seasons.size <= 1) return
        val labels = seasons.map { "Season $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Season")
            .setItems(labels) { _, which ->
                val chosen = seasons.getOrNull(which) ?: return@setItems
                lifecycleScope.launch { bindSeason(chosen) }
            }
            .show()
    }

    private fun playPrimary() {
        val ch = channel ?: return
        if (bucket == CatalogDb.SERIES) {
            val target = allEpisodes.firstOrNull { (it.season ?: 1) == selectedSeason } ?: allEpisodes.firstOrNull()
            if (target != null) {
                SeriesPlay.open(this, playlistId, allEpisodes, target)
            } else {
                startActivity(EpisodesActivity.intent(this, playlistId, ch))
            }
            return
        }
        val app = application as OlympusIptvApp
        app.playbackQueue = listOf(ch)
        startActivity(
            PlayerActivity.intent(
                this,
                channel = ch,
                channelMeta = detail?.genres?.joinToString(", ").orEmpty(),
                channelNext = "",
                startIndex = 0,
                playlistId = playlistId
            )
        )
    }

    private fun openTrailer() {
        val key = detail?.trailerKey
        if (key.isNullOrBlank()) {
            Toast.makeText(this, R.string.trailer_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val ytApp = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key"))
        val ytWeb = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))
        runCatching { startActivity(ytApp) }.recoverCatching { startActivity(ytWeb) }
    }

    private fun toggleFavorite() {
        val ch = channel ?: return
        val app = application as OlympusIptvApp
        val key = Favorites.keyFor(ch, if (bucket == CatalogDb.SERIES) Favorites.SERIES else Favorites.VOD)
        isFavorite = !isFavorite
        paintFavoriteIcon()
        lifecycleScope.launch(Dispatchers.IO) {
            app.playlistStore.setFavorite(playlistId, key, isFavorite)
        }
        Toast.makeText(
            this,
            if (isFavorite) "Added to Favorites" else "Removed from Favorites",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun paintFavoriteIcon() {
        binding.btnHeroFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )
    }

    private fun toggleWatched() {
        val app = application as OlympusIptvApp
        isWatched = !isWatched
        binding.btnHeroWatched.setColorFilter(
            if (isWatched) 0xFF73B56E.toInt() else getColor(R.color.text_primary)
        )
        lifecycleScope.launch(Dispatchers.IO) {
            if (bucket == CatalogDb.SERIES) {
                val ids = allEpisodes.filter { (it.season ?: 1) == selectedSeason }.map { it.id }
                if (isWatched) app.catalogDb.markWatchedAll(playlistId, itemId, selectedSeason, ids)
                else app.catalogDb.clearWatched(playlistId, ids)
            } else {
                if (isWatched) app.catalogDb.markWatched(playlistId, itemId, null, null)
                else app.catalogDb.clearWatched(playlistId, listOf(itemId))
            }
        }
    }

    private fun showMoreMenu() {
        val ch = channel ?: return
        val item = WatchQueue.itemFor(ch, bucket == CatalogDb.SERIES)
        val options = arrayOf("Add to Watch Queue", "View All Episodes")
        AlertDialog.Builder(this)
            .setTitle(Titles.clean(ch.name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (item != null) WatchQueue.offer(this, playlistId, item)
                    1 -> if (bucket == CatalogDb.SERIES) startActivity(EpisodesActivity.intent(this, playlistId, ch))
                }
            }
            .show()
    }

    private class EpisodeCarouselAdapter(
        private val episodes: List<Channel>,
        private val metaByNumber: Map<Int, MediaDetailMetadata.EpisodeInfo>,
        private val watchedIds: Set<String>,
        private val fallbackBackdrop: String?,
        private val onPlayEpisode: (Channel) -> Unit
    ) : RecyclerView.Adapter<EpisodeCarouselAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val still: ImageView = view.findViewById(R.id.epStill)
            val watched: ImageView = view.findViewById(R.id.epWatchedBadge)
            val heading: TextView = view.findViewById(R.id.epHeading)
            val meta: TextView = view.findViewById(R.id.epMeta)
            val overview: TextView = view.findViewById(R.id.epOverview)
            val hoverOverlay: View? = view.findViewById(R.id.epHoverOverlay)
            val btnWatched: View? = view.findViewById(R.id.btnEpWatched)
            val btnFavorite: View? = view.findViewById(R.id.btnEpFavorite)
            val btnMore: View? = view.findViewById(R.id.btnEpMore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_emby_episode, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = episodes.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = episodes[position]
            val s = ep.season ?: 1
            val eNum = ep.number ?: (position + 1)
            val info = metaByNumber[eNum]
            val cleanTitle = info?.title?.takeIf { it.isNotBlank() } ?: Titles.clean(ep.name)
            holder.heading.text = "S$s:E$eNum - $cleanTitle"

            val metaParts = ArrayList<String>(2)
            info?.airDate?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
            info?.runtimeMinutes?.takeIf { it > 0 }?.let { metaParts.add("${it}m") }
            holder.meta.text = metaParts.joinToString("   ")
            holder.overview.text = info?.overview.orEmpty()
            holder.watched.visibility = if (ep.id in watchedIds) View.VISIBLE else View.GONE

            val img = info?.stillUrl ?: ep.logoUrl ?: fallbackBackdrop
            holder.still.load(img) {
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.hoverOverlay?.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }
            holder.itemView.setOnClickListener { onPlayEpisode(ep) }
        }
    }

    private class CastAdapter(
        private val cast: List<MediaDetailMetadata.CastMember>
    ) : RecyclerView.Adapter<CastAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView = view.findViewById(R.id.castPhoto)
            val name: TextView = view.findViewById(R.id.castName)
            val role: TextView = view.findViewById(R.id.castRole)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_emby_cast, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = cast.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val member = cast[position]
            holder.name.text = member.name
            holder.role.text = member.role
            holder.photo.load(member.photoUrl) {
                placeholder(R.drawable.ic_person_placeholder)
                error(R.drawable.ic_person_placeholder)
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_ITEM_ID = "item_id"
        private const val EXTRA_BUCKET = "bucket"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_SERVICE = "service"
        private const val EXTRA_SEASON = "season"

        fun intent(
            context: Context,
            playlistId: String,
            itemId: String,
            bucket: String,
            name: String,
            logoUrl: String? = null,
            service: String = "",
            season: Int = 1
        ): Intent = Intent(context, MediaDetailActivity::class.java)
            .putExtra(EXTRA_PLAYLIST_ID, playlistId)
            .putExtra(EXTRA_ITEM_ID, itemId)
            .putExtra(EXTRA_BUCKET, bucket)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_LOGO, logoUrl)
            .putExtra(EXTRA_SERVICE, service)
            .putExtra(EXTRA_SEASON, season)
    }
}
