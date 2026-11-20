package com.olympus.iptv.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.os.Build
import com.olympus.iptv.data.Channel
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.browse.BrowseActivity

/** Publishes the current title into the Android TV home Watch Next row. */
object WatchNext {
    fun publish(context: Context, channel: Channel, playlistId: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!DeviceForm.isTelevision(context)) return
        if (channel.name.isBlank()) return
        val app = context.applicationContext
        val intent = Intent(app, BrowseActivity::class.java)
            .putExtra(BrowseActivity.EXTRA_PLAYLIST_ID, playlistId.orEmpty())
            .putExtra(BrowseActivity.EXTRA_SKIP_RESUME, true)
        val values = ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, channel.name)
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, channel.id)
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_CHANNEL)
            put(
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            )
            put(TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1)
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intent.toUri(Intent.URI_INTENT_SCHEME))
            channel.logoUrl?.takeIf { it.isNotBlank() }?.let {
                put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it)
            }
        }
        runCatching {
            val resolver = app.contentResolver
            val uri = TvContract.WatchNextPrograms.CONTENT_URI
            val existing = resolver.query(
                uri,
                arrayOf(TvContract.WatchNextPrograms._ID),
                "${TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID}=?",
                arrayOf(channel.id),
                null
            )
            val id = existing?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            if (id == null) {
                resolver.insert(uri, values)
            } else {
                resolver.update(
                    TvContract.buildWatchNextProgramUri(id),
                    values,
                    null,
                    null
                )
            }
        }
    }
}
