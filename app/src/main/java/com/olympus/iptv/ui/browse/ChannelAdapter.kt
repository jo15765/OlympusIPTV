package com.olympus.iptv.ui.browse

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import coil.dispose
import coil.load
import coil.transform.RoundedCornersTransformation
import com.olympus.iptv.R
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.FocusMotion
import com.olympus.iptv.ui.Haptics
import com.olympus.iptv.ui.channelLogo
import com.olympus.iptv.ui.prefetchLogos
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.databinding.ItemChannelBinding
import com.olympus.iptv.databinding.ItemPosterBinding

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Boolean = { false },
    private val onFocused: (Channel) -> Unit,
    private val onMove: (Channel, Int) -> Unit,
    private val isFavorite: (Channel) -> Boolean,
    private val onFavorite: (Channel) -> Unit,
    private val onRecord: (Channel) -> Unit,
    private val epgProvider: (Channel) -> EpgNowNext?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Channel>()
    private var accentTint: android.content.res.ColorStateList? = null
    private var mutedTint: android.content.res.ColorStateList? = null

    init {
        setHasStableIds(true)
    }

    private var groupLabel: String? = null
    private var sequentialNumbers = false
    private var scrolling = false
    var continueLabel: (Channel) -> String? = { null }
    var playingId: String? = null
        private set
    var focusedId: String? = null
        private set
    /** 0..1000 watch progress for poster gold bar; null hides. */
    var continueProgress: (Channel) -> Int? = { null }
    var posterRating: (Channel) -> String? = { null }
    var posterMeta: (Channel) -> String? = { null }
    var posterMode = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }
    var guideRowsFocusable = true
    var guideRowHeight = 0
    var unhealthy: (Channel) -> Boolean = { false }

    fun paintRowFocus(list: RecyclerView) {
        for (index in 0 until list.childCount) {
            val target = list.getChildAt(index).findViewById<View>(R.id.playTarget) ?: continue
            val allow = posterMode || guideRowsFocusable
            target.isFocusable = allow
            target.isFocusableInTouchMode = allow
        }
    }

    fun setPlaying(id: String?) {
        if (playingId == id) return
        playingId = id
        if (items.isNotEmpty() && !posterMode) notifyItemRangeChanged(0, items.size)
    }

    fun setGuideFocused(id: String?) {
        if (focusedId == id) return
        val prev = focusedId
        focusedId = id
        if (posterMode || items.isEmpty()) return
        if (prev != null) {
            val oldIdx = items.indexOfFirst { it.id == prev }
            if (oldIdx >= 0) notifyItemChanged(oldIdx, PAYLOAD_GUIDE_FOCUS)
        }
        if (id != null) {
            val newIdx = items.indexOfFirst { it.id == id }
            if (newIdx >= 0) notifyItemChanged(newIdx, PAYLOAD_GUIDE_FOCUS)
        }
    }

    fun submit(list: List<Channel>, groupLabel: String? = null, sequentialNumbers: Boolean = false) {
        val replaceAll = this.groupLabel != groupLabel ||
            this.sequentialNumbers != sequentialNumbers ||
            items.isEmpty() ||
            list.isEmpty()
        this.groupLabel = groupLabel
        this.sequentialNumbers = sequentialNumbers
        if (replaceAll) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
            return
        }
        val old = items.toList()
        items.clear()
        items.addAll(list)
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                old[oldPosition].id == list[newPosition].id
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                old[oldPosition] == list[newPosition]
        }).dispatchUpdatesTo(this)
    }

    fun append(more: List<Channel>) {
        if (more.isEmpty()) return
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    private val details = HashMap<String, String>()

    fun setDetail(channelId: String, line: String) {
        if (line.isBlank()) return
        details[channelId] = line
        notifyEpgChanged(channelId)
    }

    private fun detailOf(id: String): String? = details[id]

    fun notifyEpgChanged(channelId: String) {
        val index = items.indexOfFirst { it.id == channelId }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_EPG)
    }

    fun notifyFavoriteChanged(channelId: String) {
        val index = items.indexOfFirst { it.id == channelId }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_FAVORITE)
    }

    fun remove(channelId: String) {
        val index = items.indexOfFirst { it.id == channelId }
        if (index < 0) return
        items.removeAt(index)
        notifyItemRemoved(index)
        if (index < items.size) notifyItemRangeChanged(index, items.size - index)
    }

    fun setScrolling(active: Boolean) {
        scrolling = active
    }

    fun isScrolling(): Boolean = scrolling

    fun prefetch(context: Context, start: Int, count: Int, sizeDp: Int) {
        if (start < 0 || count <= 0 || start >= items.size) return
        val urls = ArrayList<String>(count)
        val end = (start + count).coerceAtMost(items.size)
        for (index in start until end) {
            items[index].logoUrl?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        prefetchLogos(context, urls, px)
    }

    fun refreshLogos(first: Int, last: Int) {
        scrolling = false
        if (first < 0 || last < first || first >= items.size) return
        val end = last.coerceAtMost(items.lastIndex)
        notifyItemRangeChanged(first, end - first + 1, PAYLOAD_LOGO)
    }

    override fun getItemViewType(position: Int): Int = if (posterMode) VIEW_POSTER else VIEW_ROW

    private fun holderMatchesMode(holder: Holder): Boolean =
        if (posterMode) holder is PosterHolder else holder is RowHolder

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_POSTER) {
            PosterHolder(ItemPosterBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemChannelBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = holder as? Holder ?: return
        if (!holderMatchesMode(row)) return
        row.bind(items[position], position, full = true)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = holder as? Holder ?: return
        if (!holderMatchesMode(row)) return
        when {
            payloads.contains(PAYLOAD_EPG) -> row.bindEpg(items[position])
            payloads.contains(PAYLOAD_FAVORITE) -> row.bindFavorite(items[position])
            payloads.contains(PAYLOAD_LOGO) -> row.bindLogo(items[position])
            payloads.contains(PAYLOAD_GUIDE_FOCUS) -> (row as? RowHolder)?.bindGuideHighlight(items[position])
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        var hash = 1125899906842597L
        val id = items[position].id
        for (index in id.indices) hash = 31L * hash + id[index].code
        return if (posterMode) hash xor 1L else hash
    }

    abstract inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(item: Channel, position: Int, full: Boolean)
        abstract fun bindLogo(item: Channel)
        abstract fun bindFavorite(item: Channel)
        abstract fun bindEpg(item: Channel)
    }

    inner class PosterHolder(private val binding: ItemPosterBinding) : Holder(binding.root) {
        override fun bind(item: Channel, position: Int, full: Boolean) {
            binding.name.text = displayName(item)
            binding.group.text = com.olympus.iptv.data.NameImages.strip(
                detailOf(item.id) ?: groupLabel ?: item.groupTitle.orEmpty()
            )
            bindLogo(item)
            bindPosterChrome(item)
            binding.playTarget.isFocusable = true
            binding.playTarget.isFocusableInTouchMode = true
            val touch = DeviceForm.isTouchPrimary(binding.root.context)
            binding.playTarget.setOnClickListener {
                if (touch) Haptics.tick(binding.playTarget)
                onClick(item)
            }
            binding.playTarget.setOnLongClickListener { onLongClick(item) }
            binding.playTarget.setOnFocusChangeListener { _, hasFocus ->
                if (!touch) FocusMotion.animate(binding.playTarget, hasFocus)
                if (hasFocus) onFocused(item)
            }
        }

        private fun bindPosterChrome(item: Channel) {
            val rating = posterRating(item)
            if (rating.isNullOrBlank()) {
                binding.ratingBadge.visibility = View.GONE
            } else {
                binding.ratingBadge.text = rating
                binding.ratingBadge.visibility = View.VISIBLE
            }
            val meta = posterMeta(item)
            if (meta.isNullOrBlank()) {
                binding.posterMeta.visibility = View.GONE
            } else {
                binding.posterMeta.text = meta
                binding.posterMeta.visibility = View.VISIBLE
            }
            val progress = continueProgress(item)
            if (progress == null || progress <= 0) {
                binding.watchProgress.visibility = View.GONE
            } else {
                binding.watchProgress.progress = progress.coerceIn(1, 1000)
                binding.watchProgress.visibility = View.VISIBLE
            }
        }

        override fun bindLogo(item: Channel) {
            val radius = 8f * binding.root.resources.displayMetrics.density
            binding.logo.clipToOutline = true
            binding.logo.outlineProvider = ViewOutlineProvider.BACKGROUND
            loadLogo(binding.logo, item.logoUrl, 160, poster = true, cornerPx = radius)
        }

        override fun bindFavorite(item: Channel) = Unit

        override fun bindEpg(item: Channel) {
            val resume = continueLabel(item)
            val detail = detailOf(item.id)
            val group = com.olympus.iptv.data.NameImages.strip(groupLabel ?: item.groupTitle.orEmpty())
            binding.group.text = listOfNotNull(resume, detail).joinToString(" · ").ifBlank { group }
            bindPosterChrome(item)
        }
    }

    inner class RowHolder(private val binding: ItemChannelBinding) : Holder(binding.root) {

        override fun bind(item: Channel, position: Int, full: Boolean) {
            if (full) {
                val portraitCard = DeviceForm.isCompact(binding.root.context) &&
                    DeviceForm.isPortrait(binding.root.context)
                val params = binding.root.layoutParams
                if (portraitCard && params != null) {
                    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
                    if (params.height != wrap) {
                        params.height = wrap
                        binding.root.layoutParams = params
                    }
                } else if (guideRowHeight > 0 && params != null && params.height != guideRowHeight) {
                    params.height = guideRowHeight
                    binding.root.layoutParams = params
                }
                binding.number.visibility = View.VISIBLE
                binding.number.text = (position + 1).toString()
                binding.logo.background = null
                binding.logo.setPadding(0, 0, 0, 0)
                binding.name.text = com.olympus.iptv.data.NameStrip.shown(item.name, item.groupTitle)
                binding.name.maxLines = if (guideRowHeight > 0) 2 else 1
                binding.name.textSize = if (guideRowHeight > 0) 13f else 15f
                val badge = qualityBadgeFor(item.name)
                if (badge == null) {
                    binding.qualityBadge.visibility = View.GONE
                } else {
                    binding.qualityBadge.text = badge
                    binding.qualityBadge.visibility = View.VISIBLE
                }
                binding.catchupBadge.visibility = if (item.supportsCatchup()) View.VISIBLE else View.GONE
                binding.root.alpha = if (unhealthy(item)) 0.45f else 1f
                binding.group.visibility = View.GONE
                binding.country.visibility = View.GONE
                hideActions()
                val density = binding.root.resources.displayMetrics.density
                val padH = (6 * density).toInt()
                binding.playTarget.setPadding(padH, 0, padH, 0)
                val logo = ((if (guideRowHeight > 0) 18 else 24) * density).toInt()
                binding.logo.layoutParams.width = logo
                binding.logo.layoutParams.height = logo
                bindLogo(item)
                bindEpg(item)
                bindGuideHighlight(item)
                com.olympus.iptv.ui.LiveBars.show(binding.liveBars, item.id == playingId)
                binding.playTarget.isFocusable = guideRowsFocusable
                binding.playTarget.isFocusableInTouchMode = guideRowsFocusable
                val touch = DeviceForm.isTouchPrimary(binding.root.context)
                binding.playTarget.setOnClickListener {
                    if (touch) Haptics.tick(binding.playTarget)
                    onClick(item)
                }
                binding.playTarget.setOnFocusChangeListener { _, hasFocus ->
                    if (!touch && guideRowsFocusable) FocusMotion.animate(binding.playTarget, hasFocus)
                    if (hasFocus) onFocused(item)
                }
            }
        }

        fun bindGuideHighlight(item: Channel) {
            val ctx = binding.root.context
            val active = !guideRowsFocusable && item.id == focusedId
            binding.number.setTextColor(
                ctx.getColor(if (active) R.color.accent else R.color.text_secondary)
            )
            binding.name.setTextColor(
                ctx.getColor(if (active) R.color.accent else R.color.text_primary)
            )
            // Never draw a focus rectangle around the channel name row in the live EPG grid.
            binding.root.background = null
            binding.playTarget.background = null
        }

        private fun hideActions() {
            binding.favorite.visibility = View.GONE
            binding.record.visibility = View.GONE
            binding.moveUp.visibility = View.GONE
            binding.moveDown.visibility = View.GONE
            binding.favorite.isFocusable = false
            binding.record.isFocusable = false
            binding.moveUp.isFocusable = false
            binding.moveDown.isFocusable = false
        }

        override fun bindLogo(item: Channel) {
            loadLogo(binding.logo, item.logoUrl, 24)
        }

        override fun bindFavorite(item: Channel) {
            val saved = isFavorite(item)
            binding.favorite.setImageResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_outline)
            val context = binding.root.context
            val tint = if (saved) {
                accentTint ?: android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent)).also { accentTint = it }
            } else {
                mutedTint ?: android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_secondary)).also { mutedTint = it }
            }
            binding.favorite.imageTintList = tint
        }

        override fun bindEpg(item: Channel) {
            val context = binding.root.context
            val card = DeviceForm.isCompact(context) &&
                DeviceForm.isPortrait(context) &&
                item.kind == ContentKind.LIVE &&
                item.seriesId.isNullOrBlank()
            if (!card) {
                binding.epgProgress.visibility = View.GONE
                binding.epgNext.visibility = View.GONE
                val touch = DeviceForm.isTouchPrimary(context)
                if (!touch || item.kind != ContentKind.LIVE || !item.seriesId.isNullOrBlank()) {
                    binding.epgNow.visibility = View.GONE
                    return
                }
                val line = epgProvider(item)?.nowLine().orEmpty()
                binding.epgNow.text = line
                binding.epgNow.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
                return
            }
            val info = epgProvider(item)
            val now = info?.now
            if (now == null) {
                binding.epgNow.visibility = View.GONE
                binding.epgProgress.visibility = View.GONE
            } else {
                binding.epgNow.text = now.title
                binding.epgNow.visibility = View.VISIBLE
                val span = now.endMillis - now.startMillis
                val fraction = if (span > 0L) {
                    ((System.currentTimeMillis() - now.startMillis) * 1000L / span).toInt()
                } else {
                    0
                }
                binding.epgProgress.progress = fraction.coerceIn(0, 1000)
                binding.epgProgress.visibility = View.VISIBLE
            }
            val next = info?.nextLine().orEmpty()
            binding.epgNext.text = next
            binding.epgNext.visibility = if (next.isBlank()) View.GONE else View.VISIBLE
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val logo = holder.itemView.findViewById<android.widget.ImageView>(R.id.logo) ?: return
        logo.setTag(R.id.logo, null)
        logo.dispose()
    }

    private fun loadLogo(
        view: android.widget.ImageView,
        url: String?,
        sizeDp: Int,
        poster: Boolean = false,
        cornerPx: Float = 0f
    ) {
        val key = url.orEmpty()
        if (key.isBlank()) {
            view.setTag(R.id.logo, null)
            view.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }
        if (view.getTag(R.id.logo) == key && view.drawable != null) return
        if (scrolling) {
            view.setTag(R.id.logo, null)
            view.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }
        view.setTag(R.id.logo, key)
        view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val px = logoPx(view, sizeDp).let { size ->
            if (poster && DeviceForm.powerSave(view.context)) size.coerceAtMost(320) else size
        }
        view.load(key) {
            channelLogo(px)
            if (poster && cornerPx > 0f) transformations(RoundedCornersTransformation(cornerPx))
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }
    }

    companion object {
        private const val VIEW_ROW = 0
        private const val VIEW_POSTER = 1
        private const val PAYLOAD_EPG = "epg"
        private const val PAYLOAD_FAVORITE = "favorite"
        private const val PAYLOAD_LOGO = "logo"
        private const val PAYLOAD_GUIDE_FOCUS = "guide_focus"

        private fun logoPx(view: android.view.View, dp: Int): Int {
            val fromDp = (dp * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val measured = maxOf(view.width, view.height)
            return if (measured > 0) minOf(fromDp, measured) else fromDp
        }
    }

    private fun qualityBadgeFor(rawName: String): String? {
        val upper = rawName.uppercase()
        return when {
            "4K" in upper || "2160P" in upper || "UHD" in upper -> "4K"
            "1080P" in upper || "FHD" in upper -> "FHD"
            "60FPS" in upper -> "60FPS"
            else -> null
        }
    }

    private fun displayName(item: Channel): String = com.olympus.iptv.data.NameStrip.shown(item.name, item.groupTitle)
}
