package com.olympus.iptv.playlist

import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ChannelNames
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.GroupRename
import com.olympus.iptv.data.LineupConfig

object LineupApplier {
    fun apply(loaded: LoadedPlaylist, config: LineupConfig): LoadedPlaylist {
        val hiddenGroups = config.hiddenGroups.orEmpty().toSet()
        val hiddenChannels = config.hiddenChannels.orEmpty().toSet()
        val groupRank = config.groupOrder.orEmpty().withIndex().associate { it.value to it.index }
        val channelRanks = config.channelOrder.orEmpty().mapValues { (_, ids) ->
            ids.withIndex().associate { it.value to it.index }
        }

        val hiddenCountries = config.hiddenCountries.orEmpty().toSet()

        fun visible(channel: Channel): Boolean {
            if (channel.id in hiddenChannels) return false
            val groupId = channel.categoryId ?: channel.groupTitle
            if (groupId != null && groupId in hiddenGroups) return false
            return !countryBlocked(channel, hiddenCountries, config.includeInternational)
        }

        fun rename(channel: Channel): Channel {
            val groupId = channel.categoryId ?: channel.groupTitle
            val groupName = groupId?.let { GroupRename.shown(it, channel.groupTitle.orEmpty(), config) } ?: channel.groupTitle
            return channel.copy(
                name = ChannelNames.present(channel, config),
                groupTitle = groupName
            )
        }

        fun sortChannels(channels: List<Channel>): List<Channel> {
            return channels.withIndex().sortedWith(
                compareBy(
                    { groupRank[it.value.categoryId] ?: groupRank[it.value.groupTitle] ?: Int.MAX_VALUE },
                    {
                        val groupId = it.value.categoryId ?: it.value.groupTitle
                        channelRanks[groupId]?.get(it.value.id) ?: Int.MAX_VALUE
                    },
                    { it.index }
                )
            ).map { it.value }
        }

        fun sortGroups(groups: List<Category>): List<Category> {
            val special = groups.filter {
                it.id == PlaylistLoader.ALL_ID || it.id == PlaylistLoader.FAVORITES_ID
            }
            val rest = groups.withIndex().filter {
                val id = it.value.id
                id != PlaylistLoader.ALL_ID &&
                    id != PlaylistLoader.FAVORITES_ID &&
                    id !in hiddenGroups &&
                    !countryHidden(it.value.name, hiddenCountries, it.value.kind, config.includeInternational, null)
            }.sortedWith(
                compareBy({ groupRank[it.value.id] ?: Int.MAX_VALUE }, { it.index })
            ).map { indexed ->
                indexed.value.copy(name = GroupRename.shown(indexed.value.id, indexed.value.name, config))
            }
            return special + rest
        }

        val liveChannels = sortChannels(loaded.liveChannels.filter(::visible).map(::rename))
        val vodChannels = sortChannels(loaded.vodChannels.filter(::visible).map(::rename))
        val seriesChannels = sortChannels(loaded.seriesChannels.orEmpty().filter(::visible).map(::rename))
        return loaded.copy(
            liveCategories = occupied(sortGroups(loaded.liveCategories), liveChannels),
            vodCategories = occupied(sortGroups(loaded.vodCategories), vodChannels),
            seriesCategories = occupied(sortGroups(loaded.seriesCategories.orEmpty()), seriesChannels),
            liveChannels = liveChannels,
            vodChannels = vodChannels,
            seriesChannels = seriesChannels
        )
    }

