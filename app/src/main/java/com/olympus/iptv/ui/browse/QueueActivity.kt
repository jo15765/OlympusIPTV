package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

class QueueActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQueueBinding
    private val playlistId by lazy { intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty() }
    private val rows = ArrayList<QueueItem>()
    private val adapter = QueueAdapter()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val touch = com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)
        if (touch) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.root,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
            binding.queueList.isNestedScrollingEnabled = true
        }
        val span = if (touch) 2 else 5
        binding.queueList.layoutManager = GridLayoutManager(this, span)
        binding.queueList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.queue(playlistId)
            }
            if (isFinishing) return@launch
            rows.clear()
            rows.addAll(saved)
            adapter.notifyDataSetChanged()
            binding.queueStatus.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(item: QueueItem) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
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
                startActivity(EpisodesActivity.intent(this@QueueActivity, playlistId, channel))
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
                    this@QueueActivity,
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
                    AlertDialog.Builder(this@QueueActivity)
                        .setTitle(Titles.clean(item.name))
                        .setItems(arrayOf(getString(R.string.queue_remove))) { _, _ ->
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    (application as OlympusIptvApp).playlistStore.removeQueue(
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
                                binding.queueStatus.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
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
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, QueueActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
