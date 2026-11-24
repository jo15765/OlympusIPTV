package com.olympus.iptv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlin.random.Random

private val Context.dataStore by preferencesDataStore(name = "olympus_iptv")

class FavoritePage(
    val ids: List<String>,
    val revision: Long,
    val lineup: LineupConfig
)

class SavedPage(
    val favorites: FavoritePage,
    val refreshedAt: Long,
    val recordings: RecordingBook
)

class PlaylistStore(private val context: Context) {
    var onLineupSaved: ((String, LineupConfig) -> Unit)? = null
    var onFavoritesSaved: ((String) -> Unit)? = null
    private val gson get() = AppJson.gson
    private val key = stringPreferencesKey("playlists_json")
    private val sessionKey = stringPreferencesKey("session_json")
    private val favoritesKey = stringPreferencesKey("favorites_json")
    private val favoritesRevisionKey = stringPreferencesKey("favorites_revision_json")
    private val deviceKey = stringPreferencesKey("device_id")
    private val lineupKey = stringPreferencesKey("lineup_json")
    private val catalogStampKey = stringPreferencesKey("catalog_stamp_json")
    private val countryStampKey = stringPreferencesKey("country_stamp_json")
    private val refreshIntervalKey = intPreferencesKey("refresh_interval_minutes")
    private val refreshLastKey = stringPreferencesKey("refresh_last_json")
    private val pictureModeKey = stringPreferencesKey("picture_mode")
    private val autoFrameKey = booleanPreferencesKey("auto_frame_rate")
    private val profileKey = stringPreferencesKey("viewer_profile")
    private val profilePinKey = stringPreferencesKey("viewer_pins_json")
    private val audioLanguageKey = stringPreferencesKey("audio_language")
    private val audioByChannelKey = stringPreferencesKey("audio_by_channel_json")
    private val hideEmptyGroupsKey = booleanPreferencesKey("hide_empty_groups")
    private val subtitleLanguageKey = stringPreferencesKey("subtitle_language")
    private val subtitleByChannelKey = stringPreferencesKey("subtitle_by_channel_json")
    private val guideDensityKey = stringPreferencesKey("guide_density")
    private val quietHoursKey = booleanPreferencesKey("quiet_hours")
    private val quietStartKey = intPreferencesKey("quiet_start_minute")
    private val quietEndKey = intPreferencesKey("quiet_end_minute")
    private val parentalTimeoutKey = intPreferencesKey("parental_timeout_minutes")
    private val multiViewKey = booleanPreferencesKey("multi_view")
    private val lastGuideSectionKey = stringPreferencesKey("last_guide_section")
    private val skipIntroSeriesKey = stringPreferencesKey("skip_intro_series_json")
    private val lastChannelKey = stringPreferencesKey("last_channel_json")
    private val previousChannelKey = stringPreferencesKey("previous_channel_json")
    private val favoritesSetupKey = booleanPreferencesKey("favorites_setup_done")
    private val recordingsKey = stringPreferencesKey("recordings_json")
    private val watchKey = stringPreferencesKey("watch_json")
    private val queueKey = stringPreferencesKey("queue_json")
    private val playlistOrderKey = stringPreferencesKey("playlist_order_json")
    private val videoDecoderKey = stringPreferencesKey("video_decoder")
    private val tmdbItemsLimitKey = intPreferencesKey("tmdb_items_limit")
    private val scrobblerProviderKey = stringPreferencesKey("scrobbler_provider")
    private val simklTokenKey = stringPreferencesKey("simkl_token")
    private val simklClientIdKey = stringPreferencesKey("simkl_client_id")
    private val traktTokenKey = stringPreferencesKey("trakt_token")
    private val traktClientIdKey = stringPreferencesKey("trakt_client_id")
    private val pipSwipeHomeKey = booleanPreferencesKey("pip_swipe_home")
    private val autoResumeKey = booleanPreferencesKey("auto_resume_last_channel")
    private val nightModeKey = booleanPreferencesKey("audio_night_mode")
    private val recordingsType = object : TypeToken<Map<String, RecordingBook>>() {}.type
    private val stringMapType = object : TypeToken<Map<String, String>>() {}.type
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val listType = object : TypeToken<List<SavedPlaylist>>() {}.type
    private val favoritesType = object : TypeToken<Map<String, List<String>>>() {}.type
    private val lineupType = object : TypeToken<Map<String, LineupConfig>>() {}.type
    private val watchType = object : TypeToken<Map<String, List<WatchEntry>>>() {}.type
    private val queueType = object : TypeToken<Map<String, List<QueueItem>>>() {}.type
    private var lineupParsed: Pair<String?, Map<String, LineupConfig>>? = null
    private var favoritesParsed: Pair<String?, Map<String, List<String>>>? = null
    private var revisionParsed: Pair<String?, Map<String, String>>? = null
    private var watchParsed: Pair<String?, Map<String, List<WatchEntry>>>? = null
    private var recordingsParsed: Pair<String?, Map<String, RecordingBook>>? = null
    private var queueParsed: Pair<String?, Map<String, List<QueueItem>>>? = null
    private var stringMapParsed: Pair<String?, Map<String, String>>? = null
    @Volatile private var cachedPrefs: Preferences? = null

    suspend fun currentPrefs(): Preferences =
        cachedPrefs ?: context.dataStore.data.first().also { cachedPrefs = it }

