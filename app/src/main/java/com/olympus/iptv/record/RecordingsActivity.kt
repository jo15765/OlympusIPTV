package com.olympus.iptv.record

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Recording
import com.olympus.iptv.data.SeriesWatch
import com.olympus.iptv.databinding.ActivityRecordingsBinding
import com.olympus.iptv.ui.player.PlayerActivity
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var playlistId: String
    private var rows: List<Row> = emptyList()

    private sealed class Row {
        data class Watch(val watch: SeriesWatch) : Row()
        data class Item(val recording: Recording) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val book = withContext(Dispatchers.IO) { app.playlistStore.recordingBook(playlistId) }
            val watches = book.seriesOrEmpty()
            val items = book.itemsOrEmpty().sortedByDescending { it.start() }
            rows = watches.map { Row.Watch(it) } + items.map { Row.Item(it) }
            binding.emptyText.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            paintStorage()
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val labels = rows.map { row ->
                when (row) {
                    is Row.Watch -> getString(
                        R.string.record_series_watching,
                        "${row.watch.channelName.orEmpty()} · ${row.watch.title.orEmpty()}"
                    )
                    is Row.Item -> {
                        val status = statusLabel(row.recording.status)
                        val whenLabel = format.format(Date(row.recording.start()))
                        "$status  ${row.recording.channelName.orEmpty()}  ${row.recording.title.orEmpty()}  $whenLabel"
                    }
                }
            }
            binding.list.adapter = RecordingRows(labels) { index -> open(rows[index]) }
        }
    }

    private fun paintStorage() {
        val dir = RecordingFiles.directory(this)
        val used = RecordingFiles.usedBytes(this)
        val free = dir.usableSpace
        binding.storageGauge.text = getString(
            R.string.record_storage,
            formatBytes(used),
            formatBytes(free)
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index.coerceAtLeast(0)])
    }

    private fun open(row: Row) {
        when (row) {
            is Row.Watch -> openWatch(row.watch)
            is Row.Item -> openItem(row.recording)
        }
    }

    private fun openWatch(watch: SeriesWatch) {
        AlertDialog.Builder(this)
            .setTitle(watch.title.orEmpty())
            .setItems(arrayOf(getString(R.string.record_series_stop))) { _, _ -> stopWatch(watch) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openItem(item: Recording) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (item.status == Recording.DONE || item.status == Recording.STOPPED || item.status == Recording.RECORDING) {
            actions.add(getString(R.string.record_play) to { play(item) })
        }
        if (item.status == Recording.RECORDING || item.status == Recording.SCHEDULED) {
            actions.add(getString(R.string.record_stop) to { stop(item) })
        }
        actions.add(getString(R.string.delete) to { confirmDelete(item) })
        AlertDialog.Builder(this)
            .setTitle(item.title.orEmpty())
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun stopWatch(watch: SeriesWatch) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val book = app.playlistStore.recordingBook(playlistId)
                val next = book.copy(
                    series = book.seriesOrEmpty().filterNot {
                        it.channelId == watch.channelId &&
                            com.olympus.iptv.data.ShowNames.same(it.title.orEmpty(), watch.title.orEmpty())
                    },
                    revision = System.currentTimeMillis()
                )
                app.playlistStore.saveRecordingBook(playlistId, next)
                RecordPrompt.push(app, playlistId, next)
            }
            reload()
        }
    }

    private fun play(item: Recording) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val book = app.playlistStore.recordingBook(playlistId)
                RecordingPlayback.uri(this@RecordingsActivity, item, book.smbOrEmpty())
            }
            if (uri == null) {
                Toast.makeText(this@RecordingsActivity, R.string.error_playback, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val channel = Channel(
                id = item.id.orEmpty(),
                name = item.title.orEmpty(),
                streamUrl = uri.toString(),
                kind = ContentKind.LIVE
            )
            app.playbackQueue = listOf(channel)
            startActivity(
                PlayerActivity.intent(
                    this@RecordingsActivity,
                    channel = channel,
                    channelMeta = item.channelName.orEmpty(),
                    channelNext = "",
                    startIndex = 0,
                    playlistId = playlistId,
                    zapMode = PlayerActivity.ZAP_LIST
                )
            )
        }
    }

    private fun stop(item: Recording) {
        update(item.id.orEmpty()) { it.copy(status = Recording.STOPPED) }
    }

    private fun confirmDelete(item: Recording) {
        AlertDialog.Builder(this)
            .setTitle(R.string.record_delete_title)
            .setPositiveButton(R.string.delete) { _, _ ->
                val app = application as OlympusIptvApp
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val book = app.playlistStore.recordingBook(playlistId)
                        val name = item.fileName
                        if (!name.isNullOrBlank()) {
                            if (item.destination == Recording.SMB) runCatching { SmbFiles.delete(book.smbOrEmpty(), name) }
                            else {
                                RecordingFiles.file(this@RecordingsActivity, name).delete()
                                RecordingFiles.invalidateSize()
                            }
                        }
                        val next = book.copy(
                            items = book.itemsOrEmpty().filterNot { it.id == item.id },
                            revision = System.currentTimeMillis()
                        )
                        app.playlistStore.saveRecordingBook(playlistId, next)
                        RecordPrompt.push(app, playlistId, next)
                    }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun update(id: String, change: (Recording) -> Recording) {
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val book = app.playlistStore.recordingBook(playlistId)
                val next = book.copy(
                    items = book.itemsOrEmpty().map { if (it.id == id) change(it) else it },
                    revision = System.currentTimeMillis()
                )
                app.playlistStore.saveRecordingBook(playlistId, next)
                RecordPrompt.push(app, playlistId, next)
            }
            reload()
        }
    }

    private fun statusLabel(status: String?): String = when (status) {
        Recording.RECORDING -> getString(R.string.record_status_recording)
        Recording.DONE -> getString(R.string.record_status_done)
        Recording.FAILED -> getString(R.string.record_status_failed)
        Recording.MISSED -> getString(R.string.record_status_missed)
        Recording.STOPPED -> getString(R.string.record_status_stopped)
        else -> getString(R.string.record_status_scheduled)
    }

    companion object {
        private const val EXTRA_PLAYLIST = "playlist_id"
        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, RecordingsActivity::class.java).putExtra(EXTRA_PLAYLIST, playlistId)
    }
}

private class RecordingRows(
    private val labels: List<String>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RecordingRows.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_record_row, parent, false) as TextView
        return Holder(view)
    }

    override fun getItemCount(): Int = labels.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.label.text = labels[position]
        holder.label.setOnClickListener { onClick(position) }
    }

    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
}
