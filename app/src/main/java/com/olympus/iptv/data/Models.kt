package com.olympus.iptv.data

enum class PlaylistType {
    M3U,
    XTREAM
}

enum class ContentKind {
    LIVE,
    VOD
}

data class SavedPlaylist(
    val id: String,
    val name: String,
    val type: PlaylistType,
    val m3uUrl: String? = null,
    val xtreamHost: String? = null,
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
    val username: String? = null,
    val password: String? = null,
    val includeVod: Boolean = true,
    val includeInternational: Boolean? = false,
    val email: String? = null,
    val homeZip: String? = null,
    val homeCountry: String? = null,
    val homeLat: Double? = null,
    val homeLon: Double? = null
)

data class LineupConfig(
    val groupOrder: List<String>? = emptyList(),
    val hiddenGroups: List<String>? = emptyList(),
    val hiddenChannels: List<String>? = emptyList(),
    val groupNames: Map<String, String>? = emptyMap(),
    val channelNames: Map<String, String>? = emptyMap(),
    val channelOrder: Map<String, List<String>>? = emptyMap(),
    val hiddenCountries: List<String>? = emptyList(),
    val includeInternational: Boolean? = false,
    val namePrefixes: List<String>? = emptyList(),
    val nameSuffixes: List<String>? = emptyList(),
    val nameEpg: Boolean? = false,
    /** Auto-strip group prefixes, channel numbers, and clutter by default across TV and Web-UI. */
    val autoCleanNames: Boolean? = true,
    /** Bulk group find text. Shared by the TV and the web dashboard. */
    val groupFind: String? = null,
    /** Bulk group replacement. Empty means the found text is removed. */
    val groupReplace: String? = null,
    /** Group id to a 4-digit code. Null when that group is not locked. */
    val groupPins: Map<String, String>? = null,
    val revision: Long? = 0
)

data class Category(
    val id: String,
    val name: String,
    val kind: ContentKind = ContentKind.LIVE,
    val countryId: String = ""
)

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val categoryId: String? = null,
    val kind: ContentKind = ContentKind.LIVE,
    val containerExtension: String? = null,
    /** XMLTV / tvg-id when different from stream id (mostly M3U). */
    val epgChannelId: String? = null,
    val countryId: String? = null,
    val countryLabel: String? = null,
    val number: Int? = null,
    val seriesId: String? = null,
    val season: Int? = null,
    val blurb: String? = null,
    val aired: String? = null,
    val runtime: String? = null,
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    val tvArchive: Boolean? = null,
    val tvArchiveDuration: Int? = null,
    /** M3U #EXTVLCOPT values. Not stored in the catalog database. */
    val userAgent: String? = null,
    val httpReferer: String? = null
) {
    fun supportsCatchup(): Boolean {
        if (tvArchive == true && (tvArchiveDuration ?: 0) > 0) return true
        if (!catchupType.isNullOrBlank()) {
            val days = catchupDays
            return days == null || days > 0
        }
        return false
    }
}

data class QueueItem(
    val bucket: String,
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val seriesId: String? = null
)

data class EpgProgram(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String? = null,
    val guideTitle: String = "",
    val guideClock: String = ""
) {
    fun contains(now: Long = System.currentTimeMillis()): Boolean =
        now in startMillis until endMillis

    fun timeRangeLabel(): String {
        return "${com.olympus.iptv.playlist.EpgUtils.formatClock(startMillis)} – ${com.olympus.iptv.playlist.EpgUtils.formatClock(endMillis)}"
    }
}

data class WatchEntry(
    val channelId: String? = null,
    val seriesId: String? = null,
    val name: String? = null,
    val section: String? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val updatedAt: Long? = null
) {
    fun canResume(): Boolean {
        val position = positionMs ?: 0L
        if (position < 15_000L) return false
        val duration = durationMs ?: 0L
        if (duration > 0L && position > duration - 20_000L) return false
        return true
    }
}

data class EpgNowNext(
    val now: EpgProgram? = null,
    val next: EpgProgram? = null
) {
    fun nowLine(): String {
        val program = now ?: return "No EPG"
        return "${program.timeRangeLabel()}  ${program.title}"
    }

    fun nextLine(): String {
        val program = next ?: return ""
        return "Next: ${program.timeRangeLabel()}  ${program.title}"
    }
}