    fun groups(
        categories: List<Category>,
        config: LineupConfig,
        countries: Map<String, String> = emptyMap()
    ): List<Category> {
        val hiddenGroups = config.hiddenGroups.orEmpty().toSet()
        val hiddenCountries = config.hiddenCountries.orEmpty().toSet()
        val groupRank = config.groupOrder.orEmpty().withIndex().associate { it.value to it.index }
        return categories.withIndex()
            .filter { indexed ->
                val category = indexed.value
                category.id != PlaylistLoader.ALL_ID &&
                    category.id != PlaylistLoader.FAVORITES_ID &&
                    category.id !in hiddenGroups &&
                    !countryHidden(
                        category.name,
                        hiddenCountries,
                        category.kind,
                        config.includeInternational,
                        countries[category.id]
                    )
            }
            .sortedWith(compareBy({ groupRank[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
            .map { indexed ->
                val name = GroupRename.shown(indexed.value.id, indexed.value.name, config)
                if (name == indexed.value.name) indexed.value else indexed.value.copy(name = name)
            }
    }

    fun present(channels: List<Channel>, config: LineupConfig): List<Channel> {
        if (channels.isEmpty()) return channels
        val hiddenChannels = config.hiddenChannels.orEmpty().toSet()
        val hiddenGroups = config.hiddenGroups.orEmpty().toSet()
        val hiddenCountries = config.hiddenCountries.orEmpty().toSet()
        val kept = if (config.includeInternational == true && hiddenChannels.isEmpty() && hiddenGroups.isEmpty() && hiddenCountries.isEmpty()) {
            channels
        } else {
            channels.filter { visibleChannel(it, hiddenChannels, hiddenGroups, hiddenCountries, config.includeInternational) }
        }
        val channelNames = config.channelNames.orEmpty()
        val groupNames = config.groupNames.orEmpty()
        val prefixes = config.namePrefixes.orEmpty()
        val suffixes = config.nameSuffixes.orEmpty()
        val rule = config.groupFind?.isNotBlank() == true
        val renamed = if (channelNames.isEmpty() && groupNames.isEmpty() && prefixes.isEmpty() && suffixes.isEmpty() && !rule) {
            kept
        } else {
            kept.map { channel ->
                val groupId = channel.categoryId ?: channel.groupTitle
                val groupName = groupId?.let { GroupRename.shown(it, channel.groupTitle.orEmpty(), config) }
                val name = ChannelNames.present(channel, config)
                if (name == channel.name && (groupName == null || groupName == channel.groupTitle)) channel
                else channel.copy(name = name, groupTitle = groupName ?: channel.groupTitle)
            }
        }
        return sortSlice(renamed, config)
    }

    fun visibleChannel(channel: Channel, config: LineupConfig): Boolean {
        return visibleChannel(
            channel,
            config.hiddenChannels.orEmpty().toSet(),
            config.hiddenGroups.orEmpty().toSet(),
            config.hiddenCountries.orEmpty().toSet(),
            config.includeInternational
        )
    }

    fun keep(
        channel: Channel,
        hiddenChannels: Set<String>,
        hiddenGroups: Set<String>,
        hiddenCountries: Set<String>,
        includeInternational: Boolean? = false
    ): Boolean = visibleChannel(channel, hiddenChannels, hiddenGroups, hiddenCountries, includeInternational)

    private fun visibleChannel(
        channel: Channel,
        hiddenChannels: Set<String>,
        hiddenGroups: Set<String>,
        hiddenCountries: Set<String>,
        includeInternational: Boolean?
    ): Boolean {
        if (channel.id in hiddenChannels) return false
        val groupId = channel.categoryId ?: channel.groupTitle
        if (groupId != null && groupId in hiddenGroups) return false
        return !countryBlocked(channel, hiddenCountries, includeInternational)
    }

    private fun countryBlocked(channel: Channel, hiddenCountries: Set<String>, includeInternational: Boolean?): Boolean {
        val countryId = if (channel.kind == ContentKind.LIVE) {
            channel.countryId ?: CountryIndex.idFor(channel.groupTitle) ?: CountryIndex.OTHER_ID
        } else {
            CountryIndex.idFor(channel.groupTitle)
        }
        if (includeInternational != true && countryId != null && countryId != "us") return true
        if (countryId == null || hiddenCountries.isEmpty()) return false
        return countryId in hiddenCountries
    }

    private fun sortSlice(channels: List<Channel>, config: LineupConfig): List<Channel> {
        val groupOrder = config.groupOrder.orEmpty()
        val channelOrder = config.channelOrder.orEmpty()
        if ((groupOrder.isEmpty() && channelOrder.isEmpty()) || channels.size < 2) return channels
        val groupRank = if (groupOrder.isEmpty()) emptyMap() else groupOrder.withIndex().associate { it.value to it.index }
        val groupsInView = HashSet<String>()
        for (channel in channels) {
            val id = channel.categoryId ?: channel.groupTitle ?: continue
            groupsInView.add(id)
        }
        val ranks = HashMap<String, Map<String, Int>>(groupsInView.size)
        for (groupId in groupsInView) {
            val ids = channelOrder[groupId] ?: continue
            if (ids.isEmpty()) continue
            val rank = HashMap<String, Int>(ids.size)
            ids.forEachIndexed { index, id -> rank[id] = index }
            ranks[groupId] = rank
        }
        if (groupRank.isEmpty() && ranks.isEmpty()) return channels
        return channels.withIndex().sortedWith(
            compareBy(
                { groupRank[it.value.categoryId] ?: groupRank[it.value.groupTitle] ?: Int.MAX_VALUE },
                {
                    val groupId = it.value.categoryId ?: it.value.groupTitle
                    ranks[groupId]?.get(it.value.id) ?: Int.MAX_VALUE
                },
                { it.index }
            )
        ).map { it.value }
    }

    private fun countryHidden(
        groupName: String,
        hiddenCountries: Set<String>,
        kind: com.olympus.iptv.data.ContentKind,
        includeInternational: Boolean?,
        resolved: String?
    ): Boolean {
        val countryId = resolved?.takeIf { it.isNotBlank() } ?: CountryIndex.idFor(groupName)
        if (includeInternational != true) {
            if (countryId == null) return kind == ContentKind.LIVE
            if (countryId != "us") return true
        }
        if (hiddenCountries.isEmpty()) return false
        if (countryId == null) {
            return kind == ContentKind.LIVE && CountryIndex.OTHER_ID in hiddenCountries
        }
        return countryId in hiddenCountries
    }

    private fun occupied(groups: List<Category>, channels: List<Channel>): List<Category> {
        val used = channels.mapNotNull { it.categoryId }.toSet()
        return groups.filter { group ->
            group.id == PlaylistLoader.ALL_ID ||
                group.id == PlaylistLoader.FAVORITES_ID ||
                group.id in used
        }
    }
}
