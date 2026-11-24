package com.olympus.iptv.record

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.data.Recording
import com.olympus.iptv.data.RecordingBook
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.data.SeriesWatch
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.LineupClient
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RecordPrompt {
    private data class Choice(val program: EpgProgram, val series: Boolean)

    fun show(activity: AppCompatActivity, playlistId: String, channel: Channel, now: EpgProgram?, next: EpgProgram?) {
        val choices = buildList {
            if (now != null) add(activity.getString(R.string.record_now, now.title) to Choice(now, series = false))
            if (next != null) add(activity.getString(R.string.record_next, next.title) to Choice(next, series = false))
            val seed = now ?: next
            if (seed != null) add(activity.getString(R.string.record_series) to Choice(seed, series = true))
        }
        if (choices.isEmpty()) {
            Toast.makeText(activity, R.string.record_no_program, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.record_pick)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which ->
                val choice = choices[which].second
                askDestination(activity, playlistId, channel, choice.program, choice.series)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        com.olympus.iptv.ui.DialogChrome.dress(dialog)
        dialog.show()
    }

    fun askDestination(
        activity: AppCompatActivity,
        playlistId: String,
        channel: Channel,
        program: EpgProgram,
        series: Boolean = false
    ) {
        activity.lifecycleScope.launch {
            val app = activity.application as OlympusIptvApp
            val (localLabel, smbLabel) = withContext(Dispatchers.IO) {
                val book = app.playlistStore.recordingBook(playlistId)
                val cloud = book.cloudDriveOrEmpty()
                val smb = book.smbOrEmpty()
                val freeBytes = RecordingFiles.directory(activity).usableSpace
                val freeText = if (freeBytes > 0L) {
                    android.text.format.Formatter.formatShortFileSize(activity, freeBytes) + " free"
                } else {
                    ""
                }
                val baseLocal = activity.getString(R.string.record_local)
                val localText = when {
                    cloud.ready() -> {
                        val cloudName = if (cloud.normalizedProvider() == Recording.ONEDRIVE) "OneDrive" else "Google Drive"
                        if (freeText.isNotEmpty()) "$baseLocal + $cloudName ($freeText)" else "$baseLocal + $cloudName"
                    }
                    freeText.isNotEmpty() -> "$baseLocal ($freeText)"
                    else -> baseLocal
                }
                val baseSmb = activity.getString(R.string.record_smb)
                val smbText = if (smb.ready()) {
                    "$baseSmb (${smb.host}/${smb.share})"
                } else {
                    "$baseSmb (Set up…)"
                }
                localText to smbText
            }
            if (activity.isDestroyed) return@launch
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.record_where)
                .setItems(arrayOf(localLabel, smbLabel)) { _, which ->
                    val destination = if (which == 0) Recording.LOCAL else Recording.SMB
                    askEndPad(activity, playlistId, channel, program, series, destination)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            com.olympus.iptv.ui.DialogChrome.dress(dialog)
            dialog.show()
        }
    }

    private fun askEndPad(
        activity: AppCompatActivity,
        playlistId: String,
        channel: Channel,
        program: EpgProgram,
        series: Boolean,
        destination: String
    ) {
        val minutes = intArrayOf(5, 15, 30)
        val labels = minutes.map { activity.getString(R.string.record_end_pad, it) }.toTypedArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.record_pad_title)
            .setItems(labels) { _, which ->
                val endPad = minutes[which] * 60_000L
                activity.lifecycleScope.launch {
                    prepareSchedule(
                        activity,
                        playlistId,
                        channel,
                        program,
                        series,
                        destination,
                        force = false,
                        startPadMs = 2 * 60_000L,
                        endPadMs = endPad
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        com.olympus.iptv.ui.DialogChrome.dress(dialog)
        dialog.show()
    }

    private suspend fun prepareSchedule(
        activity: AppCompatActivity,
        playlistId: String,
        channel: Channel,
        program: EpgProgram,
        series: Boolean,
        destination: String,
        force: Boolean,
        startPadMs: Long = 2 * 60_000L,
        endPadMs: Long = 5 * 60_000L
    ) {
        val app = activity.application as OlympusIptvApp
        val preview = withContext(Dispatchers.IO) {
            val book = app.playlistStore.recordingBook(playlistId)
            if (destination == Recording.SMB && !book.smbOrEmpty().ready()) {
                return@withContext Preview(shareNeeded = true)
            }
            val programs = if (series) {
                val playlist = savedPlaylist(app, playlistId)
                val episodes = if (playlist != null) {
                    app.epgRepository.sameShow(playlist, channel, program.title)
                } else {
                    emptyList()
                }
                episodes.ifEmpty { listOf(program) }
            } else {
                listOf(program)
            }
            val conflict = if (force) null else programs.firstNotNullOfOrNull { candidate ->
                overlapping(book, candidate)
            }
            Preview(programs = programs, conflict = conflict)
        }
        if (preview.shareNeeded) {
            activity.startActivity(SmbActivity.intent(activity, playlistId))
            Toast.makeText(activity, R.string.record_share_needed, Toast.LENGTH_SHORT).show()
            return
        }
        val conflict = preview.conflict
        if (conflict != null) {
            val overlap = AlertDialog.Builder(activity)
                .setTitle(R.string.record_overlap_title)
                .setMessage(
                    activity.getString(
                        R.string.record_overlap_message,
                        program.title,
                        conflict.title.orEmpty(),
                        conflict.channelName.orEmpty()
                    )
                )
                .setPositiveButton(R.string.record_overlap_anyway) { _, _ ->
                    activity.lifecycleScope.launch {
                        prepareSchedule(
                            activity, playlistId, channel, program, series, destination,
                            force = true, startPadMs = startPadMs, endPadMs = endPadMs
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            com.olympus.iptv.ui.DialogChrome.dress(overlap)
            overlap.show()
            return
        }
        val message = withContext(Dispatchers.IO) {
            if (series) {
                scheduleAll(app, playlistId, channel, preview.programs, destination, force, startPadMs, endPadMs)
            } else {
                schedule(app, playlistId, channel, program, destination, force, startPadMs, endPadMs)
            }
        }
        val failed = message == activity.getString(R.string.record_share_needed) ||
            message == activity.getString(R.string.record_overlap) ||
            message == activity.getString(R.string.record_no_program)
        if (!failed && destination == Recording.LOCAL) {
            val free = com.olympus.iptv.record.RecordingFiles.directory(activity).usableSpace
            if (free < 2L * 1024L * 1024L * 1024L) {
                Toast.makeText(activity, R.string.record_storage_low_warn, Toast.LENGTH_LONG).show()
            }
        }
        if (!failed) RecordingService.start(activity)
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private data class Preview(
        val programs: List<EpgProgram> = emptyList(),
        val conflict: Recording? = null,
        val shareNeeded: Boolean = false
    )

    private fun overlapping(book: RecordingBook, program: EpgProgram): Recording? {
        return book.itemsOrEmpty().firstOrNull { item ->
            item.status in setOf(Recording.SCHEDULED, Recording.RECORDING) &&
                program.startMillis < item.windowEnd() &&
                program.endMillis + Recording.PAD > item.windowStart()
        }
    }

    suspend fun schedule(
        app: OlympusIptvApp,
        playlistId: String,
        channel: Channel,
        program: EpgProgram,
        destination: String,
        force: Boolean = false,
        startPadMs: Long = 2 * 60_000L,
        endPadMs: Long = 5 * 60_000L
    ): String {
        val book = app.playlistStore.recordingBook(playlistId)
        if (destination == Recording.SMB && !book.smbOrEmpty().ready()) {
            return app.getString(R.string.record_share_needed)
        }
        if (!force && overlapping(book, program) != null) {
            return app.getString(R.string.record_overlap)
        }
        val recording = recording(channel, program, destination, startPadMs, endPadMs)
        val next = book.copy(
            items = book.itemsOrEmpty() + recording,
            revision = System.currentTimeMillis()
        )
        app.playlistStore.saveRecordingBook(playlistId, next)
        push(app, playlistId, next)
        return app.getString(R.string.record_saved, program.title)
    }

    suspend fun scheduleAll(
        app: OlympusIptvApp,
        playlistId: String,
        channel: Channel,
        programs: List<EpgProgram>,
        destination: String,
        force: Boolean = false,
        startPadMs: Long = 2 * 60_000L,
        endPadMs: Long = 5 * 60_000L
    ): String {
        if (programs.isEmpty()) return app.getString(R.string.record_no_program)
        val book = app.playlistStore.recordingBook(playlistId)
        if (destination == Recording.SMB && !book.smbOrEmpty().ready()) {
            return app.getString(R.string.record_share_needed)
        }
        val added = ArrayList<Recording>()
        val existing = book.itemsOrEmpty()
        for (program in programs) {
            val blocked = !force && (existing + added).any { item ->
                item.status in setOf(Recording.SCHEDULED, Recording.RECORDING) &&
                    program.startMillis < item.windowEnd() &&
                    program.endMillis + Recording.PAD > item.windowStart()
            }
            if (blocked) continue
            added += recording(channel, program, destination, startPadMs, endPadMs)
        }
        if (added.isEmpty()) return app.getString(R.string.record_overlap)
        val seed = programs.first().title
        val watches = book.seriesOrEmpty().filterNot {
            it.channelId == channel.id && com.olympus.iptv.data.ShowNames.same(it.title.orEmpty(), seed)
        } + SeriesWatch(
            channelId = channel.id,
            channelName = channel.name,
            title = seed,
            destination = destination
        )
        val next = book.copy(
            items = existing + added,
            series = watches,
            revision = System.currentTimeMillis()
        )
        app.playlistStore.saveRecordingBook(playlistId, next)
        push(app, playlistId, next)
        return if (added.size == 1) {
            app.getString(R.string.record_saved, added.first().title)
        } else {
            app.getString(R.string.record_series_saved, added.size)
        }
    }

    suspend fun pickupSeries(app: OlympusIptvApp, playlistId: String, book: RecordingBook): RecordingBook {
        val watches = book.seriesOrEmpty()
        if (watches.isEmpty()) return book
        val playlist = savedPlaylist(app, playlistId) ?: return book
        val added = ArrayList<Recording>()
        val existing = book.itemsOrEmpty()
        for (watch in watches) {
            val channelId = watch.channelId ?: continue
            val title = watch.title ?: continue
            val channel = app.catalogDb.readChannel(playlistId, CatalogDb.LIVE, channelId) ?: continue
            val destination = watch.destination ?: Recording.LOCAL
            if (destination == Recording.SMB && !book.smbOrEmpty().ready()) continue
            val programs = app.epgRepository.sameShow(playlist, channel, title)
            for (program in programs) {
                val blocked = (existing + added).any { item ->
                    item.status in setOf(Recording.SCHEDULED, Recording.RECORDING) &&
                        program.startMillis < item.windowEnd() &&
                        program.endMillis + Recording.PAD > item.windowStart()
                }
                if (blocked) continue
                added += recording(channel, program, destination, 2 * 60_000L, 5 * 60_000L)
            }
        }
        if (added.isEmpty()) return book
        return book.copy(
            items = existing + added,
            revision = System.currentTimeMillis()
        )
    }

    private fun recording(
        channel: Channel,
        program: EpgProgram,
        destination: String,
        startPadMs: Long,
        endPadMs: Long
    ): Recording {
        return Recording(
            id = UUID.randomUUID().toString(),
            channelId = channel.id,
            channelName = channel.name,
            title = program.title,
            startMillis = program.startMillis,
            endMillis = program.endMillis,
            destination = destination,
            status = Recording.SCHEDULED,
            fileName = RecordingFiles.safeName(channel.name, program.title, program.startMillis),
            bytes = 0L,
            copied = false,
            startPadMillis = startPadMs,
            endPadMillis = endPadMs
        )
    }

    private suspend fun savedPlaylist(app: OlympusIptvApp, playlistId: String): SavedPlaylist? {
        val store = app.playlistStore
        return store.session.first()?.takeIf { it.id == playlistId }
            ?: store.playlists.first().firstOrNull { it.id == playlistId }
    }

    suspend fun push(app: OlympusIptvApp, playlistId: String, book: RecordingBook) {
        val email = app.playlistStore.session.first()?.email?.trim().orEmpty()
        if (email.isBlank()) return
        LineupClient(app).pushRecordings(email, app.playlistStore.deviceId(), book)
    }
}
