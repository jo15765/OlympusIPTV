package com.olympus.iptv.ui.browse

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.EpgProgram
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class ProgramGuideAdapter(
    context: Context,
    private val onPlay: (Channel, EpgProgram?) -> Unit,
    private val onScroll: (Int) -> Unit,
    private val onFocus: (Channel, EpgProgram?) -> Unit = { _, _ -> },
    private val onHold: (Channel, EpgProgram?) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class Slot(val row: Int, val column: Int)

    private val density = context.resources.displayMetrics.density
    private val panel = context.getColor(R.color.bg_panel)
    private val focus = context.getColor(R.color.focus)
    private val ink = context.getColor(R.color.bg)
    private val primary = context.getColor(R.color.text_primary)
    private val secondary = context.getColor(R.color.text_secondary)
    private val channels = mutableListOf<Channel>()
    private val indexById = HashMap<String, Int>()
    private val programs = HashMap<String, List<EpgProgram>>()
    private val slotMs = 30L * 60L * 1000L
    private val hourMs = 60L * 60L * 1000L

    init {
        setHasStableIds(true)
    }

    private fun rebuildIndex() {
        indexById.clear()
        for (i in channels.indices) indexById.putIfAbsent(channels[i].id, i)
    }

    override fun getItemId(position: Int): Long = stableRowId(channels.getOrNull(position)?.id)

    var slotPx = dp(240)
        private set

    fun setViewportWidth(widthPx: Int): Boolean {
        if (widthPx <= 0) return false
        val next = (widthPx / 3).coerceAtLeast(dp(148))
        if (next == slotPx) return false
        slotPx = next
        blockCache.clear()
        if (channels.isNotEmpty()) notifyItemRangeChanged(0, channels.size)
        return true
    }
    var windowStart = 0L
        private set
    var windowEnd = 0L
        private set
    private val submitGeneration = AtomicInteger()
    var scrollX = 0
    var rowHeight = dp(52)
    private val labelPad = dp(12)
    private val labelMinVisible = dp(56)

    fun setWindow(start: Long, end: Long): Boolean {
        if (windowStart == start && windowEnd == end) return false
        windowStart = start
        windowEnd = end
        blockCache.clear()
        if (channels.isNotEmpty()) notifyItemRangeChanged(0, channels.size)
        return true
    }

    fun submit(list: List<Channel>) {
        if (list.isEmpty()) {
            val old = channels.size
            channels.clear()
            indexById.clear()
            if (old > 0) notifyItemRangeRemoved(0, old)
            return
        }
        if (channels.isEmpty()) {
            channels.addAll(list)
            rebuildIndex()
            notifyItemRangeInserted(0, list.size)
            return
        }
        var shared = 0
        val limit = minOf(channels.size, list.size)
        while (shared < limit && channels[shared].id == list[shared].id) shared += 1
        if (shared == channels.size && shared == list.size) {
            var dirty = false
            for (index in 0 until shared) {
                if (channels[index] != list[index]) {
                    channels[index] = list[index]
                    dirty = true
                }
            }
            if (dirty) notifyItemRangeChanged(0, shared)
            return
        }
        if (shared == channels.size && list.size > channels.size) {
            val added = list.subList(shared, list.size)
            channels.addAll(added)
            for (i in shared until channels.size) indexById.putIfAbsent(channels[i].id, i)
            notifyItemRangeInserted(shared, added.size)
            return
        }
        if (shared == list.size && channels.size > list.size) {
            val removed = channels.size - list.size
            for (index in 0 until shared) channels[index] = list[index]
            channels.subList(shared, channels.size).clear()
            rebuildIndex()
            notifyItemRangeRemoved(shared, removed)
            if (shared > 0) notifyItemRangeChanged(0, shared)
            return
        }
        val old = channels.toList()
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
            payload = { left, right -> if (left.name == right.name && left.logoUrl == right.logoUrl) "epg" else null }
        ) { diff, rows ->
            channels.clear()
            channels.addAll(rows)
            rebuildIndex()
            diff.dispatchUpdatesTo(this)
        }
    }

    fun channelAt(index: Int): Channel? = channels.getOrNull(index)

    fun programsFor(id: String): List<EpgProgram>? = if (id in programs) programs[id] else null

    fun programAt(channelId: String, column: Int): EpgProgram? {
        val channel = indexById[channelId]?.let { channels.getOrNull(it) } ?: return null
        val shown = cachedBlocks(channel, programs[channelId]).getOrNull(column)?.program ?: return null
        return programs[channelId].orEmpty().firstOrNull { program ->
            program.title == shown.title &&
                program.startMillis < shown.endMillis &&
                program.endMillis > shown.startMillis
        } ?: shown
    }

    fun put(id: String, list: List<EpgProgram>) {
        programs[id] = list
        indexById[id]?.let { notifyItemChanged(it) }
    }

    fun columnAt(channel: Channel, time: Long): Int {
        val blocks = cachedBlocks(channel, programs[channel.id])
        val index = blocks.indexOfFirst { time >= it.program.startMillis && time < it.program.endMillis }
        return if (index >= 0) index else 0
    }

    fun pxFor(time: Long): Int {
        if (windowEnd <= windowStart) return 0
        return ((time - windowStart) * slotPx / slotMs).toInt()
    }

    fun timelineWidth(): Int {
        val span = (windowEnd - windowStart).coerceAtLeast(slotMs)
        return (span * slotPx / slotMs).toInt().coerceAtLeast(slotPx)
    }

    fun refreshLabelShift(list: RecyclerView): Boolean {
        var needTall = false
        for (index in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(index)) as? Holder ?: continue
            if (holder.shiftLabels()) needTall = true
        }
        return needTall
    }

    override fun getItemCount(): Int = channels.size

    override fun getItemViewType(position: Int): Int = VIEW_PROGRAM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_program_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = holder as? Holder ?: return
        row.bind(channels[position], position)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private val appContext = context.applicationContext
    private val blockCacheCap = if (com.olympus.iptv.ui.DeviceForm.isLowRam(context)) 36 else 80
    private val blockCache = object : LinkedHashMap<String, List<Block>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Block>>?) = size > blockCacheCap
    }

    private fun cachedBlocks(channel: Channel, loaded: List<EpgProgram>?): List<Block> {
        val offsetMin = com.olympus.iptv.data.ProFeatureStore.load(appContext).playback.epgOffsetMinutes
        val key = buildString {
            append(channel.id)
            append(':')
            append(windowStart)
            append(':')
            append(windowEnd)
            append(':')
            append(offsetMin)
            append(':')
            append(loaded?.size ?: -1)
            append(':')
            append(loaded?.firstOrNull()?.startMillis ?: 0L)
            append(':')
            append(loaded?.lastOrNull()?.endMillis ?: 0L)
        }
        blockCache[key]?.let { return it }
        val built = guideBlocks(channel, loaded, offsetMin)
        blockCache[key] = built
        return built
    }

    private fun guideBlocks(channel: Channel, loaded: List<EpgProgram>?, proEpgOffsetMinutes: Int = 0): List<Block> {
        if (channel.kind != ContentKind.LIVE || loaded == null || windowEnd <= windowStart) return emptyList()
        val name = com.olympus.iptv.data.NameStrip.shown(channel.name, channel.groupTitle).trim()
        if (name.isEmpty()) return emptyList()
        val shiftMs = proEpgOffsetMinutes * 60_000L
        val clipped = ArrayList<EpgProgram>(loaded.size)
        for (program in loaded) {
            val pStart = if (shiftMs == 0L) program.startMillis else program.startMillis + shiftMs
            val pEnd = if (shiftMs == 0L) program.endMillis else program.endMillis + shiftMs
            if (pEnd <= windowStart || pStart >= windowEnd) continue
            val start = pStart.coerceAtLeast(windowStart)
            val end = pEnd.coerceAtMost(windowEnd)
            if (end > start) clipped.add(program.copy(startMillis = start, endMillis = end))
        }
        clipped.sortBy { it.startMillis }
        val segments = if (clipped.isEmpty() || clipped.all { isChannelFiller(it.title, name) }) {
            hourSlices(name, windowStart, windowEnd)
        } else {
            val out = ArrayList<EpgProgram>()
            var cursor = windowStart
            for (program in clipped) {
                if (program.startMillis > cursor) out.addAll(hourSlices(name, cursor, program.startMillis))
                val start = program.startMillis.coerceAtLeast(cursor)
                if (program.endMillis > start) {
                    if (isChannelFiller(program.title, name)) {
                        out.addAll(hourSlices(name, start, program.endMillis))
                    } else {
                        out.add(program.copy(startMillis = start))
                    }
                    cursor = maxOf(cursor, program.endMillis)
                }
            }
            if (cursor < windowEnd) out.addAll(hourSlices(name, cursor, windowEnd))
            out
        }
        val gap = dp(6)
        return segments.map { program ->
            val left = pxFor(program.startMillis)
            val right = pxFor(program.endMillis)
            Block(program, left, (right - left - gap).coerceAtLeast(1))
        }
    }

    private fun hourSlices(title: String, from: Long, to: Long): List<EpgProgram> {
        if (to <= from) return emptyList()
        val out = ArrayList<EpgProgram>()
        var cursor = from
        while (cursor < to) {
            var next = minOf(cursor + hourMs, to)
            if (to - next in 1 until hourMs / 4) next = to
            out.add(EpgProgram(title, cursor, next))
            cursor = next
        }
        return out
    }

    private fun isChannelFiller(title: String, channelName: String): Boolean {
        if (com.olympus.iptv.data.NameStrip.isPlaceholderEvent(title)) return true
        val program = normalize(title)
        if (program.isEmpty() || program in emptyGuide) return true
        val channel = normalize(channelName)
        if (channel.isEmpty()) return false
        val programWords = program.split(' ').filter { it.length > 1 && it !in fillerWords }
        val channelWords = channel.split(' ').filter { it.length > 1 }
        if (programWords.isEmpty()) return true
        if (channelWords.isEmpty()) return false
        return programWords.all { word -> word in channelWords }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).replace(wordBreaks, " ").trim()

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val scroll: HorizontalScrollView = view.findViewById(R.id.programRowScroll)
        private val track: FrameLayout = view.findViewById(R.id.programTrack)
        private val pool = ArrayList<TextView>(24)

        fun bind(channel: Channel, position: Int) {
            val params = itemView.layoutParams
            if (params != null && params.height != rowHeight) {
                params.height = rowHeight
                itemView.layoutParams = params
            }
            itemView.setBackgroundColor(
                if (position % 2 == 1) 0x0FFFFFFF else android.graphics.Color.TRANSPARENT
            )
            val width = timelineWidth()
            val trackParams = track.layoutParams
            if (trackParams == null || trackParams.width != width) {
                track.layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val loaded = programs[channel.id] ?: emptyList()
            val blocks = cachedBlocks(channel, loaded)
            val needed = if (blocks.isEmpty()) 1 else blocks.size
            while (track.childCount > needed) {
                val child = track.getChildAt(track.childCount - 1) as? TextView
                track.removeViewAt(track.childCount - 1)
                if (child != null) pool.add(child)
            }
            while (track.childCount < needed) {
                track.addView(obtainBlock())
            }
            if (blocks.isEmpty()) {
                bindBlock(
                    view = track.getChildAt(0) as TextView,
                    channel = channel,
                    program = null,
                    title = itemView.context.getString(R.string.epg_none),
                    time = null,
                    left = 0,
                    width = width,
                    slot = Slot(position, 0)
                )
            } else {
                val maskSports = com.olympus.iptv.data.ProFeatureStore.load(itemView.context).playback.spoilerFreeSports
                blocks.forEachIndexed { index, block ->
                    val rawTitle = block.program.guideTitle.ifEmpty { block.program.title }
                    bindBlock(
                        view = track.getChildAt(index) as TextView,
                        channel = channel,
                        program = block.program,
                        title = com.olympus.iptv.data.ProFilters.maskSportsSpoilers(rawTitle, maskSports),
                        time = block.program.guideClock.takeIf { it.isNotEmpty() },
                        left = block.left,
                        width = block.width,
                        slot = Slot(position, index)
                    )
                }
            }
            if (scroll.scrollX != scrollX) scroll.scrollTo(scrollX, 0)
            shiftLabels()
        }

        private fun obtainBlock(): TextView {
            val recycled = if (pool.isEmpty()) null else pool.removeAt(pool.lastIndex)
            val block = recycled ?: TextView(itemView.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                marqueeRepeatLimit = -1
                textSize = 13f
                isFocusable = true
                isClickable = true
            }
            block.translationX = 0f
            block.maxLines = 1
            block.isSingleLine = true
            block.ellipsize = android.text.TextUtils.TruncateAt.END
            block.isSelected = false
            block.setPaddingRelative(labelPad, 0, labelPad, 0)
            return block
        }

        fun shiftLabels(): Boolean {
            var needTall = false
            for (index in 0 until track.childCount) {
                val child = track.getChildAt(index) as? TextView ?: continue
                val layout = child.layoutParams as? FrameLayout.LayoutParams ?: continue
                if (shiftLabel(child, layout.leftMargin, layout.width)) needTall = true
            }
            return needTall
        }

        private fun shiftLabel(view: TextView, left: Int, width: Int): Boolean {
            val focused = view.hasFocus()
            view.maxLines = 1
            view.isSingleLine = true
            view.ellipsize = if (focused) {
                android.text.TextUtils.TruncateAt.MARQUEE
            } else {
                android.text.TextUtils.TruncateAt.END
            }
            view.isSelected = focused
            view.translationX = 0f
            if (width <= 0) {
                view.setPaddingRelative(labelPad, 0, labelPad, 0)
                return false
            }
            val visibleLeft = maxOf(left, 0)
            val overflow = if (scrollX > visibleLeft) (scrollX - visibleLeft) else 0
            val maxShift = (width - labelMinVisible - labelPad).coerceAtLeast(0)
            val start = labelPad + overflow.coerceAtMost(maxShift)
            if (view.paddingStart != start || view.paddingEnd != labelPad) {
                view.setPaddingRelative(start, 0, labelPad, 0)
            }
            return false
        }

        private fun bindBlock(
            view: TextView,
            channel: Channel,
            program: EpgProgram?,
            title: String,
            time: String?,
            left: Int,
            width: Int,
            slot: Slot
        ) {
            val hadFocus = view.hasFocus()
            val isChannelFallback = program != null && isChannelFiller(program.title, com.olympus.iptv.data.NameStrip.shown(channel.name, channel.groupTitle))
            val kind = if (program != null && !isChannelFallback) EpgProgramStyle.kind(program, channel) else EpgKind.OTHER
            view.tag = slot
            view.background = backgroundFor(kind, focused = false)
            view.setTextColor(primary)
            val catchupPast = program != null &&
                program.endMillis < System.currentTimeMillis() &&
                channel.supportsCatchup()
            view.text = label(sportsTitle(title, channel, program), time, kind, false, catchupPast, plain = !time.isNullOrBlank())
            val layout = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            layout.width = width
            layout.height = ViewGroup.LayoutParams.MATCH_PARENT
            layout.leftMargin = left
            layout.topMargin = dp(6)
            layout.bottomMargin = dp(6)
            view.layoutParams = layout
            shiftLabel(view, left, width)
            view.setOnClickListener { onPlay(channel, program) }
            view.setOnLongClickListener {
                onHold(channel, program)
                true
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                view.background = backgroundFor(kind, hasFocus)
                view.setTextColor(if (hasFocus) ink else primary)
                view.text = label(sportsTitle(title, channel, program), time, kind, hasFocus, catchupPast, plain = !time.isNullOrBlank())
                view.ellipsize = if (hasFocus) {
                    android.text.TextUtils.TruncateAt.MARQUEE
                } else {
                    android.text.TextUtils.TruncateAt.END
                }
                view.isSelected = hasFocus
                if (hasFocus) {
                    reveal(view)
                    onFocus(channel, program)
                }
            }
            if (hadFocus) {
                view.background = backgroundFor(kind, true)
                view.setTextColor(ink)
                view.text = label(sportsTitle(title, channel, program), time, kind, true, catchupPast, plain = !time.isNullOrBlank())
                view.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                view.isSelected = true
            }
        }

        private fun sportsTitle(title: String, channel: Channel, program: EpgProgram?): String {
            val clean = com.olympus.iptv.data.NameStrip.shown(title, channel.groupTitle)
                .trim(' ', ':', '|', '-', '–', '—')
                .ifBlank { title.trim(' ', ':', '|', '-', '–', '—') }
            if (program == null) return clean
            if (program.guideTitle.isNotEmpty()) {
                val stripped = program.guideTitle.replace(LIVE_EVENT_PREFIX, "")
                return com.olympus.iptv.data.NameStrip.shown(stripped, channel.groupTitle)
                    .trim(' ', ':', '|', '-', '–', '—')
                    .ifBlank { stripped.trim(' ', ':', '|', '-', '–', '—') }
            }
            val isFallback = isChannelFiller(title, com.olympus.iptv.data.NameStrip.shown(channel.name, channel.groupTitle))
            if (isFallback) return clean
            val now = System.currentTimeMillis()
            val timing = EpgTiming.tag(now, program.startMillis, program.endMillis)
            if (timing.isNullOrBlank()) return clean
            return "$timing · $clean"
        }

        private fun label(
            title: String,
            time: String?,
            kind: EpgKind,
            focused: Boolean,
            catchupPast: Boolean = false,
            plain: Boolean = false
        ): CharSequence {
            val pill = EpgProgramStyle.pill(kind)
            val headline = buildString {
                if (catchupPast) append("↺ ")
                append(title)
                if (pill != null) append(" · ").append(pill.uppercase(Locale.US))
            }
            if (time.isNullOrBlank()) {
                if (pill == null) return headline
                val span = SpannableString(headline)
                val tagStart = headline.length - pill.length
                span.setSpan(AbsoluteSizeSpan(11, true), tagStart, headline.length, 0)
                span.setSpan(
                    ForegroundColorSpan(if (focused) ink else EpgProgramStyle.stroke(itemView.context, kind, false)),
                    tagStart,
                    headline.length,
                    0
                )
                return span
            }
            if (plain) return "$headline\n$time"
            val text = SpannableString("$headline\n$time")
            val start = headline.length + 1
            text.setSpan(AbsoluteSizeSpan(12, true), start, text.length, 0)
            text.setSpan(
                ForegroundColorSpan(if (focused) ink else secondary),
                start,
                text.length,
                0
            )
            if (pill != null) {
                val tagStart = headline.length - pill.length
                text.setSpan(AbsoluteSizeSpan(11, true), tagStart, headline.length, 0)
                text.setSpan(
                    ForegroundColorSpan(if (focused) ink else EpgProgramStyle.stroke(itemView.context, kind, false)),
                    tagStart,
                    headline.length,
                    0
                )
            }
            return text
        }

        private fun backgroundFor(kind: EpgKind, focused: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(EpgProgramStyle.fill(itemView.context, kind, focused))
                setStroke(dp(1), EpgProgramStyle.stroke(itemView.context, kind, focused))
            }
        }

        private fun reveal(target: View) {
            val width = scroll.width
            if (width <= 0) return
            val left = target.left
            val right = target.right
            val shownLeft = scroll.scrollX
            val shownRight = shownLeft + width
            val pad = dp(16)
            val next = when {
                left >= shownLeft && right <= shownRight -> shownLeft
                left < shownLeft -> (left - pad).coerceAtLeast(0)
                right > shownRight && right - left >= width -> (left - pad).coerceAtLeast(0)
                right > shownRight -> (right - width + pad).coerceAtLeast(0)
                else -> shownLeft
            }
            if (next != shownLeft) onScroll(next)
        }
    }

    private data class Block(val program: EpgProgram, val left: Int, val width: Int)

    companion object {
        const val VIEW_PROGRAM = 20
        private val LIVE_EVENT_PREFIX = Regex("""(?i)^LIVE EVENT(?:\s*·\s*\d+m\s+left)?\s*(?:·\s*)?""")
        private val wordBreaks = Regex("[^a-z0-9]+")
        private val fillerWords = setOf(
            "programming", "program", "programs", "programmes", "programme",
            "tv", "channel", "live", "hd", "sd", "network", "feed"
        )
        private val emptyGuide = setOf(
            "no epg", "no epg data", "no data", "no information", "no info",
            "no program", "no programme", "no programs", "n a", "na", "tbd", "tba", "none"
        )
    }
}
