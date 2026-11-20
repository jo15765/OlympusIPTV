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
    private val progress: (Channel) -> Int = { 0 }
) {
    private val rows = ArrayList<Channel>()
    private var selected = 0
    private var playingId: String? = null
    private val adapter = Adapter()

    init {
        list.layoutManager = LinearLayoutManager(list.context)
        list.adapter = adapter
        list.itemAnimator = null
    }

    fun isOpen(): Boolean = panel.visibility == View.VISIBLE

    fun show(currentId: String?) {
        rows.clear()
        rows.addAll(channels())
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
        selected = (selected + delta).floorMod(rows.size)
        adapter.notifyDataSetChanged()
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
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mini_guide, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position], position == selected)
        }

        override fun getItemCount(): Int = rows.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val label: TextView = view.findViewById(R.id.miniGuideName)
            private val show: TextView = view.findViewById(R.id.miniGuideShow)
            private val logo: ImageView = view.findViewById(R.id.miniGuideLogo)
            private val bar: ProgressBar = view.findViewById(R.id.miniGuideProgress)
            private val badge: TextView = view.findViewById(R.id.miniGuideBadge)
            private val bars: android.widget.LinearLayout = view.findViewById(R.id.miniLiveBars)
            fun bind(channel: Channel, active: Boolean) {
                label.text = channel.name
                val title = nowTitle(channel)
                show.text = title.orEmpty()
                show.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE
                val fraction = progress(channel)
                bar.progress = fraction
                bar.visibility = if (fraction <= 0) View.GONE else View.VISIBLE
                val mark = StreamQuality.badge(channel.name)
                badge.text = mark.orEmpty()
                badge.visibility = if (mark.isNullOrBlank()) View.GONE else View.VISIBLE
                com.olympus.iptv.ui.LiveBars.show(bars, channel.id == playingId)
                logo.load(channel.logoUrl) {
                    placeholder(R.drawable.ic_channel_placeholder)
                    error(R.drawable.ic_channel_placeholder)
                }
                itemView.isSelected = active
                itemView.setOnClickListener {
                    val index = bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    selected = index
                    tune()
                }
            }
        }
    }
}