    private suspend fun editPrefs(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit): Preferences {
        val updated = context.dataStore.edit { prefs -> block(prefs) }
        cachedPrefs = updated
        return updated
    }

    val session: Flow<SavedPlaylist?> = context.dataStore.data.map { prefs ->
        cachedPrefs = prefs
        val raw = prefs[sessionKey].orEmpty()
        if (raw.isBlank()) null
        else runCatching { gson.fromJson(raw, SavedPlaylist::class.java) }.getOrNull()
    }

    suspend fun account(id: String): SavedPlaylist? {
        val listed = playlists.first().firstOrNull { it.id == id }
        if (listed != null) return listed
        val saved = session.first() ?: return null
        if (id.isNotBlank() && saved.id != id) return null
        saveSession(saved)
        return saved
    }

    suspend fun opening(): Pair<SavedPlaylist?, Boolean?> {
        val prefs = currentPrefs()
        val raw = prefs[sessionKey].orEmpty()
        val saved = if (raw.isBlank()) null
        else runCatching { gson.fromJson(raw, SavedPlaylist::class.java) }.getOrNull()
        val ready = if (!prefs.contains(favoritesSetupKey)) null else prefs[favoritesSetupKey] == true
        return saved to ready
    }

    suspend fun favoritesSetupDone(): Boolean? {
        val prefs = currentPrefs()
        if (!prefs.contains(favoritesSetupKey)) return null
        return prefs[favoritesSetupKey] == true
    }

    suspend fun setFavoritesSetupDone(done: Boolean) {
        editPrefs { prefs ->
            prefs[favoritesSetupKey] = done
        }
    }

    suspend fun saveSession(playlist: SavedPlaylist) {
        editPrefs { prefs ->
            prefs[sessionKey] = gson.toJson(playlist)
            prefs[key] = gson.toJson(listOf(playlist))
        }
    }

    suspend fun deviceId(): String {
        val existing = currentPrefs()[deviceKey]
        if (!existing.isNullOrBlank()) return existing
        val created = newDeviceId()
        editPrefs { prefs ->
            if (prefs[deviceKey].isNullOrBlank()) prefs[deviceKey] = created
        }
        return currentPrefs()[deviceKey] ?: created
    }

    suspend fun lineup(playlistId: String): LineupConfig {
        val raw = currentPrefs()[lineupKey]
        return parsedLineups(raw)[playlistId]?.copy() ?: LineupConfig()
    }

    private fun parsedLineups(raw: String?): Map<String, LineupConfig> {
        val cached = lineupParsed
        if (cached != null && cached.first == raw) return cached.second
        val decoded = decodeLineups(raw)
        lineupParsed = raw to decoded
        return decoded
    }

    suspend fun catalogStamp(playlistId: String): String? {
        return decodeStringMap(currentPrefs()[catalogStampKey])[playlistId]
    }

    suspend fun saveCatalogStamp(playlistId: String, stamp: String) {
        editPrefs { prefs ->
            val map = decodeStringMap(prefs[catalogStampKey])
            map[playlistId] = stamp
            prefs[catalogStampKey] = gson.toJson(map)
        }
    }

    suspend fun countryStamp(playlistId: String): String? {
        return decodeStringMap(currentPrefs()[countryStampKey])[playlistId]
    }

    suspend fun saveCountryStamp(playlistId: String, stamp: String) {
        editPrefs { prefs ->
            val map = decodeStringMap(prefs[countryStampKey])
            map[playlistId] = stamp
            prefs[countryStampKey] = gson.toJson(map)
        }
    }

    suspend fun refreshIntervalMinutes(): Int {
        val saved = currentPrefs()[refreshIntervalKey] ?: DEFAULT_REFRESH_MINUTES
        return if (saved in REFRESH_INTERVALS) saved else DEFAULT_REFRESH_MINUTES
    }

    suspend fun saveRefreshIntervalMinutes(minutes: Int) {
        val chosen = if (minutes in REFRESH_INTERVALS) minutes else DEFAULT_REFRESH_MINUTES
        editPrefs { prefs -> prefs[refreshIntervalKey] = chosen }
    }

    suspend fun pictureMode(): String = devicePrefs().pictureMode

    suspend fun autoFrameRate(): Boolean =
        currentPrefs()[autoFrameKey] != false

    suspend fun saveAutoFrameRate(enabled: Boolean) {
        editPrefs { prefs -> prefs[autoFrameKey] = enabled }
    }

    suspend fun activeProfile(): String =
        currentPrefs()[profileKey]?.takeIf { it.isNotBlank() } ?: "Main"

    suspend fun saveActiveProfile(name: String) {
        val chosen = name.ifBlank { "Main" }
        editPrefs { prefs -> prefs[profileKey] = chosen }
    }

    suspend fun profilePin(name: String): String? {
        val raw = currentPrefs()[profilePinKey] ?: return null
        val map = runCatching {
            gson.fromJson<Map<String, String>>(raw, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type)
        }.getOrNull() ?: return null
        return map[name]?.takeIf { it.isNotBlank() }
    }

    suspend fun saveProfilePin(name: String, pin: String?) {
        editPrefs { prefs ->
            val raw = prefs[profilePinKey]
            val map = runCatching {
                gson.fromJson<MutableMap<String, String>>(
                    raw,
                    object : com.google.gson.reflect.TypeToken<MutableMap<String, String>>() {}.type
                )
            }.getOrNull() ?: mutableMapOf()
            if (pin.isNullOrBlank()) map.remove(name) else map[name] = pin
            prefs[profilePinKey] = gson.toJson(map)
        }
    }

