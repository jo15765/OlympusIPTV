package com.olympus.iptv.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.olympus.iptv.ui.channelLogo
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.StreamQuality

/** Left-hand channel list drawn over the live picture. The stream keeps playing. */
class PlayerMiniGuide(
    private val panel: View,
    private val list: RecyclerView,
    private val channels: () -> List<Channel>,
    private val onTune: (Int) -> Unit,
    private val nowTitle: (Channel) -> String? = { null },
    private val progress: (Channel) -> Int = { 0 },
    private val sameShow: (Channel) -> Boolean = { false }
) {
    private val rows = ArrayList<Channel>()
    private var selected = 0
    private var playingId: String? = null
    private val adapter = Adapter()

    init {
        val lm = LinearLayoutManager(list.context).apply { initialPrefetchItemCount = 6 }
        list.layoutManager = lm
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(8)
    }

    fun isOpen(): Boolean = panel.visibility == View.VISIBLE

    fun show(currentId: String?) {
        rows.clear()
        rows.addAll(channels().sortedByDescending { sameShow(it) && it.id != currentId })
        if (rows.isEmpty()) return
        selected = rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        playingId = currentId
        adapter.notifyDataSetChanged()
        panel.visibility = View.VISIBLE
        list.scrollToPosition(selected)
        list.post {
            list.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus()
        }
    }

    fun hide() {
        panel.visibility = View.GONE
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        val prev = selected
        selected = (selected + delta).floorMod(rows.size)
        if (prev != selected) {
            adapter.notifyItemChanged(prev, PAYLOAD_ACTIVE)
            adapter.notifyItemChanged(selected, PAYLOAD_ACTIVE)
        }
        list.scrollToPosition(selected)
        list.post {
            list.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus()
        }
    }

    fun refresh() {
        adapter.notifyDataSetChanged()
    }

    fun tune() {
        if (selected !in rows.indices) return
        val index = selected
        hide()
        onTune(index)
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long =
            rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mini_guide, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position], position == selected)
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_ACTIVE)) {
                holder.itemView.isSelected = (position == selected)
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val label: TextView = view.findViewById(R.id.miniGuideName)
            private val show: TextView = view.findViewById(R.id.miniGuideShow)
            private val logo: ImageView = view.findViewById(R.id.miniGuideLogo)
            private val bar: ProgressBar = view.findViewById(R.id.miniGuideProgress)
            private val badge: TextView = view.findViewById(R.id.miniGuideBadge)
            private val bars: android.widget.LinearLayout = view.findViewById(R.id.miniLiveBars)
            private val logoPx = (48 * logo.resources.displayMetrics.density).toInt().coerceAtLeast(1)

            init {
                itemView.setOnClickListener {
                    val index = bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    selected = index
                    tune()
                }
            }

            fun bind(channel: Channel, active: Boolean) {
                label.text = com.olympus.iptv.data.NameStrip.shown(channel.name, channel.groupTitle)
                val title = nowTitle(channel)
                val same = sameShow(channel) && channel.id != playingId
                show.text = if (same && !title.isNullOrBlank()) {
                    itemView.context.getString(R.string.same_show) + " · " + title
                } else {
                    title.orEmpty()
                }
                show.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE
                val fraction = progress(channel)
                bar.progress = fraction
                bar.visibility = if (fraction <= 0) View.GONE else View.VISIBLE
                val mark = StreamQuality.badge(channel.name)
                badge.text = mark.orEmpty()
                badge.visibility = if (mark.isNullOrBlank()) View.GONE else View.VISIBLE
                com.olympus.iptv.ui.LiveBars.show(bars, channel.id == playingId)
                val url = channel.logoUrl.orEmpty()
                if (logo.getTag(R.id.miniGuideLogo) != url || logo.drawable == null) {
                    logo.setTag(R.id.miniGuideLogo, url)
                    logo.load(channel.logoUrl) {
                        channelLogo(logoPx)
                        scale(coil.size.Scale.FIT)
                        placeholder(R.drawable.ic_channel_placeholder)
                        error(R.drawable.ic_channel_placeholder)
                    }
                }
                itemView.isSelected = active
            }
        }
    }

    private companion object {
        private const val PAYLOAD_ACTIVE = "active"
    }
}
