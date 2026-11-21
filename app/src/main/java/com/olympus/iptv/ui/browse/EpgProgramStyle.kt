package com.olympus.iptv.ui.browse

import android.content.Context
import androidx.annotation.ColorInt
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.playlist.EpgUtils
import java.util.Locale

enum class EpgKind {
    LIVE,
    NEW,
    SPORTS,
    MOVIE,
    OTHER
}

object EpgProgramStyle {
    fun kind(program: EpgProgram, channel: Channel, now: Long = EpgUtils.now()): EpgKind {
        val hay = "${program.title} ${channel.name} ${channel.groupTitle.orEmpty()}".lowercase(Locale.US)
        if (SPORTS.any { it in hay }) return EpgKind.SPORTS
        if (MOVIE.any { it in hay }) return EpgKind.MOVIE
        if (NEW.any { it in hay } || (episodeBadge(program.title) != null && "new" in hay)) return EpgKind.NEW
        if (now >= program.startMillis && now < program.endMillis) return EpgKind.LIVE
        return EpgKind.OTHER
    }

    fun pill(kind: EpgKind): String? = when (kind) {
        EpgKind.LIVE -> "LIVE"
        EpgKind.NEW -> "NEW"
        EpgKind.SPORTS -> "SPORTS"
        EpgKind.MOVIE -> "MOVIE"
        EpgKind.OTHER -> null
    }

    fun episodeBadge(title: String): String? {
        val match = EPISODE.find(title) ?: EPISODE_X.find(title) ?: return null
        val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return "S%02dE%02d".format(Locale.US, season, episode)
    }

    @ColorInt
    fun fill(context: Context, kind: EpgKind, focused: Boolean): Int {
        if (focused) return context.getColor(R.color.focus)
        return context.getColor(
            when (kind) {
                EpgKind.LIVE -> R.color.epg_live
                EpgKind.NEW -> R.color.epg_new
                EpgKind.SPORTS -> R.color.epg_sports
                EpgKind.MOVIE -> R.color.epg_movie
                EpgKind.OTHER -> R.color.bg_elevated
            }
        )
    }

    @ColorInt
    fun stroke(context: Context, kind: EpgKind, focused: Boolean): Int {
        if (focused) return context.getColor(R.color.accent)
        return context.getColor(
            when (kind) {
                EpgKind.LIVE -> R.color.epg_live_stroke
                EpgKind.NEW -> R.color.epg_new_stroke
                EpgKind.SPORTS -> R.color.epg_sports_stroke
                EpgKind.MOVIE -> R.color.epg_movie_stroke
                EpgKind.OTHER -> R.color.stroke
            }
        )
    }

    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""")
    private val EPISODE_X = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")
    private val SPORTS = listOf(
        "sport", "espn", "dazn", "nfl", "nba", "mlb", "nhl", "ufc", "mma",
        "soccer", "football", "baseball", "hockey", "tennis", "golf", "f1", "racing"
    )
    private val MOVIE = listOf("movie", "film", "cinema", "feature")
    private val NEW = listOf("premiere", "new episode", "season premiere", "series premiere", "brand new")
}