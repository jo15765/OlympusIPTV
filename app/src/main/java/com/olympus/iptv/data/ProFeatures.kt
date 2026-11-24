package com.olympus.iptv.data

import android.content.Context
import com.google.gson.Gson

data class HouseholdProfile(
    val id: String = "default",
    val name: String = "Main",
    val avatar: String = "🎬",
    val kidsMode: Boolean = false,
    val pinProtected: Boolean = false,
    val hiddenGroups: List<String> = emptyList()
)

data class PlaybackDefaults(
    val preferredAudioLang: String = "",
    val preferredSubtitleLang: String = "",
    val subtitleScale: Float = 1.0f,
    val subtitleBgOpacity: Int = 65,
    val defaultVodSpeed: Float = 1.0f,
    val externalPlayerEnabled: Boolean = false,
    val externalPlayerPackage: String = "",
    val statsOverlayEnabled: Boolean = false,
    val spoilerFreeSports: Boolean = false,
    val epgOffsetMinutes: Int = 0,
    val customXmltvUrl: String = "",
    val backupFailoverEnabled: Boolean = true,
    val timeshiftMinutes: Int = 0,
    val quadMultiViewEnabled: Boolean = true
)

data class OfflineDownload(
    val id: String = "",
    val title: String = "",
    val channelId: String = "",
    val streamUrl: String = "",
    val fileName: String = "",
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = STATUS_QUEUED,
    val error: String? = null,
    val savedAt: Long = 0L
) {
    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
    }
}

data class ProState(
    val activeProfileId: String = "default",
    val profiles: List<HouseholdProfile> = listOf(
        HouseholdProfile(id = "default", name = "Main", avatar = "🎬"),
        HouseholdProfile(id = "kids", name = "Kids", avatar = "🧸", kidsMode = true)
    ),
    val playback: PlaybackDefaults = PlaybackDefaults(),
    val downloads: List<OfflineDownload> = emptyList(),
    val updatedAt: Long = 0L
)

/**
 * Fast in-memory cached store for Household Profiles, Playback Defaults, EPG Offset/XMLTV,
 * Timeshift, External Player, Stats HUD, and Offline Downloads.
 */
object ProFeatureStore {
    private const val PREFS = "olympus_pro_features"
    private const val KEY_STATE = "state_json"
    private val gson get() = AppJson.gson
    @Volatile private var cached: ProState? = null

    fun load(context: Context): ProState {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATE, null)
        val parsed = if (raw.isNullOrBlank()) {
            ProState()
        } else {
            runCatching { gson.fromJson(raw, ProState::class.java) }.getOrNull() ?: ProState()
        }
        val normalized = normalize(parsed)
        cached = normalized
        return normalized
    }

    fun save(context: Context, state: ProState): ProState {
        val clean = normalize(state).copy(updatedAt = System.currentTimeMillis())
        cached = clean
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, gson.toJson(clean))
            .apply()
        return clean
    }

    fun update(context: Context, transform: (ProState) -> ProState): ProState =
        save(context, transform(load(context)))

    fun activeProfile(context: Context): HouseholdProfile {
        val state = load(context)
        return state.profiles.firstOrNull { it.id == state.activeProfileId }
            ?: state.profiles.firstOrNull()
            ?: HouseholdProfile()
    }

    fun playback(context: Context): PlaybackDefaults = load(context).playback

    fun epgOffsetMs(context: Context): Long =
        load(context).playback.epgOffsetMinutes.coerceIn(-720, 720) * 60_000L

    private fun normalize(state: ProState): ProState {
        val profiles = state.profiles.ifEmpty {
            listOf(HouseholdProfile(id = "default", name = "Main", avatar = "🎬"))
        }
        val active = if (profiles.any { it.id == state.activeProfileId }) {
            state.activeProfileId
        } else {
            profiles.first().id
        }
        val pb = state.playback.copy(
            subtitleScale = state.playback.subtitleScale.coerceIn(0.7f, 1.8f),
            subtitleBgOpacity = state.playback.subtitleBgOpacity.coerceIn(0, 100),
            defaultVodSpeed = state.playback.defaultVodSpeed.coerceIn(0.5f, 2.0f),
            epgOffsetMinutes = state.playback.epgOffsetMinutes.coerceIn(-720, 720),
            timeshiftMinutes = state.playback.timeshiftMinutes.coerceIn(0, 120)
        )
        return state.copy(activeProfileId = active, profiles = profiles, playback = pb)
    }
}
