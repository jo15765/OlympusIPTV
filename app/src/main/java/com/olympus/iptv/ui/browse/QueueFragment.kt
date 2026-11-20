package com.olympus.iptv.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.QueueItem
import com.olympus.iptv.data.Titles
import com.olympus.iptv.databinding.ActivityQueueBinding
import com.olympus.iptv.databinding.ItemQueueBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QueueFragment : Fragment() {
    private var _binding: ActivityQueueBinding? = null
    private val binding get() = _binding!!
    private val playlistId by lazy { arguments?.getString(EXTRA_PLAYLIST_ID).orEmpty() }
    private val rows = ArrayList<QueueItem>()
    private val adapter = QueueAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (playlistId.isBlank()) return
        val touch = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(requireContext())
        if (touch) {
            binding.queueList.isNestedScrollingEnabled = true
        }
        val span = if (touch) 2 else 5
        binding.queueList.layoutManager = GridLayoutManager(requireContext(), span)
        binding.queueList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (playlistId.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                (requireActivity().application as OlympusIptvApp).playlistStore.queue(playlistId)
            }
            if (!isAdded || _binding == null) return@launch
            rows.clear()
            rows.addAll(saved)
            adapter.notifyDataSetChanged()
            binding.queueStatus.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun open(item: QueueItem) {
        val app = requireActivity().application as OlympusIptvApp
        viewLifecycleOwner.lifecycleScope.launch {
            val channel = withContext(Dispatchers.IO) {
                app.catalogDb.readChannel(playlistId, item.bucket, item.id)
            } ?: Channel(
                id = item.id,
                name = item.name,
                streamUrl = "",
                logoUrl = item.logoUrl,
                kind = ContentKind.VOD,
                seriesId = if (item.bucket == CatalogDb.SERIES) item.seriesId ?: item.id else item.seriesId
            )
            if (item.bucket == CatalogDb.SERIES || !channel.seriesId.isNullOrBlank()) {
                startActivity(EpisodesActivity.intent(requireContext(), playlistId, channel))
                return@launch
            }
            if (channel.streamUrl.isBlank()) return@launch
            val startAt = rows.indexOfFirst { it.bucket == item.bucket && it.id == item.id }.coerceAtLeast(0)
            val playback = withContext(Dispatchers.IO) {
                rows.drop(startAt).mapNotNull { row ->
                    if (row.bucket == CatalogDb.SERIES) return@mapNotNull null
                    val loaded = app.catalogDb.readChannel(playlistId, row.bucket, row.id) ?: return@mapNotNull null
                    if (!loaded.seriesId.isNullOrBlank() || loaded.streamUrl.isBlank()) null else loaded
                }
            }
            if (playback.isEmpty()) return@launch
            app.playbackQueue = playback
            startActivity(
                PlayerActivity.intent(
                    requireContext(),
                    channel = playback.first(),
                    channelMeta = "",
                    channelNext = "",
                    startIndex = 0,
                    playlistId = playlistId,
                    zapMode = PlayerActivity.ZAP_LIST
                )
            )
        }
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        override fun getItemCount(): Int = rows.size

        inner class Holder(private val row: ItemQueueBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(item: QueueItem) {
                row.queueName.text = Titles.clean(item.name)
                if (item.logoUrl.isNullOrBlank()) {
                    row.queueArt.setImageResource(R.drawable.ic_channel_placeholder)
                } else {
                    val density = row.queueArt.resources.displayMetrics.density
                    row.queueArt.load(item.logoUrl) {
                        size((104 * density).toInt(), (156 * density).toInt())
                        scale(Scale.FIT)
                        placeholder(R.drawable.ic_channel_placeholder)
                        error(R.drawable.ic_channel_placeholder)
                    }
                }
                row.root.setOnClickListener { open(item) }
                row.root.setOnLongClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(Titles.clean(item.name))
                        .setItems(arrayOf(getString(R.string.queue_remove))) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    (requireActivity().application as OlympusIptvApp).playlistStore.removeQueue(
                                        playlistId,
                                        item.bucket,
                                        item.id
                                    )
                                }
                                val index = rows.indexOfFirst { it.bucket == item.bucket && it.id == item.id }
                                if (index >= 0) {
                                    rows.removeAt(index)
                                    notifyItemRemoved(index)
                                }
                                _binding?.queueStatus?.visibility =
                                    if (rows.isEmpty()) View.VISIBLE else View.GONE
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            }
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun newInstance(playlistId: String): QueueFragment =
            QueueFragment().apply {
                arguments = Bundle().apply { putString(EXTRA_PLAYLIST_ID, playlistId) }
            }
    }
}
