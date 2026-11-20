package com.olympus.iptv.ui.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.Subtitles
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PlayerActivity.openAudio() {
        val exo = player ?: return
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.audio)
        binding.subtitlePanel.visibility = View.VISIBLE
        binding.subtitleRows.removeAllViews()
        val choices = streamChoices(exo, C.TRACK_TYPE_AUDIO)
        binding.subtitleStatus.text = if (nightMode) getString(R.string.night_mode_on) else if (choices.isEmpty()) getString(R.string.audio_none) else ""
        addSubtitleRow(getString(if (nightMode) R.string.night_mode_on else R.string.night_mode)) {
            chooseNightMode(!nightMode)
            closeSubtitlePanel()
        }
        addSubtitleRow(getString(if (radioOn) R.string.radio_on else R.string.radio_mode)) {
            closeSubtitlePanel()
            toggleRadioMode()
        }
        val offset = com.olympus.iptv.data.AvSyncStore.get(current?.id.orEmpty())
        addSubtitleRow(getString(R.string.av_sync_earlier, offset)) {
            nudgeAvSync(-com.olympus.iptv.data.AvSyncStore.STEP_MS)
        }
        addSubtitleRow(getString(R.string.av_sync_later, offset)) {
            nudgeAvSync(com.olympus.iptv.data.AvSyncStore.STEP_MS)
        }
        choices.forEach { choice ->
            addSubtitleRow(choice.label) {
                selectTrack(choice, C.TRACK_TYPE_AUDIO)
                rememberAudio(choice)
                closeSubtitlePanel()
            }
        }
        binding.subtitleRows.getChildAt(0)?.requestFocus()
    }

internal fun PlayerActivity.openSleepTimer() {
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.sleep_timer)
        binding.subtitlePanel.visibility = View.VISIBLE
        binding.subtitleRows.removeAllViews()
        binding.subtitleStatus.text = if (sleepMinutes > 0) {
            getString(R.string.sleep_armed)
        } else {
            ""
        }
        val choices = listOf(
            0 to R.string.sleep_off,
            30 to R.string.sleep_30,
            60 to R.string.sleep_60,
            90 to R.string.sleep_90
        )
        var focusIndex = 0
        choices.forEachIndexed { index, (minutes, label) ->
            val text = if (minutes == sleepMinutes) {
                getString(R.string.track_on, getString(label))
            } else {
                getString(label)
            }
            if (minutes == sleepMinutes) focusIndex = index
            addSubtitleRow(text) {
                armSleepTimer(minutes)
                closeSubtitlePanel()
            }
        }
        if (touchPrimary) {
            addSubtitleRow(
                getString(
                    if (rememberedPhoneOrientation() == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        R.string.player_orientation_lock
                    } else {
                        R.string.player_orientation_free
                    }
                )
            ) {
                togglePhoneOrientationLock()
                closeSubtitlePanel()
            }
        }
        binding.subtitleRows.getChildAt(focusIndex)?.requestFocus()
    }

internal fun PlayerActivity.armSleepTimer(minutes: Int) {
        hideHandler.removeCallbacks(sleepFire)
        sleepMinutes = minutes
        if (minutes <= 0) {
            paintSleepLabel()
            return
        }
        hideHandler.postDelayed(sleepFire, minutes * 60_000L)
        paintSleepLabel()
        Toast.makeText(this, R.string.sleep_armed, Toast.LENGTH_SHORT).show()
    }

internal fun PlayerActivity.paintSleepLabel() {
        if (!tracksReady()) return
        binding.btnSleep.text = if (sleepMinutes > 0) {
            getString(R.string.sleep_timer) + " · " + sleepMinutes
        } else {
            getString(R.string.sleep_timer)
        }
    }

internal fun PlayerActivity.playerPrefs() = getSharedPreferences("olympus_player", android.content.Context.MODE_PRIVATE)

