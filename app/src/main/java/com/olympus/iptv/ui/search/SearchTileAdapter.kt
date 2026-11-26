package com.olympus.iptv.ui.search

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.olympus.iptv.R
import com.olympus.iptv.ui.channelLogo
import com.olympus.iptv.ui.prefetchLogos
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Titles
import com.olympus.iptv.databinding.ItemSearchHeaderBinding
import com.olympus.iptv.databinding.ItemSearchTileBinding

private val SEARCH_YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

data class SearchEntry(
    val header: String? = null,
    val channel: Channel? = null,
    val indexInSection: Int = 0,
    val section: List<Channel> = emptyList(),
    val sectionKey: String = ""
)

class SearchTileAdapter(
    private val isFavorite: (Channel, String) -> Boolean,
    private val onFavorite: (Channel, String) -> Unit,
    private val onClick: (Channel, Int, List<Channel>) -> Unit,
    private val onLongClick: (Channel, String) -> Unit = { _, _ -> },
    private val watchProgress: (Channel) -> Int? = { null }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SearchEntry>()
    private var scrolling = false

    fun setScrolling(active: Boolean) {
        scrolling = active
    }

    fun refreshLogos(first: Int, last: Int) {
        scrolling = false
        if (first < 0 || last < first || first >= items.size) return
        val end = last.coerceAtMost(items.lastIndex)
        notifyItemRangeChanged(first, end - first + 1, PAYLOAD_LOGO)
    }

    fun prefetch(context: Context, start: Int, count: Int) {
        if (start < 0 || count <= 0 || start >= items.size) return
        val urls = ArrayList<String>(count)
        val end = (start + count).coerceAtMost(items.size)
        for (index in start until end) {
            items[index].channel?.logoUrl?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        val px = (120 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        prefetchLogos(context, urls, px)
    }

    fun submit(list: List<SearchEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position)?.header != null

    override fun getItemViewType(position: Int): Int =
        if (isHeader(position)) VIEW_HEADER else VIEW_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_HEADER) {
            HeaderHolder(ItemSearchHeaderBinding.inflate(inflater, parent, false))
        } else {
            TileHolder(ItemSearchTileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = items[position]
        when (holder) {
            is HeaderHolder -> holder.bind(entry.header.orEmpty())
            is TileHolder -> holder.bind(entry)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_LOGO) && holder is TileHolder) {
            holder.bindLogo(items[position].channel)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = items.size

    private class HeaderHolder(private val binding: ItemSearchHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.title.text = title
        }
    }

    private inner class TileHolder(private val binding: ItemSearchTileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SearchEntry) {
            val item = entry.channel ?: return
            binding.name.text = if (item.kind == ContentKind.VOD || !item.seriesId.isNullOrBlank()) {
                Titles.clean(item.name)
            } else {
                item.name
            }
            binding.group.text = item.groupTitle.orEmpty()
            paintTileChrome(item)
            val saved = isFavorite(item, entry.sectionKey)
            binding.favorite.setImageResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_outline)
            binding.favorite.imageTintList = android.content.res.ColorStateList.valueOf(
                binding.root.context.getColor(if (saved) R.color.accent else R.color.text_secondary)
            )
            bindLogo(item)
            binding.playTarget.setOnClickListener {
                val channel = entry.channel ?: return@setOnClickListener
                onClick(channel, entry.indexInSection, entry.section)
            }
            binding.playTarget.setOnLongClickListener {
                val channel = entry.channel ?: return@setOnLongClickListener false
                onLongClick(channel, entry.sectionKey)
                true
            }
            binding.favorite.setOnClickListener {
                val channel = entry.channel ?: return@setOnClickListener
                onFavorite(channel, entry.sectionKey)
            }
        }

        private fun paintTileChrome(item: Channel) {
            val vod = item.kind == ContentKind.VOD || !item.seriesId.isNullOrBlank()
            val rating = if (!vod) null else {
                com.olympus.iptv.playlist.TitleRatings.peek(item.name, !item.seriesId.isNullOrBlank())
                    ?.ratings?.firstOrNull()?.scoreLabel
            }
            if (rating.isNullOrBlank()) {
                binding.ratingBadge.visibility = android.view.View.GONE
            } else {
                val number = rating.substringBefore('/').substringBefore('%').trim()
                binding.ratingBadge.text = "★ $number"
                binding.ratingBadge.visibility = android.view.View.VISIBLE
            }
            val year = SEARCH_YEAR_REGEX.find(item.name)?.value
            if (year.isNullOrBlank()) {
                binding.tileMeta.visibility = android.view.View.GONE
            } else {
                binding.tileMeta.text = year
                binding.tileMeta.visibility = android.view.View.VISIBLE
            }
            val progress = watchProgress(item)
            if (progress == null || progress <= 0) {
                binding.watchProgress.visibility = android.view.View.GONE
            } else {
                binding.watchProgress.progress = progress.coerceIn(1, 1000)
                binding.watchProgress.visibility = android.view.View.VISIBLE
            }
        }

        fun bindLogo(item: Channel?) {
            if (item == null || scrolling || item.logoUrl.isNullOrBlank()) {
                binding.logo.setImageResource(R.drawable.ic_channel_placeholder)
                return
            }
            binding.logo.load(item.logoUrl) {
                channelLogo((120 * binding.logo.resources.displayMetrics.density).toInt().coerceAtLeast(1))
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }
        }
    }

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_TILE = 1
        private const val PAYLOAD_LOGO = "logo"
    }
}
