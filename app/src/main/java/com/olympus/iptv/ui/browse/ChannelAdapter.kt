package com.olympus.iptv.ui.browse

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import java.util.concurrent.atomic.AtomicInteger
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgNowNext
import com.olympus.iptv.databinding.ItemChannelBinding
import com.olympus.iptv.databinding.ItemPosterBinding

internal fun stableRowId(vararg parts: String?): Long {
    var hash = 1125899906842597L
    for (part in parts) {
        if (part == null) {
            hash = 31L * hash + 1L
            continue
        }
        for (index in 0 until part.length) hash = 31L * hash + part[index].code
        hash *= 31L
    }
    return hash
}

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Boolean = { false },
    private val onFocused: (Channel) -> Unit,
    private val onMove: (Channel, Int) -> Unit,
    private val isFavorite: (Channel) -> Boolean,
    private val onFavorite: (Channel) -> Unit,
    private val onRecord: (Channel) -> Unit,
    private val epgProvider: (Channel) -> EpgNowNext?,
    private val onWatched: (Channel) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Channel>()
    private val indexById = HashMap<String, Int>()
    private val submitGeneration = AtomicInteger()
    private var accentTint: android.content.res.ColorStateList? = null
    private var mutedTint: android.content.res.ColorStateList? = null

    private fun rebuildIndex() {
        indexById.clear()
        for (i in items.indices) indexById.putIfAbsent(items[i].id, i)
    }

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
        val prev = playingId
        playingId = id
        if (items.isEmpty() || posterMode) return
        if (prev != null) {
            indexById[prev]?.let { notifyItemChanged(it, PAYLOAD_PLAYING) }
        }
        if (id != null) {
            indexById[id]?.let { notifyItemChanged(it, PAYLOAD_PLAYING) }
        }
    }

    fun setGuideFocused(id: String?) {
        if (focusedId == id) return
        val prev = focusedId
        focusedId = id
        if (posterMode || items.isEmpty()) return
        if (prev != null) {
            indexById[prev]?.let { notifyItemChanged(it, PAYLOAD_GUIDE_FOCUS) }
        }
        if (id != null) {
            indexById[id]?.let { notifyItemChanged(it, PAYLOAD_GUIDE_FOCUS) }
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
            rebuildIndex()
            notifyDataSetChanged()
            return
        }
        val old = items.toList()
        if (old == list) return
        val next = list.toList()
        val token = submitGeneration.incrementAndGet()
        ListDiff.dispatch(
            submitGeneration,
            token,
            old,
            next,
            sameItem = { left, right -> left.id == right.id },
            sameContent = { left, right -> left == right },
            payload = { left, right ->
                if (left.name != right.name || left.number != right.number || left.streamUrl != right.streamUrl) {
                    null
                } else if (left.logoUrl != right.logoUrl) {
                    PAYLOAD_LOGO
                } else {
                    PAYLOAD_EPG
                }
            }
        ) { diff, rows ->
            items.clear()
            items.addAll(rows)
            rebuildIndex()
            diff.dispatchUpdatesTo(this)
        }
    }

    fun append(more: List<Channel>) {
        if (more.isEmpty()) return
        val start = items.size
        items.addAll(more)
        for (i in start until items.size) indexById.putIfAbsent(items[i].id, i)
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
        indexById[channelId]?.let { notifyItemChanged(it, PAYLOAD_EPG) }
    }

    fun notifyFavoriteChanged(channelId: String) {
        indexById[channelId]?.let { notifyItemChanged(it, PAYLOAD_FAVORITE) }
    }

    fun remove(channelId: String) {
        val index = indexById[channelId] ?: return
        items.removeAt(index)
        rebuildIndex()
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
            payloads.contains(PAYLOAD_PLAYING) -> (row as? RowHolder)?.bindPlaying(items[position])
            payloads.contains(PAYLOAD_EPG) -> row.bindEpg(items[position])
            payloads.contains(PAYLOAD_FAVORITE) -> row.bindFavorite(items[position])
            payloads.contains(PAYLOAD_LOGO) -> row.bindLogo(items[position])
            payloads.contains(PAYLOAD_GUIDE_FOCUS) -> (row as? RowHolder)?.bindGuideHighlight(items[position])
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        val hash = stableRowId(items.getOrNull(position)?.id)
        return if (posterMode) hash xor 1L else hash
    }

    abstract inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(item: Channel, position: Int, full: Boolean)
        abstract fun bindLogo(item: Channel)
        abstract fun bindFavorite(item: Channel)
        abstract fun bindEpg(item: Channel)
    }

    inner class PosterHolder(private val binding: ItemPosterBinding) : Holder(binding.root) {
        private var heightPosted = false

        init {
            binding.name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            binding.group.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            binding.logo.clipToOutline = true
            binding.logo.outlineProvider = ViewOutlineProvider.BACKGROUND
            binding.logo.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.playTarget.isFocusable = true
            binding.playTarget.isFocusableInTouchMode = true
        }

        private fun syncPosterHeight() {
            val frame = binding.logo.parent as? View ?: return
            val width = binding.root.width
            if (width > 0) {
                val height = (width * 1.32f).toInt().coerceAtLeast(width)
                val lp = frame.layoutParams
                if (lp != null && lp.height != height) {
                    lp.height = height
                    frame.layoutParams = lp
                }
            } else if (!heightPosted) {
                heightPosted = true
                binding.root.post {
                    heightPosted = false
                    val w = binding.root.width
                    if (w <= 0) return@post
                    val f = binding.logo.parent as? View ?: return@post
                    val height = (w * 1.32f).toInt().coerceAtLeast(w)
                    val lp = f.layoutParams
                    if (lp != null && lp.height != height) {
                        lp.height = height
                        f.layoutParams = lp
                    }
                }
            }
        }

        override fun bind(item: Channel, position: Int, full: Boolean) {
            val params = binding.root.layoutParams
            if (params != null && params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                binding.root.layoutParams = params
            }
            syncPosterHeight()
            binding.name.text = displayName(item)
            val resume = continueLabel(item)
            val detail = detailOf(item.id)
            val group = com.olympus.iptv.data.NameImages.strip(groupLabel ?: item.groupTitle.orEmpty())
            val sub = listOfNotNull(resume, detail).joinToString(" · ").ifBlank { group }
            binding.group.text = sub
            binding.group.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            bindLogo(item)
            bindPosterChrome(item)
            bindFavorite(item)

            binding.btnPosterWatched.setOnClickListener { onWatched(item) }
            binding.btnPosterFavorite.setOnClickListener { onFavorite(item) }
            binding.btnPosterMore.setOnClickListener { onLongClick(item) }

            val touch = DeviceForm.isTouchPrimary(binding.root.context)
            binding.playTarget.setOnClickListener {
                if (touch) Haptics.tick(binding.playTarget)
                onClick(item)
            }
            binding.playTarget.setOnLongClickListener { onLongClick(item) }
            binding.playTarget.setOnFocusChangeListener { _, hasFocus ->
                binding.posterHoverOverlay.visibility = if (hasFocus) View.VISIBLE else View.GONE
                if (!touch) FocusMotion.animate(binding.playTarget, hasFocus)
                if (hasFocus) onFocused(item)
            }
        }

        private fun bindPosterChrome(item: Channel) {
            val rating = posterRating(item)
            when {
                !rating.isNullOrBlank() -> {
                    binding.ratingBadge.text = rating.replace("★", "").trim()
                    binding.ratingBadge.visibility = View.VISIBLE
                }
                !item.seriesId.isNullOrBlank() -> {
                    binding.ratingBadge.text = "TV"
                    binding.ratingBadge.visibility = View.VISIBLE
                }
                else -> binding.ratingBadge.visibility = View.GONE
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
            loadLogo(binding.logo, item.logoUrl, 240, poster = true, cornerPx = 0f)
        }

        override fun bindFavorite(item: Channel) {
            binding.btnPosterFavorite.setImageResource(
                if (isFavorite(item)) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )
        }

        override fun bindEpg(item: Channel) {
            val resume = continueLabel(item)
            val detail = detailOf(item.id)
            val group = com.olympus.iptv.data.NameImages.strip(groupLabel ?: item.groupTitle.orEmpty())
            val sub = listOfNotNull(resume, detail).joinToString(" · ").ifBlank { group }
            binding.group.text = sub
            binding.group.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
            bindPosterChrome(item)
        }
    }

    inner class RowHolder(private val binding: ItemChannelBinding) : Holder(binding.root) {

        override fun bind(item: Channel, position: Int, full: Boolean) {
            if (full) {
                val ctx = binding.root.context
                val inSideBySideGuide = guideRowHeight > 0
                val phoneCard = DeviceForm.isTouchPrimary(ctx) && DeviceForm.isCompact(ctx) && !inSideBySideGuide
                val portraitCard = phoneCard && DeviceForm.isPortrait(ctx)
                val params = binding.root.layoutParams
                if (params != null) {
                    val targetHeight = if (inSideBySideGuide) {
                        guideRowHeight
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    if (params.width != ViewGroup.LayoutParams.MATCH_PARENT || params.height != targetHeight) {
                        params.width = ViewGroup.LayoutParams.MATCH_PARENT
                        params.height = targetHeight
                        binding.root.layoutParams = params
                    }
                }
                binding.root.minimumHeight = if (inSideBySideGuide) guideRowHeight else 0
                binding.number.visibility = View.VISIBLE
                binding.number.text = (position + 1).toString()
                binding.logo.background = null
                binding.logo.setPadding(0, 0, 0, 0)
                binding.name.text = com.olympus.iptv.data.NameStrip.shown(item.name, item.groupTitle)
                binding.name.maxLines = if (phoneCard || inSideBySideGuide) 2 else 1
                binding.name.textSize = if (phoneCard) 16f else if (inSideBySideGuide) 13f else 15f
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
                val touch = DeviceForm.isTouchPrimary(binding.root.context)
                val padV = if (phoneCard) ((if (portraitCard) 12 else 10) * density).toInt() else 0
                val padH = ((if (phoneCard) 12 else 6) * density).toInt()
                if (binding.playTarget.paddingLeft != padH || binding.playTarget.paddingTop != padV) {
                    binding.playTarget.setPadding(padH, padV, padH, padV)
                }
                val rootPadH = if (phoneCard) padH else 0
                val rootPadV = if (phoneCard) (4 * density).toInt() else 0
                if (binding.root.paddingLeft != rootPadH || binding.root.paddingTop != rootPadV) {
                    binding.root.setPadding(rootPadH, rootPadV, rootPadH, rootPadV)
                }
                val logo = ((if (phoneCard) 40 else if (inSideBySideGuide) (if (touch) 20 else 18) else 24) * density).toInt()
                val logoLp = binding.logo.layoutParams
                if (logoLp != null && (logoLp.width != logo || logoLp.height != logo)) {
                    logoLp.width = logo
                    logoLp.height = logo
                    binding.logo.layoutParams = logoLp
                }
                bindLogo(item)
                bindEpg(item)
                bindGuideHighlight(item, position)
                if (touch && !inSideBySideGuide) {
                    binding.favorite.visibility = View.VISIBLE
                    binding.favorite.isFocusable = false
                    binding.favorite.isFocusableInTouchMode = false
                    bindFavorite(item)
                    binding.favorite.setOnClickListener {
                        Haptics.tick(binding.favorite)
                        onFavorite(item)
                    }
                }
                com.olympus.iptv.ui.LiveBars.show(binding.liveBars, item.id == playingId)
                binding.playTarget.isFocusable = guideRowsFocusable
                binding.playTarget.isFocusableInTouchMode = guideRowsFocusable
                binding.playTarget.setOnClickListener {
                    if (touch) Haptics.tick(binding.playTarget)
                    onClick(item)
                }
                binding.playTarget.setOnLongClickListener {
                    if (touch) Haptics.tick(binding.playTarget)
                    onLongClick(item)
                }
                binding.playTarget.setOnFocusChangeListener { _, hasFocus ->
                    if (!touch && guideRowsFocusable) FocusMotion.animate(binding.playTarget, hasFocus)
                    if (hasFocus) onFocused(item)
                }
            }
        }

        fun bindPlaying(item: Channel) {
            com.olympus.iptv.ui.LiveBars.show(binding.liveBars, item.id == playingId)
        }

        fun bindGuideHighlight(item: Channel, position: Int = bindingAdapterPosition) {
            val ctx = binding.root.context
            val inSideBySideGuide = guideRowHeight > 0
            val active = !guideRowsFocusable && item.id == focusedId
            binding.number.setTextColor(
                ctx.getColor(if (active) R.color.accent else R.color.text_secondary)
            )
            binding.name.setTextColor(
                ctx.getColor(if (active) R.color.accent else R.color.text_primary)
            )
            val phoneCard = DeviceForm.isTouchPrimary(ctx) && DeviceForm.isCompact(ctx) && !inSideBySideGuide
            if (phoneCard) {
                binding.root.background = null
                binding.playTarget.setBackgroundResource(R.drawable.bg_focusable)
            } else {
                // Match ProgramGuideAdapter's subtle alternating row band so eyes track straight across.
                val band = if (inSideBySideGuide && position >= 0 && position % 2 == 1) {
                    0x0FFFFFFF
                } else {
                    android.graphics.Color.TRANSPARENT
                }
                binding.root.setBackgroundColor(band)
                binding.playTarget.background = null
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
            val context = binding.root.context
            val touch = DeviceForm.isTouchPrimary(context)
            // On mobile side-by-side EPG guide mode, the right-hand program timeline shows airing shows;
            // keeping the left channel cell clean locks its height 1:1 with the program row.
            if ((touch && guideRowHeight > 0) || item.kind != ContentKind.LIVE || !item.seriesId.isNullOrBlank()) {
                binding.epgNow.visibility = View.GONE
                binding.epgProgress.visibility = View.GONE
                binding.epgNext.visibility = View.GONE
                return
            }
            val info = epgProvider(item)
            val line = info?.nowLine()?.ifBlank { info.now?.title }.orEmpty()
            if (line.isNotBlank()) {
                binding.epgNow.text = line
                binding.epgNow.visibility = View.VISIBLE
            } else {
                binding.epgNow.visibility = View.GONE
            }
            val now = info?.now
            val card = DeviceForm.isTouchPrimary(context) && DeviceForm.isCompact(context)
            if (card && now != null && now.endMillis > now.startMillis) {
                val span = now.endMillis - now.startMillis
                val fraction = ((System.currentTimeMillis() - now.startMillis) * 1000L / span).toInt()
                binding.epgProgress.progress = fraction.coerceIn(0, 1000)
                binding.epgProgress.visibility = View.VISIBLE
                val next = info.nextLine()
                binding.epgNext.text = next
                binding.epgNext.visibility = if (next.isNotBlank()) View.VISIBLE else View.GONE
            } else {
                binding.epgProgress.visibility = View.GONE
                binding.epgNext.visibility = View.GONE
            }
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
        view.scaleType = if (poster) android.widget.ImageView.ScaleType.CENTER_CROP else android.widget.ImageView.ScaleType.FIT_CENTER
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
        internal const val PAYLOAD_EPG = "epg"
        private const val PAYLOAD_FAVORITE = "favorite"
        private const val PAYLOAD_LOGO = "logo"
        private const val PAYLOAD_GUIDE_FOCUS = "guide_focus"
        private const val PAYLOAD_PLAYING = "playing"

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
