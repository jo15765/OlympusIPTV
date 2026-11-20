package com.olympus.iptv.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.os.Build
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.browse.BrowseActivity

/** Android TV home row: continue watching, plus a Favorites channel of live stations. */
object TvLauncherBridge {
    private const val FAVORITES_ROW = "olympus-favorites"

    fun publishContinue(
        context: Context,
        channel: Channel,
        playlistId: String?,
        positionMs: Long,
        durationMs: Long
    ) {
        if (!ready(context) || channel.name.isBlank()) return
        val app = context.applicationContext
        val values = ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, channel.name)
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, channel.id)
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, programType(channel))
            put(
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            )
            put(TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1)
            put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, positionMs.coerceAtLeast(0L))
            if (durationMs > 0L) {
                put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, durationMs)
            }
            put(
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                launchIntent(app, playlistId, channel.id).toUri(Intent.URI_INTENT_SCHEME)
            )
            channel.logoUrl?.takeIf { it.isNotBlank() }?.let {
                put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it)
            }
        }
        runCatching { upsertWatchNext(app, channel.id, values) }
    }

    suspend fun refreshFavorites(app: OlympusIptvApp, playlistId: String) {
        if (!ready(app) || playlistId.isBlank()) return
        runCatching {
            val keys = app.playlistStore.favorites(playlistId)
            val ids = keys.mapNotNull { key ->
                if (Favorites.inSection(key, Favorites.LIVE)) key.substringAfter(':') else null
            }.distinct().take(8)
            val channels = app.catalogDb.readChannelsByIds(playlistId, CatalogDb.LIVE, ids)
            val byId = channels.associateBy { it.id }
            val ordered = ids.mapNotNull { byId[it] }.filter { it.streamUrl.isNotBlank() }
            publishFavoriteRow(app, playlistId, ordered)
        }
    }

    private fun publishFavoriteRow(context: Context, playlistId: String, channels: List<Channel>) {
        val resolver = context.contentResolver
        val channelId = ensureFavoriteRow(context, playlistId) ?: return
        val programs = TvContract.buildPreviewProgramsUriForChannel(channelId)
        resolver.delete(programs, null, null)
        for (channel in channels) {
            val values = ContentValues().apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
                put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL)
                put(TvContract.PreviewPrograms.COLUMN_TITLE, channel.name)
                put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, channel.id)
                put(TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1)
                put(
                    TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                    launchIntent(context, playlistId, channel.id).toUri(Intent.URI_INTENT_SCHEME)
                )
                channel.logoUrl?.takeIf { it.isNotBlank() }?.let {
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, it)
                }
            }
            runCatching { resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values) }
        }
    }

    private fun ensureFavoriteRow(context: Context, playlistId: String): Long? {
        val resolver = context.contentResolver
        val existing = resolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(TvContract.Channels._ID),
            "${TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID}=?",
            arrayOf(FAVORITES_ROW),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (existing != null) return existing
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Favorites")
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, FAVORITES_ROW)
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, launchIntent(context, playlistId, null).toUri(Intent.URI_INTENT_SCHEME))
        }
        val uri = resolver.insert(TvContract.Channels.CONTENT_URI, values) ?: return null
        val id = android.content.ContentUris.parseId(uri)
        runCatching { TvContract.requestChannelBrowsable(context, id) }
        return id
    }

    private fun upsertWatchNext(context: Context, providerId: String, values: ContentValues) {
        val resolver = context.contentResolver
        val uri = TvContract.WatchNextPrograms.CONTENT_URI
        val existing = resolver.query(
            uri,
            arrayOf(TvContract.WatchNextPrograms._ID),
            "${TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID}=?",
            arrayOf(providerId),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (existing == null) resolver.insert(uri, values)
        else resolver.update(TvContract.buildWatchNextProgramUri(existing), values, null, null)
    }

    private fun launchIntent(context: Context, playlistId: String?, channelId: String?): Intent =
        Intent(context, BrowseActivity::class.java)
            .putExtra(BrowseActivity.EXTRA_PLAYLIST_ID, playlistId.orEmpty())
            .putExtra(BrowseActivity.EXTRA_TUNE_ID, channelId.orEmpty())
            .putExtra(BrowseActivity.EXTRA_SKIP_RESUME, true)

    private fun programType(channel: Channel): Int = when {
        !channel.seriesId.isNullOrBlank() -> TvContract.WatchNextPrograms.TYPE_TV_EPISODE
        channel.kind == ContentKind.LIVE -> TvContract.WatchNextPrograms.TYPE_CHANNEL
        else -> TvContract.WatchNextPrograms.TYPE_MOVIE
    }

    private fun ready(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && DeviceForm.isTelevision(context)
}
