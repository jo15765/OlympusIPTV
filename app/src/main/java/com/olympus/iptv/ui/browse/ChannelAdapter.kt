package com.olympus.iptv.ui.browse

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
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
import com.olympus.iptv.data.Titles
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
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private val items = mutableListOf<Channel>()
    private val cleanedNames = HashMap<String, Pair<String, String>>()
    private var accentTint: android.content.res.ColorStateList? = null
    private var mutedTint: android.content.res.ColorStateList? = null

    init {
        setHasStableIds(true)
    }

    private var groupLabel: String? = null
    private var sequentialNumbers = false
    private var scrolling = false
    var continueLabel: (Channel) -> String? = { null }
    var posterMode = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }
    var guideRowsFocusable = true
    var guideRowHeight = 0

    fun paintRowFocus(list: RecyclerView) {
        for (index in 0 until list.childCount) {
            val target = list.getChildAt(index).findViewById<View>(R.id.playTarget) ?: continue
            val allow = posterMode || guideRowsFocusable
            target.isFocusable = allow
            target.isFocusableInTouchMode = allow
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_POSTER) {
            PosterHolder(ItemPosterBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemChannelBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], position, full = true)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        when {
            payloads.contains(PAYLOAD_EPG) -> holder.bindEpg(items[position])
            payloads.contains(PAYLOAD_FAVORITE) -> holder.bindFavorite(items[position])
            payloads.contains(PAYLOAD_LOGO) -> holder.bindLogo(items[position])
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
        }
    }

    inner class RowHolder(private val binding: ItemChannelBinding) : Holder(binding.root) {

        override fun bind(item: Channel, position: Int, full: Boolean) {
            if (full) {
                if (guideRowHeight > 0) {
                    val params = binding.root.layoutParams
                    if (params != null && params.height != guideRowHeight) {
                        params.height = guideRowHeight
                        binding.root.layoutParams = params
                    }
                }
                binding.number.visibility = View.VISIBLE
                binding.number.text = (position + 1).toString()
                binding.logo.background = null
                binding.logo.setPadding(0, 0, 0, 0)
                binding.name.text = item.name
                binding.group.visibility = View.GONE
                binding.country.visibility = View.GONE
                hideActions()
                val density = binding.root.resources.displayMetrics.density
                val padH = (6 * density).toInt()
                binding.playTarget.setPadding(padH, 0, padH, 0)
                val logo = (24 * density).toInt()
                binding.logo.layoutParams.width = logo
                binding.logo.layoutParams.height = logo
                bindLogo(item)
                bindEpg(item)
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
            val touch = DeviceForm.isTouchPrimary(binding.root.context)
            if (!touch || item.kind != ContentKind.LIVE || !item.seriesId.isNullOrBlank()) {
                binding.epgNow.visibility = View.GONE
                return
            }
            val line = epgProvider(item)?.nowLine().orEmpty()
            if (line.isBlank()) {
                binding.epgNow.visibility = View.GONE
            } else {
                binding.epgNow.text = line
                binding.epgNow.visibility = View.VISIBLE
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
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
        view.scaleType = if (poster) android.widget.ImageView.ScaleType.FIT_CENTER
        else android.widget.ImageView.ScaleType.FIT_CENTER
        view.load(key) {
            channelLogo(logoPx(view, sizeDp))
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

        private fun logoPx(view: android.view.View, dp: Int): Int =
            (dp * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun displayName(item: Channel): String {
        if (item.kind != ContentKind.VOD && item.seriesId.isNullOrBlank()) return item.name
        val cached = cleanedNames[item.id]
        if (cached != null && cached.first == item.name) return cached.second
        val cleaned = Titles.clean(item.name)
        cleanedNames[item.id] = item.name to cleaned
        return cleaned
    }
}
