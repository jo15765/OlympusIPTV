package com.olympus.iptv.playlist

import com.olympus.iptv.data.Category
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.ContentKind
import java.util.UUID

data class M3uPlaylist(
    val channels: List<Channel>,
    val categories: List<Category>,
    val epgUrl: String? = null
)

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(content: String): M3uPlaylist {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        var epgUrl: String? = null
        val channels = mutableListOf<Channel>()
        var pendingInfo: ExtInf? = null
        var userAgent: String? = null
        var referer: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val body = line.substringAfter(':').trim()
                    val eq = body.indexOf('=')
                    if (eq > 0) {
                        val key = body.substring(0, eq).trim().lowercase()
                        val value = body.substring(eq + 1).trim()
                        when (key) {
                            "http-user-agent" -> userAgent = value.ifBlank { null }
                            "http-referrer", "http-referer" -> referer = value.ifBlank { null }
                        }
                    }
                }
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epgUrl = attrs["url-tvg"]
                        ?: attrs["x-tvg-url"]
                        ?: attrs["tvg-url"]
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = parseExtInf(line)
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    val name = info?.name?.ifBlank { line } ?: line
                    val group = info?.groupTitle?.ifBlank { null } ?: "Uncategorized"
                    val tvgId = info?.tvgId?.ifBlank { null }
                    channels += Channel(
                        id = tvgId ?: UUID.randomUUID().toString(),
                        name = name,
                        streamUrl = line,
                        logoUrl = info?.logo,
                        groupTitle = group,
                        categoryId = group,
                        kind = ContentKind.LIVE,
                        epgChannelId = tvgId,
                        catchupType = info?.catchupType,
                        catchupSource = info?.catchupSource,
                        catchupDays = info?.catchupDays,
                        userAgent = userAgent,
                        httpReferer = referer
                    )
                    pendingInfo = null
                    userAgent = null
                    referer = null
                }
            }
        }

        val categories = channels
            .mapNotNull { it.groupTitle }
            .distinct()
            .sorted()
            .map { Category(id = it, name = it, kind = ContentKind.LIVE) }

        return M3uPlaylist(channels = channels, categories = categories, epgUrl = epgUrl)
    }

    private fun parseExtInf(line: String): ExtInf {
        val comma = line.lastIndexOf(',')
        val name = if (comma >= 0) line.substring(comma + 1).trim() else "Channel"
        val attrPart = if (comma >= 0) line.substring(0, comma) else line
        val attrs = attrRegex.findAll(attrPart).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val days = attrs["catchup-days"]?.toIntOrNull()
            ?: attrs["timeshift"]?.toIntOrNull()
        return ExtInf(
            name = name,
            tvgId = attrs["tvg-id"],
            logo = attrs["tvg-logo"],
            groupTitle = attrs["group-title"],
            catchupType = attrs["catchup"]?.takeIf { it.isNotBlank() },
            catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
            catchupDays = days
        )
    }

    private data class ExtInf(
        val name: String,
        val tvgId: String?,
        val logo: String?,
        val groupTitle: String?,
        val catchupType: String?,
        val catchupSource: String?,
        val catchupDays: Int?
    )
}