internal fun PlayerActivity.rememberedPhoneOrientation(): Int {
        val locked = playerPrefs().getInt("phone_orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        return if (locked == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
            locked == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ) {
            locked
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

internal fun PlayerActivity.togglePhoneOrientationLock() {
        val prefs = playerPrefs()
        val current = rememberedPhoneOrientation()
        val next = when {
            current != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        prefs.edit().putInt("phone_orientation", next).apply()
        requestedOrientation = next
    }

internal fun PlayerActivity.openPicture() {
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.picture)
        binding.subtitlePanel.visibility = View.VISIBLE
        binding.subtitleRows.removeAllViews()
        binding.subtitleStatus.text = ""
        val choices = listOf(
            PlaylistStore.PICTURE_FIT to R.string.picture_fit,
            PlaylistStore.PICTURE_FILL to R.string.picture_fill,
            PlaylistStore.PICTURE_STRETCH to R.string.picture_stretch
        )
        var focusIndex = 0
        choices.forEachIndexed { index, (mode, label) ->
            val text = if (mode == pictureMode) {
                getString(R.string.track_on, getString(label))
            } else {
                getString(label)
            }
            if (mode == pictureMode) focusIndex = index
            addSubtitleRow(text) {
                pictureMode = mode
                applyPicture(mode)
                lifecycleScope.launch(Dispatchers.IO) {
                    (application as OlympusIptvApp).playlistStore.savePictureMode(mode)
                }
                closeSubtitlePanel()
            }
        }
        val frameLabel = if (autoFrameRate) {
            getString(R.string.track_on, getString(R.string.auto_frame_rate))
        } else {
            getString(R.string.auto_frame_rate)
        }
        addSubtitleRow(frameLabel) {
            autoFrameRate = !autoFrameRate
            lifecycleScope.launch(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.saveAutoFrameRate(autoFrameRate)
            }
            if (autoFrameRate) matchFrameRate()
            closeSubtitlePanel()
        }
        binding.subtitleRows.getChildAt(focusIndex)?.requestFocus()
    }

internal fun PlayerActivity.applyPicture(mode: String) {
        if (!tracksReady()) return
        val resize = when (mode) {
            PlaylistStore.PICTURE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PlaylistStore.PICTURE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = resize
    }

internal fun PlayerActivity.openSubtitles() {
        val channel = current ?: return
        val exo = player
        val series = !channel.seriesId.isNullOrBlank() && channel.streamUrl.isNotBlank()
        panelEmbedded = if (exo == null) emptyList() else streamChoices(exo, C.TRACK_TYPE_TEXT)
        if (touchPrimary) setPhoneSystemBarsHidden(false)
        binding.subtitleHeading.setText(R.string.subtitles)
        binding.subtitlePanel.visibility = View.VISIBLE
        if (!series) {
            subtitleJob?.cancel()
            showSubtitleHits(emptyList(), waiting = false)
            if (panelEmbedded.isEmpty() && sidecar == null) {
                binding.subtitleStatus.text = getString(R.string.subtitles_none)
            }
            return
        }
        binding.subtitleStatus.text = getString(R.string.subtitles_searching)
        showSubtitleHits(emptyList(), waiting = true)
        subtitleJob?.cancel()
        subtitleJob = channelScope().launch {
            val savedId = playlistId
            val seriesId = channel.seriesId
            val show = withContext(Dispatchers.IO) {
                if (savedId.isNullOrBlank() || seriesId.isNullOrBlank()) channel.name
                else {
                    (application as OlympusIptvApp).catalogDb
                        .readChannel(savedId, CatalogDb.SERIES, seriesId)?.name ?: channel.name
                }
            }
            val query = Subtitles.query(show, channel)
            if (query == null || !isActive) {
                binding.subtitleStatus.text = if (panelEmbedded.isEmpty()) getString(R.string.subtitles_none) else ""
                return@launch
            }
            val opened = async(Dispatchers.IO) { Subtitles.openSearch(query) }
            val listed = async(Dispatchers.IO) { Subtitles.tvSearch(filesDir, query) }
            val first = opened.await()
            if (!isActive) return@launch
            showSubtitleHits(first, waiting = true)
            val rest = listed.await()
            if (!isActive) return@launch
            showSubtitleHits(first + rest, waiting = false)
        }
    }

internal fun PlayerActivity.showSubtitleHits(hits: List<Subtitles.Hit>, waiting: Boolean) {
        binding.subtitleRows.removeAllViews()
        panelEmbedded.forEach { choice ->
            addSubtitleRow(choice.label) { chooseEmbeddedText(choice) }
        }
        if (sidecar != null || panelEmbedded.any { it.selected }) {
            addSubtitleRow(getString(R.string.subtitles_off)) { disableText() }
        }
        hits.forEach { hit -> addSubtitleRow(hit.label) { chooseSubtitle(hit) } }
        binding.subtitleStatus.text = when {
            hits.isEmpty() && waiting -> getString(R.string.subtitles_searching)
            hits.isEmpty() && panelEmbedded.isEmpty() && sidecar == null -> getString(R.string.subtitles_none)
            else -> ""
        }
        if (binding.subtitleRows.childCount > 0) {
            binding.subtitleRows.getChildAt(0)?.requestFocus()
        }
    }

internal fun PlayerActivity.chooseEmbeddedText(choice: StreamChoice) {
        pendingLanguage = choice.language
        rememberSubtitle(choice.language)
        if (sidecar != null) {
            clearSidecar()
            return
        }
        selectTrack(choice, C.TRACK_TYPE_TEXT)
        closeSubtitlePanel()
    }

internal fun PlayerActivity.disableText() {
        pendingLanguage = null
        rememberSubtitle(PlaylistStore.SUBTITLE_OFF)
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        if (sidecar != null) clearSidecar() else closeSubtitlePanel()
    }

internal fun PlayerActivity.selectTrack(choice: StreamChoice, type: Int) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.index)))
            .build()
    }

