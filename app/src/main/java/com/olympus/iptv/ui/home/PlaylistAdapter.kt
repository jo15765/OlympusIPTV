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
    private val onDelete: (SavedPlaylist) -> Unit
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
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SavedPlaylist) {
            binding.name.text = item.name
            binding.typeBadge.text = when (item.type) {
                PlaylistType.M3U -> binding.root.context.getString(R.string.type_m3u)
                PlaylistType.XTREAM -> binding.root.context.getString(R.string.type_xtream)
            }
            binding.meta.text = when (item.type) {
                PlaylistType.M3U -> item.m3uUrl.orEmpty()
                PlaylistType.XTREAM -> "${item.xtreamHost.orEmpty()} · ${item.xtreamUsername.orEmpty()}"
            }

            binding.root.setOnClickListener { onOpen(item) }
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    onOpen(item)
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_MEDIA_STOP)
                ) {
                    onDelete(item)
                    true
                } else {
                    false
                }
            }
            binding.root.setOnLongClickListener {
                onDelete(item)
                true
            }
        }
    }
}