    private suspend fun profileScope(playlistId: String): String {
        val profile = activeProfile()
        return if (profile == "Main") playlistId else "$playlistId#$profile"
    }

    suspend fun savePictureMode(mode: String) {
        val chosen = if (mode in PICTURE_MODES) mode else PICTURE_FIT
        editPrefs { prefs -> prefs[pictureModeKey] = chosen }
        bumpDevicePrefs { it.copy(pictureMode = chosen) }
    }

    suspend fun audioLanguage(): String = devicePrefs().audioLanguage

    suspend fun saveAudioLanguage(language: String) {
        val chosen = language.trim().ifBlank { DEFAULT_AUDIO_LANGUAGE }
        editPrefs { prefs -> prefs[audioLanguageKey] = chosen }
        bumpDevicePrefs { it.copy(audioLanguage = chosen) }
    }

    suspend fun audioForChannel(channelId: String): String? {
        if (channelId.isBlank()) return null
        return decodeStringMap(currentPrefs()[audioByChannelKey])[channelId]
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun saveAudioForChannel(channelId: String, language: String) {
        if (channelId.isBlank()) return
        val chosen = language.trim()
        if (chosen.isBlank()) return
        editPrefs { prefs ->
            val map = decodeStringMap(prefs[audioByChannelKey])
            map[channelId] = chosen
            prefs[audioByChannelKey] = gson.toJson(map)
            prefs[audioLanguageKey] = chosen
        }
        bumpDevicePrefs { it.copy(audioLanguage = chosen) }
    }

    data class DevicePrefs(
        val hideEmptyGroups: Boolean,
        val guideDensity: String,
        val quietHours: Boolean,
        val quietStartMinute: Int,
        val quietEndMinute: Int,
        val parentalTimeoutMinutes: Int,
        val multiView: Boolean,
        val pictureMode: String,
        val audioLanguage: String,
        val subtitleLanguage: String
    )

    @Volatile
    private var devicePrefsCache: DevicePrefs? = null

    suspend fun devicePrefs(): DevicePrefs {
        devicePrefsCache?.let { return it }
        val prefs = currentPrefs()
        val picture = prefs[pictureModeKey].orEmpty().let { if (it in PICTURE_MODES) it else PICTURE_FIT }
        val density = prefs[guideDensityKey].orEmpty().let { if (it in GUIDE_DENSITIES) it else GUIDE_COMFORTABLE }
        val parental = prefs[parentalTimeoutKey] ?: DEFAULT_PARENTAL_TIMEOUT
        return DevicePrefs(
            hideEmptyGroups = prefs[hideEmptyGroupsKey] ?: true,
            guideDensity = density,
            quietHours = prefs[quietHoursKey] ?: false,
            quietStartMinute = prefs[quietStartKey] ?: DEFAULT_QUIET_START,
            quietEndMinute = prefs[quietEndKey] ?: DEFAULT_QUIET_END,
            parentalTimeoutMinutes = if (parental in PARENTAL_TIMEOUTS) parental else DEFAULT_PARENTAL_TIMEOUT,
            multiView = prefs[multiViewKey] ?: false,
            pictureMode = picture,
            audioLanguage = prefs[audioLanguageKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_AUDIO_LANGUAGE,
            subtitleLanguage = prefs[subtitleLanguageKey]?.takeIf { it.isNotBlank() } ?: SUBTITLE_OFF
        ).also {
            devicePrefsCache = it
            QuietHoursBridge.remember(it.quietHours, it.quietStartMinute, it.quietEndMinute)
        }
    }

    private fun bumpDevicePrefs(transform: (DevicePrefs) -> DevicePrefs) {
        val current = devicePrefsCache
        if (current != null) devicePrefsCache = transform(current)
        else devicePrefsCache = null
    }

    suspend fun hideEmptyGroups(): Boolean = devicePrefs().hideEmptyGroups

    suspend fun saveHideEmptyGroups(hide: Boolean) {
        editPrefs { prefs -> prefs[hideEmptyGroupsKey] = hide }
        bumpDevicePrefs { it.copy(hideEmptyGroups = hide) }
    }

    suspend fun subtitleLanguage(): String = devicePrefs().subtitleLanguage

    suspend fun saveSubtitleLanguage(language: String) {
        val chosen = language.trim().ifBlank { SUBTITLE_OFF }
        editPrefs { prefs -> prefs[subtitleLanguageKey] = chosen }
        bumpDevicePrefs { it.copy(subtitleLanguage = chosen) }
    }

    suspend fun subtitleForChannel(channelId: String): String? {
        if (channelId.isBlank()) return null
        return decodeStringMap(currentPrefs()[subtitleByChannelKey])[channelId]
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun saveSubtitleForChannel(channelId: String, language: String) {
        if (channelId.isBlank()) return
        val chosen = language.trim().ifBlank { SUBTITLE_OFF }
        editPrefs { prefs ->
            val map = decodeStringMap(prefs[subtitleByChannelKey])
            map[channelId] = chosen
            prefs[subtitleByChannelKey] = gson.toJson(map)
            prefs[subtitleLanguageKey] = chosen
        }
        bumpDevicePrefs { it.copy(subtitleLanguage = chosen) }
    }

    suspend fun guideDensity(): String = devicePrefs().guideDensity

    suspend fun saveGuideDensity(density: String) {
        val chosen = if (density in GUIDE_DENSITIES) density else GUIDE_COMFORTABLE
        editPrefs { prefs -> prefs[guideDensityKey] = chosen }
        bumpDevicePrefs { it.copy(guideDensity = chosen) }
    }

    suspend fun quietHours(): Boolean = devicePrefs().quietHours

    suspend fun quietStartMinute(): Int = devicePrefs().quietStartMinute

    suspend fun quietEndMinute(): Int = devicePrefs().quietEndMinute

    suspend fun saveQuietHours(enabled: Boolean, startMinute: Int = DEFAULT_QUIET_START, endMinute: Int = DEFAULT_QUIET_END) {
        val start = startMinute.coerceIn(0, 24 * 60 - 1)
        val end = endMinute.coerceIn(0, 24 * 60 - 1)
        editPrefs { prefs ->
            prefs[quietHoursKey] = enabled
            prefs[quietStartKey] = start
            prefs[quietEndKey] = end
        }
        bumpDevicePrefs { it.copy(quietHours = enabled, quietStartMinute = start, quietEndMinute = end) }
        QuietHoursBridge.remember(enabled, start, end)
    }

    suspend fun parentalTimeoutMinutes(): Int = devicePrefs().parentalTimeoutMinutes

    suspend fun saveParentalTimeoutMinutes(minutes: Int) {
        val chosen = if (minutes in PARENTAL_TIMEOUTS) minutes else DEFAULT_PARENTAL_TIMEOUT
        editPrefs { prefs -> prefs[parentalTimeoutKey] = chosen }
        bumpDevicePrefs { it.copy(parentalTimeoutMinutes = chosen) }
    }

    suspend fun multiView(): Boolean = devicePrefs().multiView

    suspend fun lastGuideSection(): String {
        val raw = currentPrefs()[lastGuideSectionKey].orEmpty()
        return when (raw.trim().lowercase()) {
            "vod", "movies" -> "vod"
            "series", "shows" -> "series"
            else -> "live"
        }
    }

    suspend fun saveLastGuideSection(section: String) {
        val chosen = when (section.trim().lowercase()) {
            "vod", "movies" -> "vod"
            "series", "shows" -> "series"
            else -> "live"
        }
        editPrefs { prefs -> prefs[lastGuideSectionKey] = chosen }
    }

    suspend fun skipIntroSeries(): Set<String> =
        decodeStringMap(currentPrefs()[skipIntroSeriesKey]).keys

    suspend fun rememberSkipIntro(seriesId: String) {
        val id = seriesId.trim()
        if (id.isEmpty()) return
        editPrefs { prefs ->
            val map = decodeStringMap(prefs[skipIntroSeriesKey])
            map[id] = "1"
            prefs[skipIntroSeriesKey] = gson.toJson(map)
        }
    }

    suspend fun saveMultiView(enabled: Boolean) {
        editPrefs { prefs -> prefs[multiViewKey] = enabled }
        bumpDevicePrefs { it.copy(multiView = enabled) }
    }

    suspend fun lastRefreshAt(playlistId: String): Long {
        return decodeStringMap(currentPrefs()[refreshLastKey])[playlistId]?.toLongOrNull() ?: 0L
    }

    suspend fun saveLastRefreshAt(playlistId: String, at: Long) {
        editPrefs { prefs ->
            val map = decodeStringMap(prefs[refreshLastKey])
            map[playlistId] = at.toString()
            prefs[refreshLastKey] = gson.toJson(map)
        }
    }

    suspend fun saveLineup(playlistId: String, config: LineupConfig) {
        editPrefs { prefs ->
            val map = decodeLineups(prefs[lineupKey])
            map[playlistId] = config
            prefs[lineupKey] = gson.toJson(map)
            lineupParsed = null
        }
        onLineupSaved?.invoke(playlistId, config)
    }

    private fun decodeLineups(raw: String?): MutableMap<String, LineupConfig> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            gson.fromJson<Map<String, LineupConfig>>(raw, lineupType)
        }.getOrNull()?.toMutableMap() ?: mutableMapOf()
    }

    suspend fun lastChannelId(playlistId: String): String? {
        val raw = currentPrefs()[lastChannelKey]
        return decodeStringMap(raw)[playlistId]?.takeIf { it.isNotBlank() }
    }

    suspend fun previousChannelId(playlistId: String): String? {
        val raw = currentPrefs()[previousChannelKey]
        return decodeStringMap(raw)[playlistId]?.takeIf { it.isNotBlank() }
    }

    suspend fun recent(playlistId: String): List<WatchEntry> {
        val key = profileScope(playlistId)
        val raw = currentPrefs()[watchKey]
        return parsedWatch(raw)[key].orEmpty().sortedByDescending { it.updatedAt ?: 0L }
    }

    private fun parsedWatch(raw: String?): Map<String, List<WatchEntry>> {
        val cached = watchParsed
        if (cached != null && cached.first == raw) return cached.second
        val decoded = decodeWatch(raw)
        watchParsed = raw to decoded
        return decoded
    }

    suspend fun rememberWatch(playlistId: String, entry: WatchEntry) {
        val channelId = entry.channelId?.takeIf { it.isNotBlank() } ?: return
        if (playlistId.isBlank()) return
        val key = profileScope(playlistId)
        editPrefs { prefs ->
            val map = decodeWatch(prefs[watchKey])
            val prior = map[key].orEmpty().firstOrNull { it.channelId == channelId }
            val nextPos = entry.positionMs ?: 0L
            val priorPos = prior?.positionMs ?: -1L
            if (prior != null &&
                priorPos >= 0L &&
                kotlin.math.abs(nextPos - priorPos) < 30_000L &&
                (entry.durationMs ?: 0L) == (prior.durationMs ?: 0L)
            ) {
                return@editPrefs
            }
            val kept = map[key].orEmpty().filter { it.channelId != channelId }
            map[key] = listOf(entry.copy(channelId = channelId)) + kept
            if ((map[key]?.size ?: 0) > 60) map[key] = map[key].orEmpty().take(60)
            val encoded = gson.toJson(map)
            prefs[watchKey] = encoded
            watchParsed = encoded to map
        }
    }

    private fun decodeWatch(raw: String?): MutableMap<String, List<WatchEntry>> {
        val cached = watchParsed
        if (cached != null && cached.first == raw) {
            return cached.second.mapValues { it.value.toList() }.toMutableMap()
        }
        if (raw.isNullOrBlank()) {
            watchParsed = raw to emptyMap()
            return mutableMapOf()
        }
        val decoded = runCatching {
            gson.fromJson<Map<String, List<WatchEntry>>>(raw, watchType)
        }.getOrNull().orEmpty()
        watchParsed = raw to decoded
        return decoded.mapValues { it.value.toList() }.toMutableMap()
    }

    suspend fun rememberChannel(playlistId: String, channelId: String) {
        if (playlistId.isBlank() || channelId.isBlank()) return
        editPrefs { prefs ->
            val lastMap = decodeStringMap(prefs[lastChannelKey])
            val previous = lastMap[playlistId]
            if (!previous.isNullOrBlank() && previous != channelId) {
                val prevMap = decodeStringMap(prefs[previousChannelKey])
                prevMap[playlistId] = previous
                prefs[previousChannelKey] = gson.toJson(prevMap)
            }
            lastMap[playlistId] = channelId
            prefs[lastChannelKey] = gson.toJson(lastMap)
        }
    }

    private fun decodeStringMap(raw: String?): MutableMap<String, String> {
        val cached = stringMapParsed
        if (cached != null && cached.first == raw) return cached.second.toMutableMap()
        if (raw.isNullOrBlank()) {
            stringMapParsed = raw to emptyMap()
            return mutableMapOf()
        }
        val decoded = runCatching {
            gson.fromJson<Map<String, String>>(raw, stringMapType)
        }.getOrNull().orEmpty()
        stringMapParsed = raw to decoded
        return decoded.toMutableMap()
    }

    suspend fun favorites(playlistId: String): List<String> {
        val key = profileScope(playlistId)
        val raw = currentPrefs()[favoritesKey]
        return parsedFavorites(raw)[key].orEmpty()
    }

    private fun parsedFavorites(raw: String?): Map<String, List<String>> {
        val cached = favoritesParsed
        if (cached != null && cached.first == raw) return cached.second
        val decoded = decodeFavorites(raw)
        favoritesParsed = raw to decoded
        return decoded
    }

    suspend fun favoriteIds(playlistId: String): Set<String> = favorites(playlistId).toSet()

    suspend fun recordingBook(playlistId: String): RecordingBook {
        val raw = currentPrefs()[recordingsKey]
        return parsedRecordings(raw)[playlistId] ?: RecordingBook()
    }

    suspend fun saveRecordingBook(playlistId: String, book: RecordingBook) {
        editPrefs { prefs ->
            val map = decodeBooks(prefs[recordingsKey])
            map[playlistId] = book
            prefs[recordingsKey] = gson.toJson(map)
            recordingsParsed = null
        }
    }

    private fun parsedRecordings(raw: String?): Map<String, RecordingBook> {
        val cached = recordingsParsed
        if (cached != null && cached.first == raw) return cached.second
        val decoded = decodeBooks(raw)
        recordingsParsed = raw to decoded
        return decoded
    }

    private fun decodeBooks(raw: String?): MutableMap<String, RecordingBook> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            gson.fromJson<Map<String, RecordingBook>>(raw, recordingsType)
        }.getOrNull()?.toMutableMap() ?: mutableMapOf()
    }

    suspend fun favoritesRevision(playlistId: String): Long {
        val raw = currentPrefs()[favoritesRevisionKey]
        return parsedRevisions(raw)[playlistId]?.toLongOrNull() ?: 0L
    }

    private fun parsedRevisions(raw: String?): Map<String, String> {
        val cached = revisionParsed
        if (cached != null && cached.first == raw) return cached.second
        val decoded = decodeStringMap(raw)
        revisionParsed = raw to decoded
        return decoded
    }

    suspend fun savedPage(playlistId: String): SavedPage {
        val prefs = currentPrefs()
        val favorites = favoritePage(playlistId, prefs)
        val refreshedAt = decodeStringMap(prefs[refreshLastKey])[playlistId]?.toLongOrNull() ?: 0L
        val raw = prefs[recordingsKey]
        val recordings = if (raw.isNullOrBlank()) {
            RecordingBook()
        } else {
            runCatching {
                gson.fromJson<Map<String, RecordingBook>>(raw, recordingsType)
            }.getOrNull()?.get(playlistId) ?: RecordingBook()
        }
        return SavedPage(favorites, refreshedAt, recordings)
    }

    suspend fun favoritePage(playlistId: String): FavoritePage {
        val prefs = currentPrefs()
        val page = favoritePage(playlistId, prefs)
        val key = profileScope(playlistId)
        if (key == playlistId) return page
        val ids = parsedFavorites(prefs[favoritesKey])[key].orEmpty()
        return FavoritePage(ids, page.revision, page.lineup)
    }

    private fun favoritePage(playlistId: String, prefs: Preferences): FavoritePage {
        val lineup = parsedLineups(prefs[lineupKey])[playlistId]?.copy() ?: LineupConfig()
        val ids = parsedFavorites(prefs[favoritesKey])[playlistId].orEmpty()
        val revision = parsedRevisions(prefs[favoritesRevisionKey])[playlistId]?.toLongOrNull() ?: 0L
        return FavoritePage(ids, revision, lineup)
    }

    suspend fun saveFavorites(playlistId: String, ids: List<String>, revision: Long) {
        val key = profileScope(playlistId)
        editPrefs { prefs ->
            val map = decodeFavorites(prefs[favoritesKey])
            map[key] = ids
            prefs[favoritesKey] = gson.toJson(map)
            prefs.writeRevision(playlistId, revision)
        }
        onFavoritesSaved?.invoke(playlistId)
    }

    suspend fun replaceFavorites(playlistId: String, ids: List<String>): Long {
        val revision = System.currentTimeMillis()
        saveFavorites(playlistId, ids, revision)
        return revision
    }

    suspend fun setFavorite(playlistId: String, channelId: String, favorite: Boolean): Long {
        val revision = System.currentTimeMillis()
        val key = profileScope(playlistId)
        editPrefs { prefs ->
            val map = decodeFavorites(prefs[favoritesKey])
            val ids = map[key]?.toMutableList() ?: mutableListOf()
            if (favorite) {
                if (!ids.contains(channelId)) ids.add(channelId)
            } else {
                ids.remove(channelId)
            }
            map[key] = ids
            prefs[favoritesKey] = gson.toJson(map)
            prefs.writeRevision(playlistId, revision)
        }
        onFavoritesSaved?.invoke(playlistId)
        return revision
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeRevision(
        playlistId: String,
        revision: Long
    ) {
        val map = decodeStringMap(this[favoritesRevisionKey])
        map[playlistId] = revision.toString()
        this[favoritesRevisionKey] = gson.toJson(map)
    }

    private fun decodeFavorites(raw: String?): MutableMap<String, List<String>> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            gson.fromJson<Map<String, List<String>>>(raw, favoritesType)
        }.getOrNull()?.toMutableMap() ?: mutableMapOf()
    }

    suspend fun clearSession() {
        editPrefs { prefs ->
            prefs.remove(sessionKey)
            prefs[key] = gson.toJson(emptyList<SavedPlaylist>())
        }
    }

    suspend fun resetDevice() {
        editPrefs { prefs ->
            prefs.remove(sessionKey)
            prefs[key] = gson.toJson(emptyList<SavedPlaylist>())
            prefs.remove(favoritesKey)
            prefs.remove(favoritesRevisionKey)
            prefs.remove(lineupKey)
            prefs.remove(catalogStampKey)
            prefs.remove(countryStampKey)
            prefs.remove(refreshIntervalKey)
            prefs.remove(refreshLastKey)
            prefs.remove(pictureModeKey)
            prefs.remove(audioLanguageKey)
            prefs.remove(audioByChannelKey)
            prefs.remove(hideEmptyGroupsKey)
            prefs.remove(subtitleLanguageKey)
            prefs.remove(subtitleByChannelKey)
            prefs.remove(guideDensityKey)
            prefs.remove(quietHoursKey)
            prefs.remove(quietStartKey)
            prefs.remove(quietEndKey)
            prefs.remove(parentalTimeoutKey)
            prefs.remove(multiViewKey)
            prefs.remove(lastGuideSectionKey)
            prefs.remove(skipIntroSeriesKey)
            prefs.remove(lastChannelKey)
            prefs.remove(previousChannelKey)
            prefs.remove(favoritesSetupKey)
            prefs.remove(recordingsKey)
            prefs.remove(watchKey)
            prefs.remove(queueKey)
            prefs.remove(playlistOrderKey)
            prefs.remove(videoDecoderKey)
            prefs.remove(tmdbItemsLimitKey)
            prefs.remove(scrobblerProviderKey)
            prefs.remove(simklTokenKey)
            prefs.remove(simklClientIdKey)
            prefs.remove(traktTokenKey)
            prefs.remove(traktClientIdKey)
            prefs.remove(pipSwipeHomeKey)
            prefs.remove(autoResumeKey)
            prefs.remove(nightModeKey)
        }
        lineupParsed = null
        favoritesParsed = null
        revisionParsed = null
        watchParsed = null
        recordingsParsed = null
        queueParsed = null
        stringMapParsed = null
        devicePrefsCache = null
        videoDecoderCache = null
        QuietHoursBridge.clear()
    }

    val playlists: Flow<List<SavedPlaylist>> = context.dataStore.data.map { prefs ->
        val raw = prefs[key].orEmpty()
        val listed = if (raw.isBlank()) emptyList()
        else runCatching { gson.fromJson<List<SavedPlaylist>>(raw, listType) }.getOrDefault(emptyList())
        orderPlaylists(listed, prefs[playlistOrderKey])
    }

    suspend fun playlistOrder(): List<String> {
        val raw = currentPrefs()[playlistOrderKey]
        return decodeStringList(raw)
    }

    suspend fun savePlaylistOrder(ids: List<String>) {
        editPrefs { prefs ->
            prefs[playlistOrderKey] = gson.toJson(ids)
        }
    }

    suspend fun movePlaylist(id: String, direction: Int): Boolean {
        val listed = playlists.first()
        if (listed.size < 2) return false
        val order = ListOrder.merge(playlistOrder(), listed.map { it.id })
        if (!ListOrder.swap(order, id, direction)) return false
        savePlaylistOrder(order)
        return true
    }

    private fun orderPlaylists(listed: List<SavedPlaylist>, orderRaw: String?): List<SavedPlaylist> {
        if (listed.isEmpty()) return listed
        val order = decodeStringList(orderRaw)
        if (order.isEmpty()) return listed
        val byId = listed.associateBy { it.id }
        return ListOrder.merge(order, listed.map { it.id }).mapNotNull { byId[it] }
    }

    private fun decodeStringList(raw: String?): MutableList<String> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return runCatching {
            gson.fromJson<List<String>>(raw, stringListType)
        }.getOrNull()?.toMutableList() ?: mutableListOf()
    }

    @Volatile
    private var videoDecoderCache: String? = null

    fun videoDecoderCached(): String? = videoDecoderCache

    suspend fun videoDecoder(): String {
        videoDecoderCache?.let { return it }
        val value = PlayerDecoder.normalize(currentPrefs()[videoDecoderKey])
        videoDecoderCache = value
        return value
    }

    suspend fun saveVideoDecoder(type: String) {
        val chosen = PlayerDecoder.normalize(type)
        editPrefs { prefs -> prefs[videoDecoderKey] = chosen }
        videoDecoderCache = chosen
    }

    suspend fun tmdbItemsLimit(): Int {
        val saved = currentPrefs()[tmdbItemsLimitKey] ?: DEFAULT_TMDB_ITEMS
        return saved.coerceIn(1, MAX_TMDB_ITEMS)
    }

    suspend fun saveTmdbItemsLimit(limit: Int) {
        val chosen = limit.coerceIn(1, MAX_TMDB_ITEMS)
        editPrefs { prefs -> prefs[tmdbItemsLimitKey] = chosen }
    }

    suspend fun scrobblerProvider(): String {
        val raw = currentPrefs()[scrobblerProviderKey].orEmpty()
        return when (raw) {
            ScrobblerIds.SIMKL, ScrobblerIds.TRAKT -> raw
            else -> ScrobblerIds.NONE
        }
    }

    suspend fun saveScrobblerProvider(provider: String) {
        val chosen = when (provider) {
            ScrobblerIds.SIMKL, ScrobblerIds.TRAKT -> provider
            else -> ScrobblerIds.NONE
        }
        editPrefs { prefs ->
            prefs[scrobblerProviderKey] = chosen
            // Mutex: only one scrobbler active.
            if (chosen == ScrobblerIds.SIMKL) {
                prefs.remove(traktTokenKey)
                prefs.remove(traktClientIdKey)
            } else if (chosen == ScrobblerIds.TRAKT) {
                prefs.remove(simklTokenKey)
                prefs.remove(simklClientIdKey)
            }
        }
    }

    suspend fun simklToken(): String = currentPrefs()[simklTokenKey].orEmpty()
    suspend fun simklClientId(): String = currentPrefs()[simklClientIdKey].orEmpty()
    suspend fun traktToken(): String = currentPrefs()[traktTokenKey].orEmpty()
    suspend fun traktClientId(): String = currentPrefs()[traktClientIdKey].orEmpty()

    suspend fun saveSimklCredentials(token: String, clientId: String) {
        editPrefs { prefs ->
            prefs[simklTokenKey] = token.trim()
            prefs[simklClientIdKey] = clientId.trim()
            prefs[scrobblerProviderKey] = ScrobblerIds.SIMKL
            prefs.remove(traktTokenKey)
            prefs.remove(traktClientIdKey)
        }
    }

    suspend fun saveTraktCredentials(token: String, clientId: String) {
        editPrefs { prefs ->
            prefs[traktTokenKey] = token.trim()
            prefs[traktClientIdKey] = clientId.trim()
            prefs[scrobblerProviderKey] = ScrobblerIds.TRAKT
            prefs.remove(simklTokenKey)
            prefs.remove(simklClientIdKey)
        }
    }

    suspend fun clearScrobbler() {
        editPrefs { prefs ->
            prefs[scrobblerProviderKey] = ScrobblerIds.NONE
            prefs.remove(simklTokenKey)
            prefs.remove(simklClientIdKey)
            prefs.remove(traktTokenKey)
            prefs.remove(traktClientIdKey)
        }
    }

    suspend fun pipSwipeHome(): Boolean = currentPrefs()[pipSwipeHomeKey] ?: true

    suspend fun savePipSwipeHome(enabled: Boolean) {
        editPrefs { prefs -> prefs[pipSwipeHomeKey] = enabled }
    }

    suspend fun nightMode(): Boolean = currentPrefs()[nightModeKey] ?: false

    suspend fun saveNightMode(enabled: Boolean) {
        editPrefs { prefs -> prefs[nightModeKey] = enabled }
    }

    suspend fun autoResume(): Boolean = currentPrefs()[autoResumeKey] ?: false

    suspend fun saveAutoResume(enabled: Boolean) {
        editPrefs { prefs -> prefs[autoResumeKey] = enabled }
    }

    suspend fun scrobbleConfig(): ScrobbleConfig {
        val prefs = currentPrefs()
        val provider = when (prefs[scrobblerProviderKey].orEmpty()) {
            ScrobblerIds.SIMKL, ScrobblerIds.TRAKT -> prefs[scrobblerProviderKey].orEmpty()
            else -> ScrobblerIds.NONE
        }
        return ScrobbleConfig(
            provider = provider,
            simklToken = prefs[simklTokenKey].orEmpty(),
            simklClientId = prefs[simklClientIdKey].orEmpty(),
            traktToken = prefs[traktTokenKey].orEmpty(),
            traktClientId = prefs[traktClientIdKey].orEmpty()
        )
    }

    data class ScrobbleConfig(
        val provider: String,
        val simklToken: String,
        val simklClientId: String,
        val traktToken: String,
        val traktClientId: String
    ) {
        fun activeToken(): String = when (provider) {
            ScrobblerIds.SIMKL -> simklToken
            ScrobblerIds.TRAKT -> traktToken
            else -> ""
        }

        fun activeClientId(): String = when (provider) {
            ScrobblerIds.SIMKL -> simklClientId
            ScrobblerIds.TRAKT -> traktClientId
            else -> ""
        }
    }

    suspend fun queue(playlistId: String): List<QueueItem> {
        val key = profileScope(playlistId)
        val raw = currentPrefs()[queueKey]
        return parsedQueue(raw)[key].orEmpty()
    }

    suspend fun addQueue(playlistId: String, item: QueueItem) {
        if (playlistId.isBlank() || item.id.isBlank()) return
        val key = profileScope(playlistId)
        editPrefs { prefs ->
            val map = decodeQueue(prefs[queueKey])
            val list = map[key]?.toMutableList() ?: mutableListOf()
            if (list.none { it.bucket == item.bucket && it.id == item.id }) list.add(item)
            map[key] = list
            prefs[queueKey] = gson.toJson(map)
            queueParsed = null
        }
    }

    suspend fun removeQueue(playlistId: String, bucket: String, id: String) {
        val key = profileScope(playlistId)
        editPrefs { prefs ->
            val map = decodeQueue(prefs[queueKey])
            map[key] = map[key].orEmpty().filterNot { it.bucket == bucket && it.id == id }
            prefs[queueKey] = gson.toJson(map)
            queueParsed = null
        }
    }

    private fun parsedQueue(raw: String?): Map<String, List<QueueItem>> {
        val cached = queueParsed
        if (cached != null && cached.first == raw) return cached.second
        val decoded = decodeQueue(raw)
        queueParsed = raw to decoded
        return decoded
    }

    private fun decodeQueue(raw: String?): MutableMap<String, List<QueueItem>> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            gson.fromJson<Map<String, List<QueueItem>>>(raw, queueType)
        }.getOrNull()?.toMutableMap() ?: mutableMapOf()
    }

    suspend fun add(playlist: SavedPlaylist) {
        editPrefs { prefs ->
            val current = prefs[key]?.let {
                runCatching { gson.fromJson<List<SavedPlaylist>>(it, listType) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[key] = gson.toJson(current + playlist)
        }
    }

    suspend fun remove(id: String) {
        editPrefs { prefs ->
            val current = prefs[key]?.let {
                runCatching { gson.fromJson<List<SavedPlaylist>>(it, listType) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[key] = gson.toJson(current.filterNot { it.id == id })
        }
    }

    companion object {
        val REFRESH_INTERVALS = intArrayOf(30, 60, 120, 360, 720, 1440)
        const val DEFAULT_REFRESH_MINUTES = 60
        const val PICTURE_FIT = "fit"
        const val PICTURE_FILL = "fill"
        const val PICTURE_STRETCH = "stretch"
        val PICTURE_MODES = setOf(PICTURE_FIT, PICTURE_FILL, PICTURE_STRETCH)
        const val DEFAULT_AUDIO_LANGUAGE = "en-US"
        const val SUBTITLE_OFF = "off"
        const val GUIDE_COMFORTABLE = "comfortable"
        const val GUIDE_COMPACT = "compact"
        val GUIDE_DENSITIES = setOf(GUIDE_COMFORTABLE, GUIDE_COMPACT)
        const val DEFAULT_QUIET_START = 23 * 60
        const val DEFAULT_QUIET_END = 7 * 60
        const val DEFAULT_PARENTAL_TIMEOUT = 15
        val PARENTAL_TIMEOUTS = intArrayOf(5, 15, 30, 60)
        const val DEFAULT_TMDB_ITEMS = 10
        const val MAX_TMDB_ITEMS = 40
        val TMDB_ITEM_OPTIONS = intArrayOf(5, 10, 20, 40)

        fun newId(): String = UUID.randomUUID().toString()

        fun newDeviceId(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val raw = buildString(12) {
                repeat(12) { append(alphabet[Random.nextInt(alphabet.length)]) }
            }
            return "OI-${raw.substring(0, 4)}-${raw.substring(4, 8)}-${raw.substring(8, 12)}"
        }
    }
}