internal fun PlayerActivity.refreshStreamTracks() {
        val exo = player ?: return
        val channel = current
        val audio = streamChoices(exo, C.TRACK_TYPE_AUDIO)
        val text = streamChoices(exo, C.TRACK_TYPE_TEXT)
        val series = channel != null && !channel.seriesId.isNullOrBlank() && channel.streamUrl.isNotBlank()
        binding.btnAudio.visibility = if (audio.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnSubtitles.visibility = if (series || text.isNotEmpty()) View.VISIBLE else View.GONE
        if (binding.playbackMenu.visibility == View.VISIBLE && !menuButtonFocused()) {
            listOf(
                binding.btnAudio,
                binding.btnSubtitles,
                binding.btnRecord,
                binding.btnPicture,
                binding.btnLast,
                binding.btnGoLive,
                binding.btnMultiView
            )
                .firstOrNull { it.visibility == View.VISIBLE }
                ?.requestFocus()
        }
        applyPreferredAudio(channel, audio)
        applyPreferredSubtitle(channel, text)
        val wanted = pendingLanguage ?: return
        val match = text.firstOrNull { it.language == wanted } ?: return
        pendingLanguage = null
        selectTrack(match, C.TRACK_TYPE_TEXT)
    }

internal fun PlayerActivity.applyPreferredAudio(channel: Channel?, audio: List<StreamChoice>) {
        if (audio.isEmpty() || channel == null || channel.id.isBlank()) return
        if (audioAppliedFor == channel.id) return
        audioAppliedFor = channel.id
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val channelLang = withContext(Dispatchers.IO) { store.audioForChannel(channel.id) }
            val global = preferredAudio.ifBlank {
                withContext(Dispatchers.IO) { store.audioLanguage() }.also { preferredAudio = it }
            }
            val match = pickAudio(audio, channelLang ?: global) ?: return@launch
            if (!match.selected) selectTrack(match, C.TRACK_TYPE_AUDIO)
        }
    }

internal fun PlayerActivity.applyPreferredSubtitle(channel: Channel?, text: List<StreamChoice>) {
        if (text.isEmpty() || channel == null || channel.id.isBlank()) return
        if (subtitleAppliedFor == channel.id) return
        subtitleAppliedFor = channel.id
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val channelLang = withContext(Dispatchers.IO) { store.subtitleForChannel(channel.id) }
            val global = preferredSubtitle.ifBlank {
                withContext(Dispatchers.IO) { store.subtitleLanguage() }.also { preferredSubtitle = it }
            }
            val wanted = channelLang ?: global
            if (wanted.isBlank() || wanted == PlaylistStore.SUBTITLE_OFF) {
                val exo = player ?: return@launch
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                return@launch
            }
            val match = pickAudio(text, wanted) ?: return@launch
            if (!match.selected) selectTrack(match, C.TRACK_TYPE_TEXT)
        }
    }

internal fun PlayerActivity.rememberAudio(choice: StreamChoice) {
        val language = choice.language?.trim().orEmpty()
        if (language.isBlank()) return
        preferredAudio = language
        audioAppliedFor = current?.id
        val channelId = current?.id.orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val store = (application as OlympusIptvApp).playlistStore
            if (channelId.isNotBlank()) store.saveAudioForChannel(channelId, language)
            else store.saveAudioLanguage(language)
        }
    }

