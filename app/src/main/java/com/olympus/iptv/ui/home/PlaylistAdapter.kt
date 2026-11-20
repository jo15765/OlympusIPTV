package com.olympus.iptv.ui.home

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.R
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onOpen: (SavedPlaylist) -> Unit,
    private val onDelete: (SavedPlaylist) -> Unit,
    private val onMove: (SavedPlaylist, Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    private val items = mutableListOf<SavedPlaylist>()

    fun submit(list: List<SavedPlaylist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], position, items.size)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SavedPlaylist, position: Int, total: Int) {
            binding.name.text = item.name
            binding.typeBadge.text = when (item.type) {
                PlaylistType.M3U -> binding.root.context.getString(R.string.type_m3u)
                PlaylistType.XTREAM -> binding.root.context.getString(R.string.type_xtream)
            }
            binding.meta.text = when (item.type) {
                PlaylistType.M3U -> item.m3uUrl.orEmpty()
                PlaylistType.XTREAM -> "${item.xtreamHost.orEmpty()} · ${item.xtreamUsername.orEmpty()}"
            }
            binding.btnMoveUp.isEnabled = position > 0
            binding.btnMoveDown.isEnabled = position < total - 1
            binding.btnMoveUp.alpha = if (position > 0) 1f else 0.35f
            binding.btnMoveDown.alpha = if (position < total - 1) 1f else 0.35f

            binding.root.setOnClickListener { onOpen(item) }
            binding.btnMoveUp.setOnClickListener { onMove(item, -1) }
            binding.btnMoveDown.setOnClickListener { onMove(item, 1) }
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                        onOpen(item)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onMove(item, -1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onMove(item, 1)
                        true
                    }
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_MEDIA_STOP -> {
                        onDelete(item)
                        true
                    }
                    else -> false
                }
            }
            binding.root.setOnLongClickListener {
                onDelete(item)
                true
            }
        }
    }
}
