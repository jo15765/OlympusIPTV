package com.olympus.iptv.ui.browse

import android.view.View
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.EpisodeCache
import com.olympus.iptv.playlist.EpisodeSequence
import com.olympus.iptv.ui.browse.BrowseActivity.GuideSection

/** VOD continue, recent, and poster sorting. Holds no views of its own. */
internal fun BrowseActivity.continueText(channel: Channel): String? {
        val spot = resumeEntry(channel) ?: return null
        val minutes = ((spot.positionMs ?: 0L) / 60_000L).toInt().coerceAtLeast(1)
        val episode = spot.name?.takeIf {
            it.isNotBlank() && it != channel.name && channel.streamUrl.isBlank()
        }
        return if (episode == null) {
            getString(R.string.continue_watching, minutes)
        } else {
            getString(R.string.continue_episode, episode, minutes)
        }
    }

internal fun BrowseActivity.continueProgress(channel: Channel): Int? {
        val spot = resumeEntry(channel) ?: return null
        val position = spot.positionMs ?: return null
        val duration = spot.durationMs ?: return null
        if (duration <= 0L) return null
        return ((position * 1000L) / duration).toInt().coerceIn(1, 1000)
    }

internal fun BrowseActivity.posterRatingLabel(channel: Channel): String? {
        if (channel.kind == ContentKind.LIVE && channel.seriesId.isNullOrBlank()) return null
        val preferTv = !channel.seriesId.isNullOrBlank()
        val peek = com.olympus.iptv.playlist.TitleRatings.peek(channel.name, preferTv) ?: return null
        val score = peek.ratings.firstOrNull()?.scoreLabel ?: return null
        val number = score.substringBefore('/').substringBefore('%').trim()
        if (number.isBlank()) return null
        return "★ $number"
    }

internal fun BrowseActivity.posterMetaLine(channel: Channel): String? {
        val year = Regex("""\b(19|20)\d{2}\b""").find(channel.name)?.value
        val resume = continueText(channel)
        return listOfNotNull(year, resume).joinToString(" · ").ifBlank { null }
    }

internal fun BrowseActivity.recentChannels(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
        val saved = playlist ?: return emptyList()
        val db = (application as OlympusIptvApp).catalogDb
        val wanted = sectionKey(sectionNow)
        val seen = HashSet<String>()
        val rows = ArrayList<Channel>()
        for (entry in recentEntries) {
            if (entrySection(entry) != wanted) continue
            val id = if (wanted == "series") entry.seriesId ?: entry.channelId else entry.channelId
            if (id.isNullOrBlank() || !seen.add(id)) continue
            val row = db.readChannel(saved.id, libraryBucket(sectionNow), id) ?: continue
            val tagged = if (wanted == "series" && row.seriesId.isNullOrBlank()) row.copy(seriesId = id) else row
            rows.add(tagged)
            if (rows.size == 20) break
        }
        return prepareGroup(rows, config)
    }

internal fun BrowseActivity.continueChannels(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
        val saved = playlist ?: return emptyList()
        val db = (application as OlympusIptvApp).catalogDb
        val wanted = sectionKey(sectionNow)
        val seen = HashSet<String>()
        val rows = ArrayList<Channel>()
        for (entry in recentEntries) {
            if (entrySection(entry) != wanted || !entry.canResume()) continue
            if (wanted == "series") {
                val seriesId = entry.seriesId ?: entry.channelId ?: continue
                if (!seen.add(seriesId)) continue
                val show = db.readChannel(saved.id, CatalogDb.SERIES, seriesId)
                val episodeId = entry.channelId
                val cached = if (episodeId.isNullOrBlank()) null else {
                    EpisodeCache.peek(filesDir, saved.id, seriesId)?.firstOrNull { it.id == episodeId }
                }
                val row = if (cached != null) {
                    cached.copy(
                        seriesId = seriesId,
                        name = EpisodeSequence.heading(show?.name.orEmpty(), cached),
                        logoUrl = cached.logoUrl ?: show?.logoUrl
                    )
                } else {
                    show ?: continue
                }
                val tagged = if (row.seriesId.isNullOrBlank()) row.copy(seriesId = seriesId) else row
                rows.add(tagged)
            } else {
                val id = entry.channelId ?: continue
                if (!seen.add(id)) continue
                val row = db.readChannel(saved.id, libraryBucket(sectionNow), id) ?: continue
                rows.add(row)
            }
            if (rows.size == 20) break
        }
        return prepareGroup(rows, config)
    }