internal fun PlayerActivity.rememberSubtitle(language: String?) {
        val chosen = language?.trim().orEmpty().ifBlank { PlaylistStore.SUBTITLE_OFF }
        preferredSubtitle = chosen
        subtitleAppliedFor = current?.id
        val channelId = current?.id.orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val store = (application as OlympusIptvApp).playlistStore
            if (channelId.isNotBlank()) store.saveSubtitleForChannel(channelId, chosen)
            else store.saveSubtitleLanguage(chosen)
        }
    }

internal fun PlayerActivity.pickAudio(choices: List<StreamChoice>, wanted: String): StreamChoice? {
        val prefs = linkedSetOf(wanted, PlaylistStore.DEFAULT_AUDIO_LANGUAGE, "en", "eng")
        for (pref in prefs) {
            choices.firstOrNull { languageMatches(it.language, pref) }?.let { return it }
        }
        return null
    }

internal fun PlayerActivity.languageMatches(track: String?, want: String): Boolean {
        if (track.isNullOrBlank() || want.isBlank()) return false
        val a = track.replace('_', '-').lowercase(Locale.US)
        val b = want.replace('_', '-').lowercase(Locale.US)
        if (a == b) return true
        if (a.startsWith("$b-") || b.startsWith("$a-")) return true
        return a.substringBefore('-') == b.substringBefore('-')
    }

internal fun PlayerActivity.streamChoices(exo: Player, type: Int): List<StreamChoice> {
        val out = ArrayList<StreamChoice>()
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val language = format.language?.takeIf { it.isNotBlank() && !it.equals("und", true) }
                val spoken = language?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() }
                val extra = format.label?.takeIf { label ->
                    label.isNotBlank() && !label.equals(spoken, true) && !label.equals(language, true)
                }
                val base = listOfNotNull(spoken, extra).joinToString(" · ").ifBlank {
                    getString(R.string.track_n, out.size + 1)
                }
                val label = if (group.isTrackSelected(index)) getString(R.string.track_on, base) else base
                out += StreamChoice(group, index, label, language ?: format.label, group.isTrackSelected(index))
            }
        }
        return out
    }

internal data class StreamChoice(
        val group: Tracks.Group,
        val index: Int,
        val label: String,
        val language: String?,
        val selected: Boolean
    )

internal fun PlayerActivity.addSubtitleRow(label: String, onPick: () -> Unit) {
        val row = Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.text_primary))
            background = getDrawable(R.drawable.bg_focusable)
            textSize = 15f
            isFocusable = true
            setPadding(24, 12, 24, 12)
            setOnClickListener { onPick() }
        }
        val margin = (6 * resources.displayMetrics.density).toInt()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = margin }
        binding.subtitleRows.addView(row)
    }

internal fun PlayerActivity.chooseSubtitle(hit: Subtitles.Hit) {
        binding.subtitleStatus.text = getString(R.string.subtitles_saving)
        subtitleJob?.cancel()
        subtitleJob = channelScope().launch {
            val saved = withContext(Dispatchers.IO) { Subtitles.download(filesDir, hit) }
            if (!isActive) return@launch
            if (saved == null) {
                binding.subtitleStatus.text = getString(R.string.subtitles_failed)
                return@launch
            }
            rememberSubtitle("en")
            applySidecar(saved)
            closeSubtitlePanel()
            Toast.makeText(this@chooseSubtitle, R.string.subtitles_added, Toast.LENGTH_SHORT).show()
        }
    }

internal fun PlayerActivity.applySidecar(saved: Subtitles.Saved) {
        val exo = player ?: return
        val channel = current ?: return
        val at = exo.currentPosition.coerceAtLeast(0L)
        playbackReady = false
        sidecar = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(saved.file))
            .setMimeType(saved.mime)
            .setLanguage("en")
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()
        exo.setMediaItem(mediaItem(channel), at)
        exo.prepare()
        exo.playWhenReady = true
    }

internal fun PlayerActivity.clearSidecar() {
        val exo = player ?: return
        val channel = current ?: return
        val at = exo.currentPosition.coerceAtLeast(0L)
        playbackReady = false
        sidecar = null
        exo.setMediaItem(mediaItem(channel), at)
        exo.prepare()
        exo.playWhenReady = true
        closeSubtitlePanel()
    }

internal fun PlayerActivity.closeSubtitlePanel() {
        subtitleJob?.cancel()
        if (tracksReady()) binding.subtitlePanel.visibility = View.GONE
        if (touchPrimary &&
            tracksReady() &&
            binding.playbackMenu.visibility != View.VISIBLE &&
            binding.overlay.visibility != View.VISIBLE
        ) {
            setPhoneSystemBarsHidden(true)
        }
    }

