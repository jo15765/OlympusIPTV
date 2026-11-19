package com.olympus.iptv.ui.browse

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.QueueItem
import com.olympus.iptv.data.Titles
import com.olympus.iptv.playlist.CatalogDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WatchQueue {
    fun itemFor(channel: Channel, shows: Boolean): QueueItem? {
        val bucket = when {
            shows || !channel.seriesId.isNullOrBlank() -> CatalogDb.SERIES
            channel.kind == ContentKind.VOD -> CatalogDb.VOD
            else -> return null
        }
        return QueueItem(
            bucket = bucket,
            id = channel.id,
            name = channel.name,
            logoUrl = channel.logoUrl,
            seriesId = channel.seriesId
        )
    }

    fun offer(activity: AppCompatActivity, playlistId: String, item: QueueItem) {
        val app = activity.application as OlympusIptvApp
        activity.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                app.playlistStore.queue(playlistId).any { it.bucket == item.bucket && it.id == item.id }
            }
            if (activity.isFinishing) return@launch
            val label = activity.getString(if (existing) R.string.queue_remove else R.string.queue_add)
            AlertDialog.Builder(activity)
                .setTitle(Titles.clean(item.name))
                .setItems(arrayOf(label)) { _, _ ->
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        if (existing) app.playlistStore.removeQueue(playlistId, item.bucket, item.id)
                        else app.playlistStore.addQueue(playlistId, item)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
