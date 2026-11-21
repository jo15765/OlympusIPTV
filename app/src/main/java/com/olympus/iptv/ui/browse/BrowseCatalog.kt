package com.olympus.iptv.ui.browse

import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.Favorites
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.data.ChannelNames
import com.olympus.iptv.playlist.LineupApplier
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.ui.browse.BrowseActivity.GuideSection

/** Category, favorite, and hidden-channel filtering pulled out of the VOD coordinator. */
internal fun BrowseActivity.libraryBucket(sectionNow: GuideSection): String = when (sectionNow) {
    GuideSection.TV -> CatalogDb.LIVE
    GuideSection.MOVIES -> CatalogDb.VOD
    GuideSection.SHOWS -> CatalogDb.SERIES
}

internal fun BrowseActivity.libraryGroup(
    sectionNow: GuideSection,
    categoryId: String,
    config: LineupConfig
): List<Channel> {
    val saved = playlist ?: return emptyList()
    val rows = (application as OlympusIptvApp).catalogDb.readCategoryChannels(
        saved.id,
        libraryBucket(sectionNow),
        categoryId
    )
    return prepareGroup(rows, config)
}

internal fun BrowseActivity.forYouChannels(config: LineupConfig): List<Channel> {
    val recent = recentChannels(GuideSection.TV, config)
    val favorites = libraryFavorites(GuideSection.TV, config)
    val sports = (recent + favorites).filter { channel ->
        val title = epgCache[channel.id]?.now?.title ?: channel.name
        SportsEvent.isMatchup(title)
    }
    val ordered = LinkedHashSet<Channel>()
    sports.forEach { ordered.add(it) }
    recent.take(8).forEach { ordered.add(it) }
    favorites.take(16).forEach { ordered.add(it) }
    return ordered.toList()
}

internal fun BrowseActivity.libraryFavorites(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
    val saved = playlist ?: return emptyList()
    val section = favoriteSection(sectionNow)
    val keys = favoriteOrder.filter { Favorites.inSection(it, section) }
    val ids = keys.map { it.substringAfter(':') }
    val rows = (application as OlympusIptvApp).catalogDb.readChannelsByIds(
        saved.id,
        libraryBucket(sectionNow),
        ids
    ).map { channel ->
        if (section == Favorites.SERIES && channel.seriesId.isNullOrBlank()) channel.copy(seriesId = channel.id) else channel
    }
    return prepareGroup(Favorites.ordered(keys, rows.associateBy { it.id }, favoriteIds), config)
}

internal fun BrowseActivity.topWatchedFavorites(sectionNow: GuideSection, config: LineupConfig): List<Channel> {
    ensureTopWatchedCache()
    val wanted = sectionKey(sectionNow)
    val orderedIds = topWatchedIdsBySection[wanted].orEmpty()
    val favorites = libraryFavorites(sectionNow, config)
    if (favorites.isEmpty()) return emptyList()
    if (orderedIds.isEmpty()) return favorites.take(10)
    val byId = favorites.associateBy { it.id }
    val ranked = orderedIds.mapNotNull { byId[it] }
    return ranked.ifEmpty { favorites.take(10) }
}

internal fun BrowseActivity.visibleGroups(categories: List<com.olympus.iptv.data.Category>, config: LineupConfig): List<com.olympus.iptv.data.Category> {
    ensureGroupCountries()
    if (groupedSection == section &&
        groupedCategories === categories &&
        groupedConfig === config &&
        groupedCountries === groupCountries
    ) {
        return groupedResult
    }
    val groups = LineupApplier.groups(categories, config, groupCountries, groupCountriesLoaded)
    groupedSection = section
    groupedCategories = categories
    groupedConfig = config
    groupedCountries = groupCountries
    groupedResult = groups
    return groups
}

internal fun BrowseActivity.withoutEmptyGroups(categories: List<com.olympus.iptv.data.Category>): List<com.olympus.iptv.data.Category> {
    if (!hideEmptyGroups || groupCounts.isEmpty()) return categories
    return categories.filter { category ->
        category.id == PlaylistLoader.ALL_ID ||
            category.id == PlaylistLoader.FAVORITES_ID ||
            category.id == PlaylistLoader.RECENT_ID ||
            category.id == PlaylistLoader.FOR_YOU_ID ||
            category.id == PlaylistLoader.LIVE_EVENTS_ID ||
            category.id == PlaylistLoader.CONTINUE_ID ||
            category.id == PlaylistLoader.TOP_WATCHED_ID ||
            (groupCounts[category.id] ?: 0) > 0
    }
}

internal fun BrowseActivity.prepareGroup(channels: List<Channel>, config: LineupConfig): List<Channel> {
    val hidden = config.hiddenChannels.orEmpty()
    val hiddenSet = if (hidden.isEmpty()) emptySet() else hidden.toSet()
    val now = System.currentTimeMillis()
    val isLiveGuide = section == GuideSection.TV
    val out = ArrayList<Channel>(channels.size)
    for (channel in channels) {
        if (channel.id in hiddenSet) continue
        if (isLiveGuide && channel.kind == com.olympus.iptv.data.ContentKind.LIVE) {
            val cachedEpg = epgCache[channel.id]?.now ?: epgCache[channel.id]?.next
            val status = com.olympus.iptv.data.NameStrip.inspect(
                rawChannel = channel.name,
                groupTitle = channel.groupTitle,
                epgChannelId = channel.epgChannelId,
                nowTitle = cachedEpg?.title,
                nowStartMs = cachedEpg?.startMillis ?: 0L,
                nowEndMs = cachedEpg?.endMillis ?: 0L
            )
            // Automatically hide `NO EVENT` / off-air slots and restore them 30 min prior to airtime
            if (!status.isVisibleAt(now)) continue
        }
        val name = ChannelNames.present(channel, config)
        out.add(if (name == channel.name) channel else channel.copy(name = name))
    }
    return out
}


internal fun BrowseActivity.liveEventsHubChannels(config: LineupConfig): List<Channel> {
    val saved = playlist ?: return emptyList()
    val hiddenSet = config.hiddenChannels.orEmpty().toSet()
    val raw = (application as OlympusIptvApp).catalogDb.readLiveEventsHub(saved.id, hiddenSet)
    val grouped = LinkedHashMap<String, MutableList<Channel>>()
    for (ch in raw) {
        val eventName = ch.name.substringBeforeLast(" (").trim()
        val key = com.olympus.iptv.playlist.SimilarChannels.key(eventName).ifBlank { ch.id }
        grouped.getOrPut(key) { ArrayList(4) }.add(ch)
    }
    val out = ArrayList<Channel>(raw.size)
    for ((_, feeds) in grouped) {
        feeds.sortWith(
            compareByDescending<Channel> { "4k" in it.name.lowercase() || "uhd" in it.name.lowercase() }
                .thenByDescending { "fhd" in it.name.lowercase() || "1080" in it.name.lowercase() }
                .thenBy { it.name }
        )
        out.addAll(feeds)
    }
    return out
}
