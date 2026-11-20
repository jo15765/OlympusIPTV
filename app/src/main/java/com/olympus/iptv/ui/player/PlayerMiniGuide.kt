package com.olympus.iptv.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel

/** Left-hand channel list drawn over the live picture. The stream keeps playing. */
class PlayerMiniGuide(
    private val panel: View,
    private val list: RecyclerView,
    private val channels: () -> List<Channel>,
    private val onTune: (Int) -> Unit
) {
    private val rows = ArrayList<Channel>()
    private var selected = 0
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
            fun bind(channel: Channel, active: Boolean) {
                label.text = channel.name
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
