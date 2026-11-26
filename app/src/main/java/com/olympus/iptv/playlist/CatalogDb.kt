package com.olympus.iptv.playlist

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.NameImages
import com.olympus.iptv.data.NameStrip
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CatalogDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "olympus-iptv.db", null, 18) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val lowRamDevice = com.olympus.iptv.ui.DeviceForm.isLowRam(appContext)
    private val writer = ReentrantLock()
    private val dashLock = Any()
    @Volatile private var dashHold: DashHold? = null
    @Volatile private var liveCountryHold: LiveCountryHold? = null
    @Volatile private var fastCountryHold: FastCountryHold? = null
    @Volatile private var searchExportHold: SearchExportHold? = null
    @Volatile private var sorted247Hold: Sorted247Hold? = null

    private inline fun <T> read(block: () -> T): T {
        try {
            return block()
        } catch (oom: OutOfMemoryError) {
            releaseSearchExport()
            throw oom
        }
    }

    private inline fun <T> write(block: () -> T): T = writer.withLock(block)

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        applyPragma(db, "PRAGMA auto_vacuum=INCREMENTAL")
        applyPragma(db, "PRAGMA synchronous=NORMAL")
        applyPragma(db, "PRAGMA temp_store=MEMORY")
        applyPragma(db, "PRAGMA busy_timeout=3000")
        applyPragma(db, if (lowRamDevice) "PRAGMA cache_size=-6144" else "PRAGMA cache_size=-16384")
        applyPragma(db, if (lowRamDevice) "PRAGMA mmap_size=16777216" else "PRAGMA mmap_size=33554432")
        db.enableWriteAheadLogging()
        db.setMaxSqlCacheSize(64)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS library_meta (playlist_id TEXT PRIMARY KEY, stamp INTEGER NOT NULL)"
        )
        ensureMetaMissTable(db)
        ensureStreamHealthTable(db)
    }

    private fun ensureStreamHealthTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stream_health (
                playlist_id TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                failures INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, channel_id)
            )
            """.trimIndent()
        )
    }

    @Volatile private var deadHold: Pair<String, Set<String>>? = null

    fun invalidateDeadCache() {
        deadHold = null
    }

    fun recordStreamFailure(playlistId: String, channelId: String): Int = write {
        val db = writableDatabase
        val current = db.rawQuery(
            "SELECT failures FROM stream_health WHERE playlist_id=? AND channel_id=?",
            arrayOf(playlistId, channelId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val next = current + 1
        if (current == 0) {
            db.execSQL(
                "INSERT INTO stream_health (playlist_id, channel_id, failures) VALUES (?, ?, ?)",
                arrayOf(playlistId, channelId, next.toString())
            )
        } else {
            db.execSQL(
                "UPDATE stream_health SET failures=? WHERE playlist_id=? AND channel_id=?",
                arrayOf(next.toString(), playlistId, channelId)
            )
        }
        deadHold = null
        next
    }

    fun deadChannelIds(playlistId: String, minimum: Int = 2): Set<String> {
        if (minimum == 2) {
            deadHold?.takeIf { it.first == playlistId }?.let { return it.second }
        }
        return read {
            val ids = HashSet<String>()
            readableDatabase.rawQuery(
                "SELECT channel_id FROM stream_health WHERE playlist_id=? AND failures>=?",
                arrayOf(playlistId, minimum.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getString(0))
            }
            if (minimum == 2) deadHold = playlistId to ids
            ids
        }
    }

    private fun loadCachedEpgNow(
        db: SQLiteDatabase,
        playlistId: String,
        now: Long,
        maxAgeMs: Long = 0L
    ): Map<String, Triple<String, Long, Long>> {
        val out = HashMap<String, Triple<String, Long, Long>>()
        runCatching {
            val sql = if (maxAgeMs > 0L) {
                "SELECT channel_id, payload FROM epg_cache WHERE playlist_id=? AND fetched_at >= ?"
            } else {
                "SELECT channel_id, payload FROM epg_cache WHERE playlist_id=?"
            }
            val args = if (maxAgeMs > 0L) {
                arrayOf(playlistId, (now - maxAgeMs).toString())
            } else {
                arrayOf(playlistId)
            }
            db.rawQuery(sql, args).use { cur ->
                while (cur.moveToNext()) {
                    val cid = cur.getString(0) ?: continue
                    val payload = cur.getString(1) ?: continue
                    val programs = decodeEpg(payload)
                    val current = programs.firstOrNull { now >= it.startMillis && now < it.endMillis + NameStrip.POST_EVENT_GRACE_MS }
                        ?: programs.firstOrNull { it.startMillis > now }
                    if (current != null) {
                        out[cid] = Triple(current.title, current.startMillis, current.endMillis)
                    }
                }
            }
        }
        return out
    }

    private fun liveEventWindowSql(): String =
        "(is_event_slot = 0 OR event_start_ms = -1 OR (event_start_ms > 0 AND ? >= event_start_ms AND ? < event_end_ms))"

    fun readLiveEventsHub(
        playlistId: String,
        hiddenChannels: Set<String>,
        now: Long = System.currentTimeMillis()
    ): List<com.olympus.iptv.data.Channel> = read {
        val out = ArrayList<com.olympus.iptv.data.Channel>()
        val db = readableDatabase
        val preWindow = now + NameStrip.PRE_EVENT_WINDOW_MS
        val postGraceCutoff = now - NameStrip.POST_EVENT_GRACE_MS
        val epgNowByChannel = loadCachedEpgNow(db, playlistId, now, maxAgeMs = 6L * 3_600_000L)
        db.query(
            "channels",
            LIST_COLUMNS,
            """
            playlist_id=? AND bucket=?
            AND name NOT LIKE '%24/7%'
            AND IFNULL(group_title, '') NOT LIKE '%24/7%'
            AND ${liveEventWindowSql()}
            """.trimIndent(),
            arrayOf(playlistId, LIVE, preWindow.toString(), postGraceCutoff.toString()),
            null,
            null,
            "name COLLATE NOCASE"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val ch = listRow(cursor)
                if (ch.id in hiddenChannels) continue
                val cachedEpg = epgNowByChannel[ch.epgChannelId ?: ch.id] ?: epgNowByChannel[ch.id]
                val status = NameStrip.inspect(
                    rawChannel = ch.name,
                    groupTitle = ch.groupTitle,
                    epgChannelId = ch.epgChannelId,
                    nowTitle = cachedEpg?.first,
                    nowStartMs = cachedEpg?.second ?: 0L,
                    nowEndMs = cachedEpg?.third ?: 0L
                )
                if (!status.isVisibleAt(now)) continue

                val is247 = ch.name.contains("24/7") ||
                    ch.groupTitle?.contains("24/7") == true ||
                    cachedEpg?.first?.contains("24/7") == true
                if (is247) continue
                val eventTitle = status.airingEvent ?: cachedEpg?.first ?: status.cleanChannel
                val isSportsOrEvent = status.isEventSlot ||
                    com.olympus.iptv.ui.browse.SportsEvent.isMatchup(eventTitle)
                if (!isSportsOrEvent) continue
                val display = if (!status.airingEvent.isNullOrBlank()) {
                    "${status.airingEvent} (${status.cleanChannel})"
                } else {
                    status.cleanChannel
                }
                out.add(ch.copy(name = display))
            }
        }
        out
    }

    fun sampleChannelIds(playlistId: String, group: String, limit: Int = 8): List<String> = read {
        val ids = ArrayList<String>(limit)
        readableDatabase.rawQuery(
            """
            SELECT id FROM channels
            WHERE playlist_id=? AND bucket=? AND (category_id=? OR group_title=?)
            LIMIT $limit
            """.trimIndent(),
            arrayOf(playlistId, LIVE, group, group)
        ).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        ids
    }

    fun clearStreamFailure(playlistId: String, channelId: String) {
        write {
            writableDatabase.delete(
                "stream_health",
                "playlist_id=? AND channel_id=?",
                arrayOf(playlistId, channelId)
            )
        }
    }

    private fun ensureMetaMissTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS meta_miss (
                cache_key TEXT PRIMARY KEY NOT NULL,
                saved_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun isMetaMiss(cacheKey: String, ttlMs: Long = META_MISS_TTL_MS): Boolean {
        if (cacheKey.isBlank()) return false
        val savedAt = read {
            readableDatabase.rawQuery(
                "SELECT saved_at FROM meta_miss WHERE cache_key=?",
                arrayOf(cacheKey)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } ?: return false
        if (System.currentTimeMillis() - savedAt > ttlMs) {
            clearMetaMiss(cacheKey)
            return false
        }
        return true
    }

    fun putMetaMiss(cacheKey: String) {
        if (cacheKey.isBlank()) return
        write {
            val values = ContentValues().apply {
                put("cache_key", cacheKey)
                put("saved_at", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("meta_miss", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun clearMetaMiss(cacheKey: String) {
        if (cacheKey.isBlank()) return
        write {
            writableDatabase.delete("meta_miss", "cache_key=?", arrayOf(cacheKey))
        }
    }

    private fun applyPragma(db: SQLiteDatabase, sql: String) {
        db.rawQuery(sql, null).use { it.moveToFirst() }
    }

    data class PageCursor(val number: Int?, val name: String, val id: String)

    data class ParsedNames(
        val programs: Map<String, List<com.olympus.iptv.data.EpgProgram>>,
        val examples: List<Pair<String, String>>
    )

    data class ImageStrip(val changed: Int, val examples: List<Pair<String, String>>)

    data class Added(
        val categories: List<Category> = emptyList(),
        val channels: List<com.olympus.iptv.data.Channel> = emptyList()
    )

    fun wipeAll() = write {
        val db = writableDatabase
        db.delete("channels", null, null)
        db.delete("categories", null, null)
        db.delete("epg_cache", null, null)
        db.delete("watched", null, null)
        db.delete("library_meta", null, null)
        runCatching { db.execSQL("INSERT INTO channels_fts(channels_fts) VALUES('rebuild')") }
        dashHold = null
        liveCountryHold = null
        searchExportHold = null
        sorted247Hold = null
        runCatching { dashFile().delete() }
        runCatching {
            filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("search-export-")) file.delete()
            }
        }
        runCatching { File(filesDir, "group-images.stamp").delete() }
    }

    fun readBucket(playlistId: String, bucket: String): Pair<List<Category>, List<com.olympus.iptv.data.Channel>> =
        read {
            val db = readableDatabase
            readCategories(db, playlistId, bucket) to readChannels(db, playlistId, bucket)
        }

    fun read(playlistId: String): LoadedPlaylist = read {
        val db = readableDatabase
        LoadedPlaylist(
            liveCategories = readCategories(db, playlistId, LIVE),
            liveChannels = emptyList(),
            vodCategories = readCategories(db, playlistId, VOD),
            vodChannels = emptyList(),
            seriesCategories = readCategories(db, playlistId, SERIES),
            seriesChannels = emptyList()
        )
    }

    fun readChannelPage(
        playlistId: String,
        bucket: String,
        limit: Int,
        after: PageCursor?
    ): List<com.olympus.iptv.data.Channel> = read {
        pageAfter(readableDatabase, playlistId, bucket, null, limit, after)
    }

    fun readCategoryPage(
        playlistId: String,
        bucket: String,
        categoryId: String,
        limit: Int,
        after: PageCursor?
    ): List<com.olympus.iptv.data.Channel> = read {
        pageAfter(readableDatabase, playlistId, bucket, categoryId, limit, after)
    }

    data class VisiblePage(
        val channels: List<com.olympus.iptv.data.Channel>,
        val cursor: PageCursor?,
        val exhausted: Boolean
    )

    fun readVisiblePage(
        playlistId: String,
        bucket: String,
        categoryId: String?,
        limit: Int,
        after: PageCursor?,
        hiddenChannels: Set<String>,
        hiddenGroups: Set<String>,
        hiddenCountries: Set<String>,
        includeInternational: Boolean?
    ): VisiblePage = read {
        val db = readableDatabase
        val filter = visibilitySql(bucket, hiddenChannels, hiddenGroups, hiddenCountries, includeInternational)
        val visible = ArrayList<com.olympus.iptv.data.Channel>(limit)
        var cursor = after
        var exhausted = false
        var rounds = 0
        while (visible.size < limit && rounds < 30) {
            rounds += 1
            val need = (limit - visible.size).coerceAtLeast(1)
            val ask = if (filter.sql.isBlank()) need else (need * 3).coerceAtMost(limit * 3).coerceAtLeast(need)
            val raw = pageAfter(db, playlistId, bucket, categoryId, ask, cursor, filter)
            if (raw.isEmpty()) {
                exhausted = true
                break
            }
            var last: com.olympus.iptv.data.Channel? = null
            var filled = false
            for (channel in raw) {
                last = channel
                if (LineupApplier.keep(channel, hiddenChannels, hiddenGroups, hiddenCountries, includeInternational)) {
                    visible.add(channel)
                    if (visible.size == limit) {
                        filled = true
                        break
                    }
                }
            }
            if (last != null) cursor = PageCursor(last.number, last.name, last.id)
            if (filled) break
            if (raw.size < ask) {
                exhausted = true
                break
            }
        }
        VisiblePage(visible, cursor, exhausted)
    }

    fun readGroupPage(
        playlistId: String,
        bucket: String,
        categoryId: String,
        limit: Int,
        after: PageCursor?,
        hiddenChannels: Set<String> = emptySet(),
        hiddenCountries: Set<String> = emptySet(),
        includeInternational: Boolean? = true
    ): VisiblePage = read {
        if (bucket == LIVE && categoryId == TwentyFourSeven.GROUP_ID) {
            return@read read247Page(
                readableDatabase,
                playlistId,
                limit,
                after,
                hiddenChannels,
                hiddenCountries,
                includeInternational
            )
        }
        if (bucket == LIVE && categoryId == AdultGroup.GROUP_ID) {
            return@read readAdultPage(
                readableDatabase,
                playlistId,
                limit,
                after,
                hiddenChannels
            )
        }
        val db = readableDatabase
        val now = System.currentTimeMillis()
        val preWindow = now + NameStrip.PRE_EVENT_WINDOW_MS
        val postGraceCutoff = now - NameStrip.POST_EVENT_GRACE_MS
        val liveFilterSql = if (bucket == LIVE) {
            " AND UPPER(name) NOT LIKE '%: NO EVENT%' AND UPPER(name) NOT LIKE '%| NO EVENT%'" +
                " AND ${liveEventWindowSql()}"
        } else {
            ""
        }
        val liveFilterArgs = if (bucket == LIVE) {
            listOf(preWindow.toString(), postGraceCutoff.toString())
        } else {
            emptyList()
        }
        val hide = if (hiddenChannels.isEmpty()) {
            SqlFilter(liveFilterSql, liveFilterArgs)
        } else {
            val ids = hiddenChannels.filter { it.isNotBlank() }.take(400)
            if (ids.isEmpty()) {
                SqlFilter(liveFilterSql, liveFilterArgs)
            } else {
                SqlFilter(
                    "$liveFilterSql AND id NOT IN (${ids.joinToString(",") { "?" }})",
                    liveFilterArgs + ids
                )
            }
        }
        val visible = ArrayList<com.olympus.iptv.data.Channel>(limit)
        var cursor = after
        var exhausted = false
        var rounds = 0
        while (visible.size < limit && rounds < 20) {
            rounds += 1
            val need = (limit - visible.size).coerceAtLeast(1)
            val raw = pageAfter(db, playlistId, bucket, categoryId, need, cursor, hide)
            if (raw.isEmpty()) {
                exhausted = true
                break
            }
            for (channel in raw) {
                visible.add(channel)
                cursor = PageCursor(channel.number, channel.name, channel.id)
                if (visible.size == limit) break
            }
            if (visible.size == limit) break
            if (raw.size < need) {
                exhausted = true
                break
            }
        }
        VisiblePage(visible, cursor, exhausted)
    }

    fun countryChoices(playlistId: String, groups: List<DashboardGroup> = dashboardGroups(playlistId)): List<CountryOption> {
        val summed = HashMap<String, Int>()
        for (group in groups) {
            if (group.kind != "LIVE") continue
            val id = group.countryId.ifEmpty { CountryIndex.OTHER_ID }
            summed[id] = (summed[id] ?: 0) + group.count
        }
        val known = CountryIndex.directory()
        val seen = HashSet<String>(known.size + summed.size)
        val out = ArrayList<CountryOption>(known.size + summed.size)
        for (country in known) {
            seen.add(country.id)
            out.add(CountryOption(country.id, country.label, summed[country.id] ?: 0))
        }
        for ((id, count) in summed) {
            if (id in seen) continue
            val label = if (id == CountryIndex.OTHER_ID) "Other" else id
            out.add(CountryOption(id, label, count))
        }
        out.sortWith(compareBy({ it.id == CountryIndex.OTHER_ID }, { it.label.lowercase() }))
        return out
    }

    fun readCategoriesOnly(playlistId: String, bucket: String): List<Category> =
        read { readCategories(readableDatabase, playlistId, bucket) }

    fun readCategoryChannels(playlistId: String, bucket: String, categoryId: String): List<com.olympus.iptv.data.Channel> =
        read {
            if (bucket == LIVE && categoryId == TwentyFourSeven.GROUP_ID) {
                return@read read247Channels(readableDatabase, playlistId, emptySet(), emptySet(), true)
            }
            if (bucket == LIVE && categoryId == AdultGroup.GROUP_ID) {
                return@read readAdultChannels(readableDatabase, playlistId, emptySet())
            }
            if (bucket == LIVE) {
                val now = System.currentTimeMillis()
                val preWindow = now + NameStrip.PRE_EVENT_WINDOW_MS
                val postGraceCutoff = now - NameStrip.POST_EVENT_GRACE_MS
                return@read readableDatabase.query(
                    "channels",
                    LIST_COLUMNS,
                    "playlist_id=? AND bucket=? AND (category_id=? OR group_title=?) " +
                        "AND UPPER(name) NOT LIKE '%: NO EVENT%' AND UPPER(name) NOT LIKE '%| NO EVENT%' " +
                        "AND ${liveEventWindowSql()}",
                    arrayOf(playlistId, bucket, categoryId, categoryId, preWindow.toString(), postGraceCutoff.toString()),
                    null,
                    null,
                    "number, name COLLATE NOCASE"
                ).use { collectList(it) }
            }
            readableDatabase.query(
                "channels",
                LIST_COLUMNS,
                "playlist_id=? AND bucket=? AND (category_id=? OR group_title=?)",
                arrayOf(playlistId, bucket, categoryId, categoryId),
                null,
                null,
                "number, name COLLATE NOCASE"
            ).use { collectList(it) }
        }

    private fun readAdultPage(
        db: SQLiteDatabase,
        playlistId: String,
        limit: Int,
        after: PageCursor?,
        hiddenChannels: Set<String>
    ): VisiblePage {
        val all = readAdultChannels(db, playlistId, hiddenChannels)
        val start = if (after == null) {
            0
        } else {
            val idx = all.indexOfFirst { it.id == after.id }
            if (idx >= 0) idx + 1 else all.size
        }
        val slice = if (start >= all.size) emptyList() else all.subList(start, (start + limit).coerceAtMost(all.size))
        val last = slice.lastOrNull()
        val hasMore = start + slice.size < all.size
        val next = if (hasMore && last != null) PageCursor(null, last.name, last.id) else null
        return VisiblePage(slice, next, hasMore)
    }

    private fun readAdultChannels(
        db: SQLiteDatabase,
        playlistId: String,
        hiddenChannels: Set<String>
    ): List<com.olympus.iptv.data.Channel> {
        val out = ArrayList<com.olympus.iptv.data.Channel>()
        db.query(
            "channels",
            LIST_COLUMNS,
            "playlist_id=? AND bucket=? AND ${AdultGroup.SQL_MATCH}",
            arrayOf(playlistId, LIVE),
            null,
            null,
            "name COLLATE NOCASE, id ASC"
        ).use { cur ->
            while (cur.moveToNext()) {
                val ch = listRow(cur)
                if (ch.id in hiddenChannels) continue
                out.add(ch.copy(categoryId = AdultGroup.GROUP_ID, groupTitle = AdultGroup.GROUP_NAME))
            }
        }
        return out
    }

    private fun read247Page(
        db: SQLiteDatabase,
        playlistId: String,
        limit: Int,
        after: PageCursor?,
        hiddenChannels: Set<String>,
        hiddenCountries: Set<String>,
        includeInternational: Boolean?
    ): VisiblePage {
        val visible = ArrayList<com.olympus.iptv.data.Channel>(limit)
        var cursorSort = after?.name
        var cursorId = after?.id
        var exhausted = false
        var rounds = 0
        val cols = LIST_COLUMNS + "sort_247"
        val sortColIdx = LIST_COLUMNS.size
        while (visible.size < limit && rounds < 20) {
            rounds += 1
            val fetchLimit = ((limit - visible.size) * 2).coerceAtLeast(40).coerceAtMost(200)
            val where = StringBuilder(
                "playlist_id=? AND bucket=? AND is_247=1 " +
                    "AND UPPER(name) NOT LIKE '%: NO EVENT%' AND UPPER(name) NOT LIKE '%| NO EVENT%'"
            )
            val args = ArrayList<String>(6).apply {
                add(playlistId)
                add(LIVE)
            }
            if (cursorSort != null && cursorId != null) {
                where.append(" AND (sort_247 > ? OR (sort_247 = ? AND id > ?))")
                args.add(cursorSort)
                args.add(cursorSort)
                args.add(cursorId)
            }
            var rawCount = 0
            db.query(
                "channels",
                cols,
                where.toString(),
                args.toTypedArray(),
                null,
                null,
                "sort_247 ASC, id ASC",
                fetchLimit.toString()
            ).use { cur ->
                while (cur.moveToNext()) {
                    rawCount += 1
                    val ch = listRow(cur)
                    val sortKey = cur.getString(sortColIdx) ?: ""
                    cursorSort = sortKey
                    cursorId = ch.id
                    if (!LineupApplier.keep(ch, hiddenChannels, emptySet(), hiddenCountries, includeInternational)) continue
                    visible.add(ch.copy(categoryId = TwentyFourSeven.GROUP_ID, groupTitle = TwentyFourSeven.GROUP_NAME))
                    if (visible.size == limit) break
                }
            }
            if (rawCount < fetchLimit) {
                exhausted = true
                break
            }
        }
        val nextCursor = if (cursorSort != null && cursorId != null) {
            PageCursor(null, cursorSort, cursorId)
        } else {
            after
        }
        return VisiblePage(visible, nextCursor, exhausted)
    }

    private fun read247Channels(
        db: SQLiteDatabase,
        playlistId: String,
        hiddenChannels: Set<String>,
        hiddenCountries: Set<String>,
        includeInternational: Boolean?
    ): List<com.olympus.iptv.data.Channel> {
        val stamp = libraryStamp(playlistId)
        val hiddenHash = hiddenChannels.hashCode()
        val countriesHash = hiddenCountries.hashCode()
        sorted247Hold?.let { held ->
            if (held.playlistId == playlistId &&
                held.stamp == stamp &&
                held.hiddenHash == hiddenHash &&
                held.countriesHash == countriesHash &&
                held.includeInternational == includeInternational
            ) {
                return held.channels
            }
        }
        val out = ArrayList<com.olympus.iptv.data.Channel>()
        db.query(
            "channels",
            LIST_COLUMNS,
            "playlist_id=? AND bucket=? AND is_247=1 " +
                "AND UPPER(name) NOT LIKE '%: NO EVENT%' AND UPPER(name) NOT LIKE '%| NO EVENT%'",
            arrayOf(playlistId, LIVE),
            null,
            null,
            "sort_247 ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val ch = listRow(cursor)
                if (!LineupApplier.keep(ch, hiddenChannels, emptySet(), hiddenCountries, includeInternational)) continue
                out.add(ch.copy(categoryId = TwentyFourSeven.GROUP_ID, groupTitle = TwentyFourSeven.GROUP_NAME))
            }
        }
        sorted247Hold = Sorted247Hold(
            playlistId = playlistId,
            stamp = stamp,
            hiddenHash = hiddenHash,
            countriesHash = countriesHash,
            includeInternational = includeInternational,
            channels = out
        )
        return out
    }

    fun readChannel(playlistId: String, bucket: String, channelId: String): com.olympus.iptv.data.Channel? =
        read {
            readableDatabase.query(
                "channels",
                CHANNEL_COLUMNS,
                "playlist_id=? AND bucket=? AND id=?",
                arrayOf(playlistId, bucket, channelId),
                null,
                null,
                null,
                "1"
            ).use { cursor -> if (cursor.moveToFirst()) channelRow(cursor) else null }
        }

    fun readNeighbor(
        playlistId: String,
        bucket: String,
        categoryId: String?,
        number: Int?,
        name: String,
        id: String,
        direction: Int,
        hiddenChannels: Set<String> = emptySet(),
        hiddenGroups: Set<String> = emptySet(),
        hiddenCountries: Set<String> = emptySet(),
        includeInternational: Boolean? = true,
        accept: (com.olympus.iptv.data.Channel) -> Boolean
    ): com.olympus.iptv.data.Channel? = read {
        val db = readableDatabase
        val filter = visibilitySql(bucket, hiddenChannels, hiddenGroups, hiddenCountries, includeInternational)
        var cursorNumber = number ?: -1
        var cursorName = name
        var cursorId = id
        repeat(6) {
            val rows = neighborPage(db, playlistId, bucket, categoryId, cursorNumber, cursorName, cursorId, direction, filter)
            if (rows.isEmpty()) return null
            val hit = rows.firstOrNull(accept)
            if (hit != null) return hit
            val last = rows.last()
            cursorNumber = last.number ?: -1
            cursorName = last.name
            cursorId = last.id
        }
        null
    }

    private fun neighborPage(
        db: SQLiteDatabase,
        playlistId: String,
        bucket: String,
        categoryId: String?,
        number: Int,
        name: String,
        id: String,
        direction: Int,
        filter: SqlFilter = SqlFilter("", emptyList())
    ): List<com.olympus.iptv.data.Channel> {
        val cmp = if (direction < 0) "<" else ">"
        val categorySql = if (categoryId.isNullOrBlank()) "" else " AND (category_id=? OR group_title=?)"
        val args = ArrayList<String>()
        args.add(playlistId)
        args.add(bucket)
        if (!categoryId.isNullOrBlank()) {
            args.add(categoryId)
            args.add(categoryId)
        }
        args.addAll(filter.args)
        val numberText = number.toString()
        args.add(numberText)
        args.add(numberText)
        args.add(name)
        args.add(numberText)
        args.add(name)
        args.add(id)
        val order = if (direction < 0) {
            "number DESC, name COLLATE NOCASE DESC, id DESC"
        } else {
            "number, name COLLATE NOCASE, id"
        }
        return db.query(
            "channels",
            CHANNEL_COLUMNS,
            """
            playlist_id=? AND bucket=?$categorySql${filter.sql}
            AND number IS NOT NULL
            AND (
                number $cmp ?
                OR (number = ? AND name $cmp ? COLLATE NOCASE)
                OR (number = ? AND name = ? COLLATE NOCASE AND id $cmp ?)
            )
            """.trimIndent(),
            args.toTypedArray(),
            null,
            null,
            order,
            "24"
        ).use { collect(it) }
    }

    fun readChannelsByIds(
        playlistId: String,
        bucket: String,
        channelIds: Collection<String>
    ): List<com.olympus.iptv.data.Channel> {
        if (channelIds.isEmpty()) return emptyList()
        return read {
            val db = readableDatabase
            val found = ArrayList<com.olympus.iptv.data.Channel>()
            channelIds.distinct().chunked(400).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                db.query(
                    "channels",
                    LIST_COLUMNS,
                    "playlist_id=? AND bucket=? AND id IN ($marks)",
                    arrayOf(playlistId, bucket, *chunk.toTypedArray()),
                    null,
                    null,
                    "number, name COLLATE NOCASE"
                ).use { found.addAll(collectList(it)) }
            }
            found
        }
    }

    fun searchChannels(
        playlistId: String,
        bucket: String,
        text: String,
        limit: Int
    ): List<com.olympus.iptv.data.Channel> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()
        val match = ftsMatch(trimmed)
        return read {
            val db = readableDatabase
            val found = ArrayList<com.olympus.iptv.data.Channel>(limit)
            val seen = HashSet<String>()
            fun add(channel: com.olympus.iptv.data.Channel) {
                if (found.size < limit && seen.add(channel.id)) found.add(channel)
            }
            var indexed = false
            if (match != null) {
                indexed = runCatching {
                    db.rawQuery(
                        """
                        SELECT c.id, c.name, c.logo_url, c.group_title, c.category_id, c.kind,
                            c.container_extension, c.epg_channel_id, c.country_id, c.country_label,
                            c.number, c.series_id, c.catchup_type, c.catchup_source, c.catchup_days,
                            c.tv_archive, c.tv_archive_duration
                        FROM channels_fts
                        JOIN channels c ON c.rowid = channels_fts.rowid
                        WHERE channels_fts MATCH ? AND c.playlist_id=? AND c.bucket=?
                        LIMIT ?
                        """.trimIndent(),
                        arrayOf(match, playlistId, bucket, limit.toString())
                    ).use { cursor -> collectList(cursor).forEach(::add) }
                }.isSuccess
            }
            if (found.size >= limit || (indexed && found.isNotEmpty())) return found
            fun take(where: String, args: Array<String>) {
                if (found.size >= limit) return
                db.query(
                    "channels",
                    LIST_COLUMNS,
                    where,
                    args,
                    null,
                    null,
                    "name COLLATE NOCASE",
                    (limit - found.size).toString()
                ).use { cursor -> collectList(cursor).forEach(::add) }
            }
            val base = "playlist_id=? AND bucket=?"
            val prefix = prefixPattern(trimmed)
            take("$base AND name LIKE ? ESCAPE '\\'", arrayOf(playlistId, bucket, prefix))
            take("$base AND group_title LIKE ? ESCAPE '\\'", arrayOf(playlistId, bucket, prefix))
            if (!indexed && found.size < limit) {
                val contains = likePattern(trimmed)
                take(
                    "$base AND (name LIKE ? ESCAPE '\\' OR group_title LIKE ? ESCAPE '\\')",
                    arrayOf(playlistId, bucket, contains, contains)
                )
            }
            if (found.isEmpty()) {
                val tokens = FtsQuery.looseTokens(trimmed)
                if (tokens.isNotEmpty()) {
                    val where = base + tokens.joinToString("") { " AND name LIKE ? ESCAPE '\\'" }
                    val args = arrayOf(playlistId, bucket, *tokens.map { likePattern(it) }.toTypedArray())
                    take(where, args)
                }
            }
            found
        }
    }

    data class NamedChannel(val id: String, val name: String, val group: String)

    fun localCandidateChannels(playlistId: String): List<NamedChannel> = read {
        if (playlistId.isBlank()) return emptyList()
        val found = ArrayList<NamedChannel>()
        readableDatabase.rawQuery(
            "SELECT id, name, IFNULL(group_title, '') FROM channels " +
                "WHERE playlist_id=? AND bucket=? AND (" +
                "name LIKE '%|%' OR name LIKE '%ABC%' OR name LIKE '%CBS%' OR name LIKE '%NBC%' OR " +
                "name LIKE '%FOX%' OR name LIKE '%PBS%' OR name LIKE '%CW%' OR name LIKE '%ION%' OR " +
                "IFNULL(group_title, '') LIKE '%LOCAL%' OR IFNULL(group_title, '') LIKE '%AFFILIATE%' OR " +
                "IFNULL(group_title, '') LIKE '%ABC%' OR IFNULL(group_title, '') LIKE '%CBS%' OR " +
                "IFNULL(group_title, '') LIKE '%NBC%' OR IFNULL(group_title, '') LIKE '%FOX%' OR " +
                "IFNULL(group_title, '') LIKE '%USA%' OR IFNULL(group_title, '') LIKE 'US %' OR " +
                "IFNULL(group_title, '') LIKE 'US:%'" +
                ") LIMIT 25000",
            arrayOf(playlistId, LIVE)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                found.add(
                    NamedChannel(
                        id = cursor.getString(0),
                        name = cursor.getString(1) ?: "",
                        group = NameImages.strip(cursor.getString(2) ?: "")
                    )
                )
            }
        }
        found
    }

    fun channelsInGroups(playlistId: String, groups: List<String>): List<NamedChannel> = read {
        if (playlistId.isBlank() || groups.isEmpty()) return emptyList()
        val wanted = groups.map { NameImages.strip(it) }.toHashSet()
        val db = readableDatabase
        val actual = ArrayList<String>()
        db.query(
            "categories",
            arrayOf("name"),
            "playlist_id=? AND bucket=?",
            arrayOf(playlistId, LIVE),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                if (NameImages.strip(name) in wanted) actual.add(name)
            }
        }
        if (actual.isEmpty()) return emptyList()
        val marks = actual.joinToString(",") { "?" }
        db.rawQuery(
            "SELECT id, name, group_title FROM channels WHERE playlist_id=? AND bucket=? AND group_title IN ($marks)",
            arrayOf(playlistId, LIVE, *actual.toTypedArray())
        ).use { cursor ->
            val found = ArrayList<NamedChannel>()
            while (cursor.moveToNext()) {
                found.add(
                    NamedChannel(
                        id = cursor.getString(0),
                        name = cursor.getString(1) ?: "",
                        group = NameImages.strip(cursor.getString(2) ?: "")
                    )
                )
            }
            found
        }
    }

    fun rewriteNames(playlistId: String, prefixes: List<String>, suffixes: List<String>): Int {
        if (playlistId.isBlank() || (prefixes.isEmpty() && suffixes.isEmpty())) return 0
        val changes = ArrayList<Pair<String, String>>()
        readableDatabase.query(
            "channels",
            arrayOf("id", "name"),
            "playlist_id=?",
            arrayOf(playlistId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val next = NameStrip.apply(name, prefixes, suffixes)
                if (next != name) changes.add(cursor.getString(0) to next)
            }
        }
        if (changes.isEmpty()) return 0
        return write {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val stmt = db.compileStatement("UPDATE channels SET name=? WHERE playlist_id=? AND id=?")
                for ((id, name) in changes) {
                    stmt.clearBindings()
                    stmt.bindString(1, name)
                    stmt.bindString(2, playlistId)
                    stmt.bindString(3, id)
                    stmt.executeUpdateDelete()
                }
                touchLibrary(db, playlistId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            changes.size
        }
    }

    fun stripImages(playlistId: String): ImageStrip = write {
        val db = writableDatabase
        val samples = ArrayList<Pair<String, String>>()
        var changed = 0
        fun consider(before: String, after: String) {
            if (before == after) return
            changed += 1
            val sample = clipImageSample(before) to clipImageSample(after)
            if (samples.size < 4) samples.add(sample)
            else if (changed % 40 == 0) samples[(changed / 40) % 4] = sample
        }
        val categories = ArrayList<Array<String>>()
        db.query(
            "categories",
            arrayOf("bucket", "id", "name"),
            "playlist_id=?",
            arrayOf(playlistId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(2) ?: continue
                val next = com.olympus.iptv.data.NameImages.strip(name)
                if (next == name) continue
                consider(name, next)
                categories.add(arrayOf(cursor.getString(0), cursor.getString(1), next))
            }
        }
        val channels = ArrayList<Array<String>>()
        db.query(
            "channels",
            arrayOf("bucket", "id", "name", "group_title"),
            "playlist_id=?",
            arrayOf(playlistId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(2) ?: ""
                val group = cursor.getString(3)
                val nextName = com.olympus.iptv.data.NameImages.strip(name)
                val nextGroup = group?.let { com.olympus.iptv.data.NameImages.strip(it) }
                if (nextName == name && nextGroup == group) continue
                if (nextName != name) consider(name, nextName)
                else if (group != null && nextGroup != group) consider(group, nextGroup ?: group)
                channels.add(arrayOf(cursor.getString(0), cursor.getString(1), nextName, nextGroup ?: ""))
            }
        }
        if (categories.isEmpty() && channels.isEmpty()) return ImageStrip(0, samples)
        db.beginTransaction()
        try {
            val categoryStmt = db.compileStatement(
                "UPDATE categories SET name=? WHERE playlist_id=? AND bucket=? AND id=?"
            )
            for (row in categories) {
                categoryStmt.clearBindings()
                categoryStmt.bindString(1, row[2])
                categoryStmt.bindString(2, playlistId)
                categoryStmt.bindString(3, row[0])
                categoryStmt.bindString(4, row[1])
                categoryStmt.executeUpdateDelete()
            }
            val channelStmt = db.compileStatement(
                "UPDATE channels SET name=?, group_title=? WHERE playlist_id=? AND bucket=? AND id=?"
            )
            for (row in channels) {
                channelStmt.clearBindings()
                channelStmt.bindString(1, row[2])
                if (row[3].isEmpty()) channelStmt.bindNull(2) else channelStmt.bindString(2, row[3])
                channelStmt.bindString(3, playlistId)
                channelStmt.bindString(4, row[0])
                channelStmt.bindString(5, row[1])
                channelStmt.executeUpdateDelete()
            }
            touchLibrary(db, playlistId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ImageStrip(changed, samples)
    }

    fun parseVisibleNames(playlistId: String, config: com.olympus.iptv.data.LineupConfig): ParsedNames = read {
        val db = readableDatabase
        val parsed = HashMap<String, List<com.olympus.iptv.data.EpgProgram>>()
        val examples = ArrayList<Pair<String, String>>(PARSE_EXAMPLES)
        var changed = 0
        db.query(
            "channels",
            arrayOf("id", "name", "group_title", "category_id", "country_id"),
            "playlist_id=? AND bucket=?",
            arrayOf(playlistId, LIVE),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val channel = com.olympus.iptv.data.Channel(
                    id = cursor.getString(0),
                    name = cursor.getString(1) ?: continue,
                    streamUrl = "",
                    groupTitle = cursor.getString(2),
                    categoryId = cursor.getString(3),
                    kind = ContentKind.LIVE,
                    countryId = cursor.getString(4)
                )
                if (!LineupApplier.visibleChannel(channel, config)) continue
                val shown = com.olympus.iptv.data.ChannelNames.present(channel, config)
                val (after, programs) = DummyEpg.titled(shown)
                parsed[channel.id] = programs
                if (shown == after) continue
                changed++
                val sample = clipExample(shown) to clipExample(after)
                if (examples.any { it == sample }) continue
                if (examples.size < PARSE_EXAMPLES) {
                    examples.add(sample)
                } else if (changed % 800 == 0) {
                    examples[(changed / 800) % PARSE_EXAMPLES] = sample
                }
            }
        }
        ParsedNames(parsed, examples)
    }

    fun releaseSearchExport() {
        searchExportHold = null
    }

    fun releaseMemory() {
        searchExportHold = null
        dashHold = null
        liveCountryHold = null
        fastCountryHold = null
        runCatching { readableDatabase.rawQuery("PRAGMA shrink_memory", null).use { } }
    }

    fun exportSearchCatalog(playlistId: String): Pair<List<Map<String, String>>, List<Map<String, String>>> {
        searchExportHold = null
        if (playlistId.isBlank()) return emptyList<Map<String, String>>() to emptyList()
        return emptyList<Map<String, String>>() to emptyList()
    }

    private fun searchKind(bucket: String?): String = when (bucket) {
        VOD -> "VOD"
        SERIES -> "SERIES"
        else -> "LIVE"
    }

    fun knownChannelIds(playlistId: String, bucket: String): HashSet<String> = read {
        val ids = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT id FROM channels WHERE playlist_id=? AND bucket=?",
            arrayOf(playlistId, bucket)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (!id.isNullOrEmpty()) ids.add(id)
            }
        }
        ids
    }

    fun hasLibrary(playlistId: String): Boolean = read {
        val db = readableDatabase
        db.rawQuery(
            "SELECT 1 FROM channels WHERE playlist_id=? LIMIT 1",
            arrayOf(playlistId)
        ).use { it.moveToFirst() }
    }

    fun pruneMissingLiveChannels(playlistId: String, activeIds: Set<String>): Int = write {
        if (activeIds.size < 100) return@write 0
        val db = writableDatabase
        val stale = ArrayList<String>()
        db.rawQuery(
            "SELECT id FROM channels WHERE playlist_id=? AND bucket=?",
            arrayOf(playlistId, LIVE)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                if (id !in activeIds) stale.add(id)
            }
        }
        if (stale.isEmpty()) return@write 0
        var deleted = 0
        db.beginTransaction()
        try {
            val delChan = db.compileStatement("DELETE FROM channels WHERE playlist_id=? AND bucket=? AND id=?")
            val delEpg = db.compileStatement("DELETE FROM epg_cache WHERE playlist_id=? AND channel_id=?")
            for (id in stale) {
                delChan.clearBindings()
                delChan.bindString(1, playlistId)
                delChan.bindString(2, LIVE)
                delChan.bindString(3, id)
                deleted += delChan.executeUpdateDelete()
                delEpg.clearBindings()
                delEpg.bindString(1, playlistId)
                delEpg.bindString(2, id)
                delEpg.executeUpdateDelete()
            }
            if (deleted > 0) {
                touchLibrary(db, playlistId)
                NameStrip.clearCaches()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        deleted
    }

    fun insertMissing(
        playlistId: String,
        bucket: String,
        categories: List<Category>,
        channels: List<com.olympus.iptv.data.Channel>
    ): Added = write {
        if (categories.isEmpty() && channels.isEmpty()) return Added()
        val db = writableDatabase
        val newCategories = ArrayList<Category>()
        val newChannels = ArrayList<com.olympus.iptv.data.Channel>()
        val bulk = channels.size >= 32 && bulkSearch.get() == 0
        if (bulk) {
            runCatching {
                db.execSQL("DROP TRIGGER IF EXISTS channels_fts_insert")
                db.execSQL("DROP TRIGGER IF EXISTS channels_fts_update")
            }
        }
        db.beginTransaction()
        try {
            val categoryStmt = db.compileStatement(
                "INSERT INTO categories (playlist_id, bucket, id, name) VALUES (?,?,?,?) " +
                    "ON CONFLICT(playlist_id, bucket, id) DO UPDATE SET name=excluded.name " +
                    "WHERE excluded.name != '' AND excluded.name != excluded.id"
            )
            for (category in categories) {
                categoryStmt.clearBindings()
                categoryStmt.bindString(1, playlistId)
                categoryStmt.bindString(2, bucket)
                categoryStmt.bindString(3, category.id)
                categoryStmt.bindString(4, category.name)
                if (categoryStmt.executeInsert() != -1L) newCategories.add(category)
            }
            val derivedCatStmt = db.compileStatement(
                "INSERT OR IGNORE INTO categories (playlist_id, bucket, id, name) VALUES (?,?,?,?)"
            )
            val derivedSeen = HashSet<String>()
            for (channel in channels) {
                val cid = channel.categoryId?.trim().orEmpty()
                val title = channel.groupTitle?.trim().orEmpty()
                if (cid.isNotEmpty() && title.isNotEmpty() && derivedSeen.add(cid)) {
                    derivedCatStmt.clearBindings()
                    derivedCatStmt.bindString(1, playlistId)
                    derivedCatStmt.bindString(2, bucket)
                    derivedCatStmt.bindString(3, cid)
                    derivedCatStmt.bindString(4, title)
                    if (derivedCatStmt.executeInsert() != -1L) {
                        newCategories.add(Category(id = cid, name = title, kind = channel.kind))
                    }
                }
            }
            val channelStmt = db.compileStatement(
                """
                INSERT OR IGNORE INTO channels (
                    playlist_id, bucket, id, name, stream_url, logo_url, group_title, category_id,
                    kind, container_extension, epg_channel_id, country_id, country_label, number, series_id,
                    catchup_type, catchup_source, catchup_days, tv_archive, tv_archive_duration,
                    is_event_slot, event_start_ms, event_end_ms, is_247, sort_247
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            val replaceTeam = db.compileStatement(
                """
                UPDATE channels SET name=?, stream_url=?, logo_url=?, group_title=?, category_id=?, kind=?,
                    container_extension=?, epg_channel_id=?, country_id=?, country_label=?, number=?, series_id=?,
                    catchup_type=?, catchup_source=?, catchup_days=?, tv_archive=?, tv_archive_duration=?
                WHERE playlist_id=? AND bucket=? AND id=?
                  AND IFNULL(group_title,'') NOT LIKE '%Teams%'
                """.trimIndent()
            )
            val updateExisting = db.compileStatement(
                """
                UPDATE channels SET name=?, stream_url=?, logo_url=?,
                    epg_channel_id=?, catchup_type=?, catchup_source=?, catchup_days=?,
                    tv_archive=?, tv_archive_duration=?,
                    is_event_slot=?, event_start_ms=?, event_end_ms=?, is_247=?, sort_247=?
                WHERE playlist_id=? AND bucket=? AND id=?
                """.trimIndent()
            )
            val clearStaleEpg = db.compileStatement(
                "DELETE FROM epg_cache WHERE playlist_id=? AND (channel_id=? OR channel_id=?)"
            )
            var updatedAnyLive = false
            for (channel in channels) {
                channelStmt.clearBindings()
                channelStmt.bindString(1, playlistId)
                channelStmt.bindString(2, bucket)
                channelStmt.bindString(3, channel.id)
                channelStmt.bindString(4, channel.name)
                channelStmt.bindString(5, channel.streamUrl)
                bindOptional(channelStmt, 6, channel.logoUrl)
                bindOptional(channelStmt, 7, channel.groupTitle)
                bindOptional(channelStmt, 8, channel.categoryId)
                channelStmt.bindString(9, channel.kind.name)
                bindOptional(channelStmt, 10, channel.containerExtension)
                bindOptional(channelStmt, 11, channel.epgChannelId)
                bindOptional(channelStmt, 12, channel.countryId)
                bindOptional(channelStmt, 13, channel.countryLabel)
                if (channel.number == null) channelStmt.bindNull(14) else channelStmt.bindLong(14, channel.number.toLong())
                bindOptional(channelStmt, 15, channel.seriesId)
                bindCatchup(channelStmt, 16, channel)
                val evStatus = if (bucket == LIVE) {
                    NameStrip.inspect(channel.name, channel.groupTitle, channel.epgChannelId)
                } else null
                val evFlag = if (evStatus?.isEventSlot == true) 1L else 0L
                val evStart = evStatus?.eventStartMs ?: 0L
                val evEnd = evStatus?.eventEndMs ?: 0L
                val is247Flag = if (bucket == LIVE && TwentyFourSeven.is247(channel.name, channel.groupTitle)) 1L else 0L
                val sort247Val = if (is247Flag == 1L) TwentyFourSeven.sortKey(channel.name) else ""
                channelStmt.bindLong(21, evFlag)
                channelStmt.bindLong(22, evStart)
                channelStmt.bindLong(23, evEnd)
                channelStmt.bindLong(24, is247Flag)
                channelStmt.bindString(25, sort247Val)
                val inserted = channelStmt.executeInsert() != -1L
                val teamGroup = channel.groupTitle?.contains("Teams", ignoreCase = true) == true
                if (inserted) {
                    newChannels.add(channel)
                } else if (teamGroup) {
                    replaceTeam.clearBindings()
                    replaceTeam.bindString(1, channel.name)
                    replaceTeam.bindString(2, channel.streamUrl)
                    bindOptional(replaceTeam, 3, channel.logoUrl)
                    bindOptional(replaceTeam, 4, channel.groupTitle)
                    bindOptional(replaceTeam, 5, channel.categoryId)
                    replaceTeam.bindString(6, channel.kind.name)
                    bindOptional(replaceTeam, 7, channel.containerExtension)
                    bindOptional(replaceTeam, 8, channel.epgChannelId)
                    bindOptional(replaceTeam, 9, channel.countryId)
                    bindOptional(replaceTeam, 10, channel.countryLabel)
                    if (channel.number == null) replaceTeam.bindNull(11) else replaceTeam.bindLong(11, channel.number.toLong())
                    bindOptional(replaceTeam, 12, channel.seriesId)
                    bindCatchup(replaceTeam, 13, channel)
                    replaceTeam.bindString(18, playlistId)
                    replaceTeam.bindString(19, bucket)
                    replaceTeam.bindString(20, channel.id)
                    if (replaceTeam.executeUpdateDelete() > 0) {
                        newChannels.add(channel)
                        if (bucket == LIVE) updatedAnyLive = true
                    }
                } else {
                    updateExisting.clearBindings()
                    updateExisting.bindString(1, channel.name)
                    updateExisting.bindString(2, channel.streamUrl)
                    bindOptional(updateExisting, 3, channel.logoUrl)
                    bindOptional(updateExisting, 4, channel.epgChannelId)
                    bindCatchup(updateExisting, 5, channel)
                    updateExisting.bindLong(10, evFlag)
                    updateExisting.bindLong(11, evStart)
                    updateExisting.bindLong(12, evEnd)
                    updateExisting.bindLong(13, is247Flag)
                    updateExisting.bindString(14, sort247Val)
                    updateExisting.bindString(15, playlistId)
                    updateExisting.bindString(16, bucket)
                    updateExisting.bindString(17, channel.id)
                    if (updateExisting.executeUpdateDelete() > 0) {
                        newChannels.add(channel)
                        if (bucket == LIVE) {
                            updatedAnyLive = true
                            if (evFlag == 1L) {
                                clearStaleEpg.clearBindings()
                                clearStaleEpg.bindString(1, playlistId)
                                clearStaleEpg.bindString(2, channel.id)
                                clearStaleEpg.bindString(3, channel.epgChannelId ?: channel.id)
                                clearStaleEpg.executeUpdateDelete()
                            }
                        }
                    }
                }
            }
            if (updatedAnyLive) NameStrip.clearCaches()
            if (newCategories.isNotEmpty() || newChannels.isNotEmpty()) touchLibrary(db, playlistId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            if (bulk) {
                runCatching {
                    ensureSearchTriggers(db)
                    indexInsertedChannels(db, playlistId, bucket, newChannels.map { it.id })
                }
            }
        }
        if (newChannels.size >= 100) {
            runCatching { writableDatabase.execSQL("PRAGMA wal_checkpoint(PASSIVE)") }
            runCatching { writableDatabase.execSQL("ANALYZE channels") }
        }
        Added(newCategories, newChannels)
    }

    fun libraryStamp(playlistId: String): Long = read {
        readableDatabase.rawQuery(
            "SELECT stamp FROM library_meta WHERE playlist_id=?",
            arrayOf(playlistId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    private fun touchLibrary(db: SQLiteDatabase, playlistId: String) {
        dashHold = null
        sorted247Hold = null
        db.execSQL(
            "INSERT INTO library_meta(playlist_id, stamp) VALUES(?, 1) ON CONFLICT(playlist_id) DO UPDATE SET stamp = stamp + 1",
            arrayOf(playlistId)
        )
    }

    private fun readCategories(db: SQLiteDatabase, playlistId: String, bucket: String): List<Category> {
        val kind = if (bucket == LIVE) ContentKind.LIVE else ContentKind.VOD
        val categories = ArrayList<Category>()
        val seenIds = HashSet<String>()
        var has247 = false
        db.query(
            "categories",
            arrayOf("id", "name"),
            "playlist_id=? AND bucket=?",
            arrayOf(playlistId, bucket),
            null,
            null,
            "name COLLATE NOCASE"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val shown = NameImages.strip(cursor.getString(1) ?: "")
                val label = shown.ifBlank { id }
                seenIds.add(id)
                if (bucket == LIVE && TwentyFourSeven.is247(label)) {
                    has247 = true
                    continue
                }
                if (bucket == LIVE && AdultGroup.isAdultGroup(label)) {
                    continue
                }
                categories.add(Category(id = id, name = label, kind = kind))
            }
        }

        db.rawQuery(
            """
            SELECT COALESCE(NULLIF(category_id, ''), group_title), MAX(NULLIF(group_title, ''))
            FROM channels
            WHERE playlist_id=? AND bucket=?
              AND COALESCE(NULLIF(category_id, ''), group_title) IS NOT NULL
              AND COALESCE(NULLIF(category_id, ''), group_title) != ''
            GROUP BY COALESCE(NULLIF(category_id, ''), group_title)
            """.trimIndent(),
            arrayOf(playlistId, bucket)
        ).use { cursor ->
            var addedAny = false
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                if (id == PlaylistLoader.ALL_ID || !seenIds.add(id)) continue
                val title = NameImages.strip(cursor.getString(1) ?: "").ifBlank { id }
                if (bucket == LIVE && TwentyFourSeven.is247(title)) {
                    has247 = true
                    continue
                }
                if (bucket == LIVE && AdultGroup.isAdultGroup(title)) {
                    continue
                }
                categories.add(Category(id = id, name = title, kind = kind))
                addedAny = true
            }
            if (bucket == LIVE) {
                if (!has247) {
                    db.rawQuery(
                        "SELECT 1 FROM channels WHERE playlist_id=? AND bucket=? AND ${TwentyFourSeven.SQL_MATCH} LIMIT 1",
                        arrayOf(playlistId, LIVE)
                    ).use { c -> if (c.moveToFirst()) has247 = true }
                }
                if (has247 && seenIds.add(TwentyFourSeven.GROUP_ID)) {
                    categories.add(Category(id = TwentyFourSeven.GROUP_ID, name = TwentyFourSeven.GROUP_NAME, kind = ContentKind.LIVE))
                    addedAny = true
                }
                val hasAdult = db.rawQuery(
                    "SELECT 1 FROM channels WHERE playlist_id=? AND bucket=? AND ${AdultGroup.SQL_MATCH} LIMIT 1",
                    arrayOf(playlistId, LIVE)
                ).use { c -> c.moveToFirst() }
                if (hasAdult && seenIds.add(AdultGroup.GROUP_ID)) {
                    categories.add(Category(id = AdultGroup.GROUP_ID, name = AdultGroup.GROUP_NAME, kind = ContentKind.LIVE))
                    addedAny = true
                }
            }
            if (addedAny) categories.sortWith(compareBy({ it.id == AdultGroup.GROUP_ID }, { it.name.lowercase() }))
        }
        return categories
    }

    private fun readChannels(
        db: SQLiteDatabase,
        playlistId: String,
        bucket: String
    ): List<com.olympus.iptv.data.Channel> =
        db.query(
            "channels",
            CHANNEL_COLUMNS,
            "playlist_id=? AND bucket=?",
            arrayOf(playlistId, bucket),
            null,
            null,
            "number, name COLLATE NOCASE"
        ).use { collect(it) }

    private data class SqlFilter(val sql: String, val args: List<String>)

    private fun visibilitySql(
        bucket: String,
        hiddenChannels: Set<String>,
        hiddenGroups: Set<String>,
        hiddenCountries: Set<String>,
        includeInternational: Boolean?
    ): SqlFilter {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        if (hiddenChannels.isNotEmpty() && hiddenChannels.size <= 400) {
            val ids = hiddenChannels.filter { it.isNotBlank() }
            if (ids.isNotEmpty()) {
                parts.add("id NOT IN (${ids.joinToString(",") { "?" }})")
                args.addAll(ids)
            }
        }
        if (hiddenGroups.isNotEmpty() && hiddenGroups.size <= 200) {
            val groups = hiddenGroups.filter { it.isNotBlank() }
            if (groups.isNotEmpty()) {
                val marks = groups.joinToString(",") { "?" }
                parts.add("(category_id IS NULL OR category_id NOT IN ($marks))")
                args.addAll(groups)
                parts.add("(group_title IS NULL OR group_title NOT IN ($marks))")
                args.addAll(groups)
            }
        }
        if (bucket == LIVE && includeInternational != true) {
            parts.add("(country_id IS NULL OR country_id='' OR country_id='us')")
        } else if (hiddenCountries.isNotEmpty()) {
            val countries = hiddenCountries.filter { it.isNotBlank() && it != CountryIndex.OTHER_ID }
            if (countries.isNotEmpty() && countries.size <= 100) {
                parts.add("(country_id IS NULL OR country_id='' OR country_id NOT IN (${countries.joinToString(",") { "?" }}))")
                args.addAll(countries)
            }
        }
        return SqlFilter(if (parts.isEmpty()) "" else " AND " + parts.joinToString(" AND "), args)
    }

    private fun pageAfter(
        db: SQLiteDatabase,
        playlistId: String,
        bucket: String,
        categoryId: String?,
        limit: Int,
        after: PageCursor?,
        filter: SqlFilter = SqlFilter("", emptyList())
    ): List<com.olympus.iptv.data.Channel> {
        val args = ArrayList<String>()
        args.add(playlistId)
        args.add(bucket)
        val categorySql = if (categoryId.isNullOrBlank()) {
            ""
        } else {
            args.add(categoryId)
            args.add(categoryId)
            " AND (category_id=? OR group_title=?)"
        }
        args.addAll(filter.args)
        val cursorSql = when {
            after == null -> ""
            after.number != null -> {
                val number = after.number.toString()
                args.add(number)
                args.add(number)
                args.add(after.name)
                args.add(number)
                args.add(after.name)
                args.add(after.id)
                """
                AND number IS NOT NULL
                AND (
                    number > ?
                    OR (number = ? AND name > ? COLLATE NOCASE)
                    OR (number = ? AND name = ? COLLATE NOCASE AND id > ?)
                )
                """.trimIndent()
            }
            else -> {
                args.add(after.name)
                args.add(after.name)
                args.add(after.id)
                """
                AND (
                    (number IS NULL AND (
                        name > ? COLLATE NOCASE
                        OR (name = ? COLLATE NOCASE AND id > ?)
                    ))
                    OR number IS NOT NULL
                )
                """.trimIndent()
            }
        }
        return db.query(
            "channels",
            LIST_COLUMNS,
            "playlist_id=? AND bucket=?$categorySql${filter.sql} $cursorSql",
            args.toTypedArray(),
            null,
            null,
            "number, name COLLATE NOCASE, id",
            limit.toString()
        ).use { collectList(it) }
    }

    private fun collect(cursor: android.database.Cursor): List<com.olympus.iptv.data.Channel> {
        val channels = ArrayList<com.olympus.iptv.data.Channel>()
        while (cursor.moveToNext()) channels.add(channelRow(cursor))
        return channels
    }

    private fun collectList(cursor: android.database.Cursor): List<com.olympus.iptv.data.Channel> {
        val channels = ArrayList<com.olympus.iptv.data.Channel>()
        while (cursor.moveToNext()) channels.add(listRow(cursor))
        return channels
    }

    private fun channelRow(cursor: android.database.Cursor) = com.olympus.iptv.data.Channel(
        id = cursor.getString(0),
        name = cursor.getString(1),
        streamUrl = cursor.getString(2),
        logoUrl = cursor.text(3),
        groupTitle = cursor.text(4),
        categoryId = cursor.text(5),
        kind = if (cursor.getString(6) == ContentKind.LIVE.name) ContentKind.LIVE else ContentKind.VOD,
        containerExtension = cursor.text(7),
        epgChannelId = cursor.text(8),
        countryId = cursor.text(9),
        countryLabel = cursor.text(10),
        number = if (cursor.isNull(11)) null else cursor.getInt(11),
        seriesId = cursor.text(12),
        catchupType = cursor.text(13),
        catchupSource = cursor.text(14),
        catchupDays = if (cursor.isNull(15)) null else cursor.getInt(15),
        tvArchive = if (cursor.isNull(16)) null else cursor.getInt(16) == 1,
        tvArchiveDuration = if (cursor.isNull(17)) null else cursor.getInt(17)
    )

    private fun listRow(cursor: android.database.Cursor) = com.olympus.iptv.data.Channel(
        id = cursor.getString(0),
        name = cursor.getString(1),
        streamUrl = "",
        logoUrl = cursor.text(2),
        groupTitle = cursor.text(3),
        categoryId = cursor.text(4),
        kind = if (cursor.getString(5) == ContentKind.LIVE.name) ContentKind.LIVE else ContentKind.VOD,
        containerExtension = cursor.text(6),
        epgChannelId = cursor.text(7),
        countryId = cursor.text(8),
        countryLabel = cursor.text(9),
        number = if (cursor.isNull(10)) null else cursor.getInt(10),
        seriesId = cursor.text(11),
        catchupType = cursor.text(12),
        catchupSource = cursor.text(13),
        catchupDays = if (cursor.isNull(14)) null else cursor.getInt(14),
        tvArchive = if (cursor.isNull(15)) null else cursor.getInt(15) == 1,
        tvArchiveDuration = if (cursor.isNull(16)) null else cursor.getInt(16)
    )

    private fun bindCatchup(
        statement: android.database.sqlite.SQLiteStatement,
        start: Int,
        channel: com.olympus.iptv.data.Channel
    ) {
        bindOptional(statement, start, channel.catchupType)
        bindOptional(statement, start + 1, channel.catchupSource)
        if (channel.catchupDays == null) statement.bindNull(start + 2)
        else statement.bindLong(start + 2, channel.catchupDays.toLong())
        if (channel.tvArchive == null) statement.bindNull(start + 3)
        else statement.bindLong(start + 3, if (channel.tvArchive == true) 1L else 0L)
        if (channel.tvArchiveDuration == null) statement.bindNull(start + 4)
        else statement.bindLong(start + 4, channel.tvArchiveDuration.toLong())
    }

    private fun ftsMatch(text: String): String? = FtsQuery.match(text)

    private fun likePattern(text: String): String = "%${escapeLike(text)}%"

    private fun prefixPattern(text: String): String = "${escapeLike(text)}%"

    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun bindOptional(statement: android.database.sqlite.SQLiteStatement, index: Int, value: String?) {
        if (value.isNullOrEmpty()) statement.bindNull(index) else statement.bindString(index, value)
    }

    private fun android.database.Cursor.text(index: Int): String? =
        if (isNull(index)) null else getString(index)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE categories (
                playlist_id TEXT NOT NULL,
                bucket TEXT NOT NULL,
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                PRIMARY KEY (playlist_id, bucket, id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE channels (
                playlist_id TEXT NOT NULL,
                bucket TEXT NOT NULL,
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                stream_url TEXT NOT NULL,
                logo_url TEXT,
                group_title TEXT,
                category_id TEXT,
                kind TEXT NOT NULL,
                container_extension TEXT,
                epg_channel_id TEXT,
                country_id TEXT,
                country_label TEXT,
                number INTEGER,
                series_id TEXT,
                catchup_type TEXT,
                catchup_source TEXT,
                catchup_days INTEGER,
                tv_archive INTEGER,
                tv_archive_duration INTEGER,
                is_event_slot INTEGER DEFAULT 0,
                event_start_ms INTEGER DEFAULT 0,
                event_end_ms INTEGER DEFAULT 0,
                is_247 INTEGER DEFAULT 0,
                sort_247 TEXT DEFAULT '',
                PRIMARY KEY (playlist_id, bucket, id)
            )
            """.trimIndent()
        )
        ensureIndexes(db)
        ensureSearch(db)
        ensureGuideTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            ensureIndexes(db)
            ensureSearch(db)
        }
        if (oldVersion < 6) ensureIndexes(db)
        if (oldVersion < 7) db.execSQL("ANALYZE")
        if (oldVersion < 8) ensureGuideTables(db)
        if (oldVersion < 9) ensureCatchupColumns(db)
        if (oldVersion < 10) ensureIndexes(db)
        if (oldVersion < 11) ensureMetaMissTable(db)
        if (oldVersion < 13) {
            ensureIndexes(db)
            ensureGuideTables(db)
        }
        if (oldVersion < 14) {
            ensureEventWindowColumns(db)
            ensureIndexes(db)
        }
        if (oldVersion < 16) {
            ensureEventWindowColumns(db)
            ensureIndexes(db)

            runCatching { File(filesDir, "event-windows.pending").writeText("1") }
        }
        if (oldVersion < 17) {
            ensureIndexes(db)
        }
        if (oldVersion < 18) {
            ensureEventWindowColumns(db)
            runCatching { File(filesDir, "event-windows.pending").writeText("1") }
        }
    }

    private fun ensureCatchupColumns(db: SQLiteDatabase) {
        fun add(column: String, type: String) {
            runCatching { db.execSQL("ALTER TABLE channels ADD COLUMN $column $type") }
        }
        add("catchup_type", "TEXT")
        add("catchup_source", "TEXT")
        add("catchup_days", "INTEGER")
        add("tv_archive", "INTEGER")
        add("tv_archive_duration", "INTEGER")
    }

    private fun ensureEventWindowColumns(db: SQLiteDatabase) {
        fun add(column: String, type: String) {
            runCatching { db.execSQL("ALTER TABLE channels ADD COLUMN $column $type") }
        }
        add("is_event_slot", "INTEGER DEFAULT 0")
        add("event_start_ms", "INTEGER DEFAULT 0")
        add("event_end_ms", "INTEGER DEFAULT 0")
        add("is_247", "INTEGER DEFAULT 0")
        add("sort_247", "TEXT DEFAULT ''")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_live_event_window ON channels(playlist_id, bucket, is_event_slot, event_start_ms, event_end_ms, category_id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_247 ON channels(playlist_id, bucket, is_247, sort_247, id)"
        )
    }

    private fun ensureGuideTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS epg_cache (
                playlist_id TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY (playlist_id, channel_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_epg_cache_channel ON epg_cache(channel_id, fetched_at)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS watched (
                playlist_id TEXT NOT NULL,
                episode_id TEXT NOT NULL,
                series_id TEXT,
                season INTEGER,
                PRIMARY KEY (playlist_id, episode_id)
            )
            """.trimIndent()
        )
    }

    private fun ensureIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "DROP INDEX IF EXISTS idx_channels_category"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_category ON channels(playlist_id, bucket, category_id, number, name COLLATE NOCASE, id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_name ON channels(playlist_id, bucket, name COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_group ON channels(playlist_id, bucket, group_title COLLATE NOCASE)"
        )
        db.execSQL(
            "DROP INDEX IF EXISTS idx_channels_order"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_order ON channels(playlist_id, bucket, number, name COLLATE NOCASE, id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_country ON channels(playlist_id, bucket, country_id, number, name COLLATE NOCASE, id)"
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_group_order ON channels(playlist_id, bucket, group_title, number, name COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_cat_country ON channels(playlist_id, bucket, category_id, country_id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_bucket_cat_cover ON channels(playlist_id, bucket, category_id, name COLLATE NOCASE, id, logo_url, kind)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_channels_logo ON channels(playlist_id, logo_url)"
        )
    }

    fun maintainAfterRefresh() {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - 6L * 3_600_000L
        runCatching { db.delete("epg_cache", "fetched_at < ?", arrayOf(cutoff.toString())) }
        runCatching { db.rawQuery("PRAGMA optimize", null).use { } }
        runCatching { db.execSQL("PRAGMA incremental_vacuum(200)") }
    }

    private val bulkSearch = java.util.concurrent.atomic.AtomicInteger(0)

    fun beginBulkSearch() = write {
        if (bulkSearch.incrementAndGet() != 1) return@write
        val db = writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS channels_fts_insert")
        db.execSQL("DROP TRIGGER IF EXISTS channels_fts_update")
    }

    fun endBulkSearch() = write {
        if (bulkSearch.decrementAndGet() > 0) return@write
        if (bulkSearch.get() < 0) bulkSearch.set(0)
        val db = writableDatabase
        ensureSearchTriggers(db)
        runCatching { db.execSQL("INSERT INTO channels_fts(channels_fts) VALUES('rebuild')") }
    }

    private fun indexInsertedChannels(
        db: SQLiteDatabase,
        playlistId: String,
        bucket: String,
        ids: List<String>
    ) {
        if (ids.isEmpty()) return
        val fts5 = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='channels_fts' AND sql LIKE '%fts5%'",
            null
        ).use { it.moveToFirst() }
        val columns = if (fts5) "rowid, name, group_title" else "docid, name, group_title"
        for (chunk in ids.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            db.execSQL(
                """
                INSERT INTO channels_fts($columns)
                SELECT rowid, name, IFNULL(group_title, '') FROM channels
                WHERE playlist_id=? AND bucket=? AND id IN ($marks)
                """.trimIndent(),
                arrayOf(playlistId, bucket, *chunk.toTypedArray())
            )
        }
    }

    private fun ensureSearchTriggers(db: SQLiteDatabase) {
        val fts5 = fts5Available(db) && db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='channels_fts' AND sql LIKE '%fts5%'",
            null
        ).use { it.moveToFirst() }
        db.execSQL("DROP TRIGGER IF EXISTS channels_fts_insert")
        db.execSQL("DROP TRIGGER IF EXISTS channels_fts_update")
        if (fts5) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS channels_fts_insert AFTER INSERT ON channels BEGIN
                    INSERT INTO channels_fts(rowid, name, group_title)
                    VALUES (new.rowid, new.name, new.group_title);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS channels_fts_update AFTER UPDATE ON channels BEGIN
                    INSERT INTO channels_fts(channels_fts, rowid, name, group_title)
                    VALUES ('delete', old.rowid, old.name, old.group_title);
                    INSERT INTO channels_fts(rowid, name, group_title)
                    VALUES (new.rowid, new.name, new.group_title);
                END
                """.trimIndent()
            )
        } else if (db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='channels_fts'",
                null
            ).use { it.moveToFirst() }
        ) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS channels_fts_insert AFTER INSERT ON channels BEGIN
                    INSERT INTO channels_fts(docid, name, group_title)
                    VALUES (new.rowid, new.name, new.group_title);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS channels_fts_update AFTER UPDATE ON channels BEGIN
                    DELETE FROM channels_fts WHERE docid = old.rowid;
                    INSERT INTO channels_fts(docid, name, group_title)
                    VALUES (new.rowid, new.name, new.group_title);
                END
                """.trimIndent()
            )
        }
    }

    private fun ensureSearch(db: SQLiteDatabase) {
        if (!fts5Available(db)) {
            ensureFts4(db)
            return
        }
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS channels_fts USING fts5(
                name,
                group_title,
                content='channels',
                content_rowid='rowid',
                tokenize='unicode61 remove_diacritics 2'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS channels_fts_insert AFTER INSERT ON channels BEGIN
                INSERT INTO channels_fts(rowid, name, group_title)
                VALUES (new.rowid, new.name, new.group_title);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS channels_fts_delete AFTER DELETE ON channels BEGIN
                INSERT INTO channels_fts(channels_fts, rowid, name, group_title)
                VALUES ('delete', old.rowid, old.name, old.group_title);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS channels_fts_update AFTER UPDATE ON channels BEGIN
                INSERT INTO channels_fts(channels_fts, rowid, name, group_title)
                VALUES ('delete', old.rowid, old.name, old.group_title);
                INSERT INTO channels_fts(rowid, name, group_title)
                VALUES (new.rowid, new.name, new.group_title);
            END
            """.trimIndent()
        )
        db.execSQL("INSERT INTO channels_fts(channels_fts) VALUES('rebuild')")
    }

    private fun ensureFts4(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS channels_fts USING fts4(
                content='channels',
                name,
                group_title
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS channels_fts_insert AFTER INSERT ON channels BEGIN
                INSERT INTO channels_fts(docid, name, group_title)
                VALUES (new.rowid, new.name, new.group_title);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS channels_fts_delete BEFORE DELETE ON channels BEGIN
                DELETE FROM channels_fts WHERE docid = old.rowid;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS channels_fts_update AFTER UPDATE ON channels BEGIN
                DELETE FROM channels_fts WHERE docid = old.rowid;
                INSERT INTO channels_fts(docid, name, group_title)
                VALUES (new.rowid, new.name, new.group_title);
            END
            """.trimIndent()
        )
        runCatching { db.execSQL("INSERT INTO channels_fts(channels_fts) VALUES('rebuild')") }
    }

    data class DashboardGroup(
        val id: String,
        val name: String,
        val kind: String,
        val count: Int,
        val countryId: String = ""
    )
    data class DashboardSnapshot(
        val groups: List<DashboardGroup>,
        val countries: List<CountryOption>,
        val channelCount: Int
    )
    private data class DashHold(val playlistId: String, val stamp: Long, val body: DashboardSnapshot)
    private data class Sorted247Hold(
        val playlistId: String,
        val stamp: Long,
        val hiddenHash: Int,
        val countriesHash: Int,
        val includeInternational: Boolean?,
        val channels: List<com.olympus.iptv.data.Channel>
    )

    private data class FastCountryHold(
        val playlistId: String,
        val stamp: Long,
        val countries: Map<String, String>
    )

    private data class LiveCountryHold(
        val playlistId: String,
        val stamp: Long,
        val liveCountry: Map<String, String>,
        val titled: Map<String, String>,
        val named: Map<String, String>
    )

    private data class SearchExportHold(
        val playlistId: String,
        val stamp: Long,
        val groups: List<Map<String, String>>,
        val channels: List<Map<String, String>>
    )
    data class SlimChannel(val id: String, val name: String, val groupId: String, val kind: String, val logo: String)
    data class StripSample(val before: String, val after: String)

    data class FreshTitle(
        val id: String,
        val bucket: String,
        val name: String,
        val logoUrl: String?,
        val service: String,
        val detail: String,
        val day: String = "",
        val watchmodeId: Int = 0,
        val season: Int? = null
    )

    data class CatalogTitle(
        val id: String,
        val bucket: String,
        val name: String,
        val logoUrl: String?,
        val group: String
    )

    fun titlesForPremieres(playlistId: String, prefixes: List<String>): List<CatalogTitle> {
        if (playlistId.isBlank() || prefixes.isEmpty()) return emptyList()
        val sql = """
            SELECT c.id, c.bucket, c.name, c.logo_url, IFNULL(c.group_title, ''), IFNULL(cat.name, '')
            FROM channels c
            LEFT JOIN categories cat
              ON cat.playlist_id = c.playlist_id AND cat.bucket = c.bucket AND cat.id = c.category_id
            WHERE c.playlist_id = ? AND c.bucket = ? AND c.name COLLATE NOCASE >= ? AND c.name COLLATE NOCASE < ?
            LIMIT 48
        """.trimIndent()
        val seen = HashSet<String>()
        val rows = ArrayList<CatalogTitle>()
        val buckets = arrayOf(VOD, SERIES)
        val capped = if (prefixes.size > 96) prefixes.take(96) else prefixes
        try {
            val db = readableDatabase
            for (prefix in capped) {
                if (prefix.isBlank()) continue
                val end = prefixEnd(prefix)
                for (bucket in buckets) {
                    db.rawQuery(sql, arrayOf(playlistId, bucket, prefix, end)).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(2) ?: continue
                            val id = cursor.getString(0) ?: continue
                            val rowBucket = cursor.getString(1) ?: continue
                            if (!seen.add("$rowBucket\u0000$id")) continue
                            val groupTitle = cursor.getString(4).orEmpty()
                            val catName = cursor.getString(5).orEmpty()
                            val group = when {
                                groupTitle.isNotBlank() && catName.isNotBlank() -> "$groupTitle $catName"
                                groupTitle.isNotBlank() -> groupTitle
                                else -> catName
                            }
                            rows.add(CatalogTitle(id, rowBucket, name, cursor.getString(3), group))
                            if (rows.size >= 400) return rows
                        }
                    }
                }
            }
        } catch (_: OutOfMemoryError) {
            releaseSearchExport()
        }
        return rows
    }

    private fun prefixEnd(prefix: String): String {
        val chars = prefix.toCharArray()
        var index = chars.lastIndex
        while (index >= 0 && chars[index] == '\uFFFF') index--
        if (index < 0) return "$prefix\u0000"
        chars[index] = chars[index] + 1
        return String(chars, 0, index + 1)
    }

    fun catchUpEventWindows() {
        val marker = File(filesDir, "event-windows.pending")
        val versionFile = File(filesDir, "event-windows.version")
        val targetVersion = "v6"
        val currentVersion = runCatching { versionFile.readText().trim() }.getOrDefault("")
        if (!marker.exists() && currentVersion == targetVersion) return
        val now = System.currentTimeMillis()
        val epgNow = HashMap<String, Triple<String, Long, Long>>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT playlist_id, channel_id, payload FROM epg_cache",
                null
            ).use { cur ->
                while (cur.moveToNext()) {
                    val pid = cur.getString(0) ?: continue
                    val cid = cur.getString(1) ?: continue
                    val payload = cur.getString(2) ?: continue
                    val programs = decodeEpg(payload)
                    val current = programs.firstOrNull { now >= it.startMillis && now < it.endMillis }
                        ?: programs.firstOrNull { it.startMillis > now }
                    if (current != null) {
                        epgNow["$pid\u0000$cid"] = Triple(current.title, current.startMillis, current.endMillis)
                    }
                }
            }
        }
        val slots = ArrayList<Array<Any>>()
        val twentyFourSeven = ArrayList<Array<Any>>()
        readableDatabase.rawQuery(
            "SELECT playlist_id, id, name, group_title, epg_channel_id FROM channels WHERE bucket=?",
            arrayOf(LIVE)
        ).use { cur ->
            while (cur.moveToNext()) {
                val pid = cur.getString(0) ?: continue
                val id = cur.getString(1) ?: continue
                val name = cur.getString(2) ?: continue
                val groupTitle = cur.getString(3)
                val epgId = cur.getString(4)
                if (TwentyFourSeven.is247(name, groupTitle)) {
                    twentyFourSeven.add(arrayOf(pid, id, TwentyFourSeven.sortKey(name)))
                }
                val cached = epgNow["$pid\u0000${epgId ?: id}"] ?: epgNow["$pid\u0000$id"]
                val status = NameStrip.inspect(
                    rawChannel = name,
                    groupTitle = groupTitle,
                    epgChannelId = epgId,
                    nowTitle = cached?.first,
                    nowStartMs = cached?.second ?: 0L,
                    nowEndMs = cached?.third ?: 0L
                )
                if (!status.isEventSlot && status.eventStartMs <= 0L && status.eventEndMs <= 0L) continue
                slots.add(arrayOf(pid, id, if (status.isEventSlot) 1L else 0L, status.eventStartMs, status.eventEndMs))
            }
        }
        write {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DROP TRIGGER IF EXISTS channels_fts_update")
                db.execSQL(
                    "UPDATE channels SET is_event_slot=0, event_start_ms=0, event_end_ms=0, is_247=0, sort_247='' WHERE bucket=?",
                    arrayOf(LIVE)
                )
                if (slots.isNotEmpty()) {
                    val stmt = db.compileStatement(
                        "UPDATE channels SET is_event_slot=?, event_start_ms=?, event_end_ms=? WHERE playlist_id=? AND bucket=? AND id=?"
                    )
                    for (row in slots) {
                        stmt.clearBindings()
                        stmt.bindLong(1, row[2] as Long)
                        stmt.bindLong(2, row[3] as Long)
                        stmt.bindLong(3, row[4] as Long)
                        stmt.bindString(4, row[0] as String)
                        stmt.bindString(5, LIVE)
                        stmt.bindString(6, row[1] as String)
                        stmt.executeUpdateDelete()
                    }
                }
                if (twentyFourSeven.isNotEmpty()) {
                    val stmt247 = db.compileStatement(
                        "UPDATE channels SET is_247=1, sort_247=? WHERE playlist_id=? AND bucket=? AND id=?"
                    )
                    for (row in twentyFourSeven) {
                        stmt247.clearBindings()
                        stmt247.bindString(1, row[2] as String)
                        stmt247.bindString(2, row[0] as String)
                        stmt247.bindString(3, LIVE)
                        stmt247.bindString(4, row[1] as String)
                        stmt247.executeUpdateDelete()
                    }
                }
                restoreFtsUpdateTrigger(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                runCatching { restoreFtsUpdateTrigger(db) }
            }
        }
        runCatching { marker.delete() }
        runCatching { versionFile.writeText(targetVersion) }
    }

    private fun restoreFtsUpdateTrigger(db: SQLiteDatabase) {
        val fts5 = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='channels_fts' AND sql LIKE '%fts5%'",
            null
        ).use { it.moveToFirst() }
        if (!fts5 && !db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='channels_fts'",
                null
            ).use { it.moveToFirst() }
        ) {
            return
        }
        db.execSQL("DROP TRIGGER IF EXISTS channels_fts_update")
        if (fts5) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS channels_fts_update AFTER UPDATE ON channels BEGIN
                    INSERT INTO channels_fts(channels_fts, rowid, name, group_title)
                    VALUES ('delete', old.rowid, old.name, old.group_title);
                    INSERT INTO channels_fts(rowid, name, group_title)
                    VALUES (new.rowid, new.name, new.group_title);
                END
                """.trimIndent()
            )
        } else {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS channels_fts_update AFTER UPDATE ON channels BEGIN
                    DELETE FROM channels_fts WHERE docid = old.rowid;
                    INSERT INTO channels_fts(docid, name, group_title)
                    VALUES (new.rowid, new.name, new.group_title);
                END
                """.trimIndent()
            )
        }
    }

    fun activeLiveCategoryCounts(
        playlistId: String,
        hiddenChannels: Set<String>,
        now: Long = System.currentTimeMillis(),
        hiddenCountries: Set<String> = emptySet(),
        includeInternational: Boolean? = true
    ): Map<String, Int> = read {
        val db = readableDatabase
        val map = HashMap<String, Int>()
        var totalActive = 0
        var count247 = 0
        var countAdult = 0
        val preWindow = now + NameStrip.PRE_EVENT_WINDOW_MS
        val postGraceCutoff = now - NameStrip.POST_EVENT_GRACE_MS
        val hiddenSet = hiddenChannels.filterTo(HashSet()) { it.isNotBlank() }
        val epgNowByChannel = loadCachedEpgNow(db, playlistId, now)

        db.rawQuery(
            """
            SELECT id, name, group_title, category_id, epg_channel_id, country_id
            FROM channels
            WHERE playlist_id=? AND bucket=?
              AND UPPER(name) NOT LIKE '%: NO EVENT%'
              AND UPPER(name) NOT LIKE '%| NO EVENT%'
              AND ${liveEventWindowSql()}
            """.trimIndent(),
            arrayOf(playlistId, LIVE, preWindow.toString(), postGraceCutoff.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                if (id in hiddenSet) continue
                val name = cursor.getString(1) ?: ""
                val groupTitle = cursor.getString(2)
                val categoryId = cursor.getString(3)
                val epgChannelId = cursor.getString(4)
                val countryId = cursor.getString(5)

                val cachedEpg = epgNowByChannel[epgChannelId ?: id] ?: epgNowByChannel[id]
                val status = NameStrip.inspect(
                    rawChannel = name,
                    groupTitle = groupTitle,
                    epgChannelId = epgChannelId,
                    nowTitle = cachedEpg?.first,
                    nowStartMs = cachedEpg?.second ?: 0L,
                    nowEndMs = cachedEpg?.third ?: 0L
                )
                if (!status.isVisibleAt(now)) continue

                val key = categoryId?.takeIf { it.isNotBlank() } ?: groupTitle?.takeIf { it.isNotBlank() }
                if (key != null) {
                    map[key] = (map[key] ?: 0) + 1
                    totalActive += 1
                }
                if (TwentyFourSeven.is247(name, groupTitle)) {
                    val probe = com.olympus.iptv.data.Channel(
                        id = id,
                        name = name,
                        streamUrl = "",
                        groupTitle = groupTitle,
                        categoryId = categoryId,
                        countryId = countryId,
                        kind = ContentKind.LIVE
                    )
                    if (LineupApplier.keep(probe, hiddenSet, emptySet(), hiddenCountries, includeInternational)) {
                        count247 += 1
                    }
                }
                if (AdultGroup.isAdultGroup(groupTitle)) {
                    countAdult += 1
                }
            }
        }
        map[PlaylistLoader.ALL_ID] = totalActive
        if (count247 > 0) map[TwentyFourSeven.GROUP_ID] = count247
        if (countAdult > 0) map[AdultGroup.GROUP_ID] = countAdult
        map
    }

    fun categoryCounts(playlistId: String, bucket: String): Map<String, Int> = read {
        if (bucket == LIVE) {
            return@read activeLiveCategoryCounts(playlistId, emptySet())
        }
        val map = HashMap<String, Int>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT category_id, COUNT(*) FROM channels WHERE playlist_id=? AND bucket=? AND category_id IS NOT NULL AND category_id!='' GROUP BY category_id",
            arrayOf(playlistId, bucket)
        ).use { cursor ->
            while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getInt(1)
        }
        db.rawQuery(
            "SELECT COUNT(*) FROM channels WHERE playlist_id=? AND bucket=?",
            arrayOf(playlistId, bucket)
        ).use { cursor ->
            if (cursor.moveToFirst()) map[PlaylistLoader.ALL_ID] = cursor.getInt(0)
        }
        map
    }

    fun liveCountryByCategory(playlistId: String): Map<String, String> {
        if (playlistId.isBlank()) return emptyMap()
        val stamp = libraryStamp(playlistId)
        fastCountryHold?.takeIf { it.playlistId == playlistId && it.stamp == stamp }?.let { return it.countries }
        val best = HashMap<String, String>()
        val votes = HashMap<String, Int>()
        read {
            val blank = HashMap<String, Int>()
            readableDatabase.rawQuery(
                """
                SELECT category_id, IFNULL(country_id, ''), COUNT(*)
                FROM channels
                WHERE playlist_id=? AND bucket=?
                  AND category_id IS NOT NULL AND category_id!=''
                GROUP BY category_id, IFNULL(country_id, '')
                """.trimIndent(),
                arrayOf(playlistId, LIVE)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    if (id.isEmpty()) continue
                    val country = cursor.getString(1).orEmpty()
                    val count = cursor.getInt(2)
                    if (country.isEmpty()) {
                        blank[id] = (blank[id] ?: 0) + count
                        continue
                    }
                    if (count > (votes[id] ?: 0)) {
                        votes[id] = count
                        best[id] = country
                    }
                }
            }

            val ids = best.keys.toList()
            for (id in ids) {
                if ((votes[id] ?: 0) <= (blank[id] ?: 0)) best.remove(id)
            }
        }
        fastCountryHold = FastCountryHold(playlistId, stamp, best)
        return best
    }

    fun dashboardGroups(playlistId: String): List<DashboardGroup> = read {
        val db = readableDatabase
        val names = HashMap<String, String>()
        db.query(
            "categories",
            arrayOf("bucket", "id", "name"),
            "playlist_id=?",
            arrayOf(playlistId),
            null,
            null,
            "name COLLATE NOCASE"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                names[cursor.getString(0) + "\u0000" + cursor.getString(1)] = cursor.getString(2)
            }
        }
        val counts = LinkedHashMap<String, Int>()
        val fallbackTitles = HashMap<String, String>()
        db.rawQuery(
            """
            SELECT bucket, COALESCE(NULLIF(category_id, ''), group_title), MAX(NULLIF(group_title, '')), COUNT(*)
            FROM channels
            WHERE playlist_id=?
              AND COALESCE(NULLIF(category_id, ''), group_title) IS NOT NULL
              AND COALESCE(NULLIF(category_id, ''), group_title) != ''
            GROUP BY bucket, COALESCE(NULLIF(category_id, ''), group_title)
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val b = cursor.getString(0) ?: continue
                val keyId = cursor.getString(1) ?: continue
                val key = b + "\u0000" + keyId
                val title = NameImages.strip(cursor.getString(2) ?: "")
                if (title.isNotEmpty() && title != keyId) fallbackTitles[key] = title
                counts[key] = (counts[key] ?: 0) + cursor.getInt(3)
            }
        }
        val stamp = libraryStamp(playlistId)
        val held = liveCountryHold?.takeIf { it.playlistId == playlistId && it.stamp == stamp }
        val liveCountry: HashMap<String, String>
        val titled: HashMap<String, String>
        val named: HashMap<String, String>
        if (held != null) {
            liveCountry = HashMap(held.liveCountry)
            titled = HashMap(held.titled)
            named = HashMap(held.named)
        } else {
            liveCountry = HashMap()
            val liveVotes = HashMap<String, Int>()
            titled = HashMap()
            val titleVotes = HashMap<String, Int>()
            val countrySums = HashMap<String, Int>()
            val titleSums = HashMap<String, Int>()
            db.rawQuery(
                """
                SELECT category_id, IFNULL(country_id, ''), IFNULL(group_title, ''), COUNT(*)
                FROM channels
                WHERE playlist_id=? AND bucket=? AND category_id IS NOT NULL AND category_id!=''
                GROUP BY category_id, IFNULL(country_id, ''), IFNULL(group_title, '')
                """.trimIndent(),
                arrayOf(playlistId, LIVE)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    if (id.isEmpty()) continue
                    val country = cursor.getString(1)
                    val title = cursor.getString(2)
                    val votes = cursor.getInt(3)
                    if (country.isNotEmpty()) {
                        val key = id + "\u0000" + country
                        countrySums[key] = (countrySums[key] ?: 0) + votes
                    }
                    if (title.isNotEmpty()) {
                        val key = id + "\u0000" + title
                        titleSums[key] = (titleSums[key] ?: 0) + votes
                    }
                }
            }
            for ((key, votes) in countrySums) {
                val split = key.indexOf('\u0000')
                val id = key.substring(0, split)
                if (votes > (liveVotes[id] ?: 0)) {
                    liveVotes[id] = votes
                    liveCountry[id] = key.substring(split + 1)
                }
            }
            for ((key, votes) in titleSums) {
                val split = key.indexOf('\u0000')
                val id = key.substring(0, split)
                val country = CountryIndex.idFor(key.substring(split + 1)) ?: continue
                if (votes > (titleVotes[id] ?: 0)) {
                    titleVotes[id] = votes
                    titled[id] = country
                }
            }
            val unresolved = ArrayList<String>()
            for ((key, name) in names) {
                val split = key.indexOf('\u0000')
                if (key.substring(0, split) != LIVE) continue
                val id = key.substring(split + 1)
                if (CountryIndex.idFor(name) != null || liveCountry.containsKey(id) || titled.containsKey(id)) continue
                unresolved.add(id)
            }
            named = HashMap()
            val nameVotes = HashMap<String, Int>()
            for (ids in unresolved.chunked(200)) {
                val marks = ids.joinToString(",") { "?" }
                val args = ArrayList<String>(ids.size + 2)
                args.add(playlistId)
                args.add(LIVE)
                args.addAll(ids)
                db.rawQuery(
                    "SELECT category_id, SUBSTR(name, 1, 24), COUNT(*) FROM channels WHERE playlist_id=? AND bucket=? AND category_id IN ($marks) AND name IS NOT NULL AND name!='' GROUP BY category_id, SUBSTR(name, 1, 24)",
                    args.toTypedArray()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val country = CountryIndex.idFor(cursor.getString(1)) ?: continue
                        val votes = cursor.getInt(2)
                        if (votes > (nameVotes[id] ?: 0)) {
                            nameVotes[id] = votes
                            named[id] = country
                        }
                    }
                }
            }
            liveCountryHold = LiveCountryHold(playlistId, stamp, liveCountry, titled, named)
        }
        val rows = ArrayList<DashboardGroup>(names.size)
        val seenKeys = HashSet<String>(names.size)
        var has247Group = false
        for ((key, rawName) in names) {
            seenKeys.add(key)
            val split = key.indexOf('\u0000')
            val bucket = key.substring(0, split)
            val id = key.substring(split + 1)
            val name = if (rawName == id || rawName.isBlank()) {
                fallbackTitles[key]?.takeIf { it.isNotBlank() } ?: rawName
            } else {
                rawName
            }
            if (bucket == LIVE && TwentyFourSeven.is247(name)) {
                has247Group = true
                continue
            }
            if (bucket == LIVE && AdultGroup.isAdultGroup(name)) {
                continue
            }
            val parsed = CountryIndex.idFor(name).orEmpty()
            val countryId = when {
                parsed.isNotEmpty() -> parsed
                bucket != LIVE -> ""
                else -> liveCountry[id] ?: titled[id] ?: named[id].orEmpty()
            }
            val groupCount = counts[key] ?: counts[bucket + "\u0000" + name] ?: 0
            rows.add(DashboardGroup(id, name, kindLabel(bucket), groupCount, countryId))
        }
        for ((key, count) in counts) {
            if (key in seenKeys || count <= 0) continue
            val split = key.indexOf('\u0000')
            val bucket = key.substring(0, split)
            val id = key.substring(split + 1)
            if (id == PlaylistLoader.ALL_ID) continue
            val resolvedName = fallbackTitles[key]?.takeIf { it.isNotBlank() } ?: id
            if (bucket == LIVE && TwentyFourSeven.is247(resolvedName)) {
                has247Group = true
                continue
            }
            if (bucket == LIVE && AdultGroup.isAdultGroup(resolvedName)) {
                continue
            }
            val parsed = CountryIndex.idFor(resolvedName).orEmpty()
            val countryId = when {
                parsed.isNotEmpty() -> parsed
                bucket != LIVE -> ""
                else -> liveCountry[id] ?: titled[id] ?: named[id].orEmpty()
            }
            rows.add(DashboardGroup(id, resolvedName, kindLabel(bucket), count, countryId))
        }
        val count247 = db.rawQuery(
            "SELECT COUNT(*) FROM channels WHERE playlist_id=? AND bucket=? AND ${TwentyFourSeven.SQL_MATCH}",
            arrayOf(playlistId, LIVE)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count247 > 0 || has247Group) {
            rows.add(DashboardGroup(TwentyFourSeven.GROUP_ID, TwentyFourSeven.GROUP_NAME, "LIVE", count247, ""))
        }
        val countAdult = db.rawQuery(
            "SELECT COUNT(*) FROM channels WHERE playlist_id=? AND bucket=? AND ${AdultGroup.SQL_MATCH}",
            arrayOf(playlistId, LIVE)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (countAdult > 0) {
            rows.add(DashboardGroup(AdultGroup.GROUP_ID, AdultGroup.GROUP_NAME, "LIVE", countAdult, ""))
        }
        rows
    }

    fun dashboardSnapshot(playlistId: String): DashboardSnapshot {
        val stamp = libraryStamp(playlistId)
        dashHold?.let { if (it.playlistId == playlistId && it.stamp == stamp) return it.body }
        synchronized(dashLock) {
            dashHold?.let { if (it.playlistId == playlistId && it.stamp == stamp) return it.body }
            readDash(playlistId, stamp)?.let { saved ->
                dashHold = DashHold(playlistId, stamp, saved)
                return saved
            }
            val groups = dashboardGroups(playlistId)
            val body = DashboardSnapshot(
                groups = groups,
                countries = countryChoices(playlistId, groups),
                channelCount = channelCount(playlistId)
            )
            dashHold = DashHold(playlistId, stamp, body)
            writeDash(playlistId, stamp, body)
            return body
        }
    }

    fun warmDashboard(playlistId: String) {
        if (playlistId.isBlank()) return
        runCatching { dashboardSnapshot(playlistId) }
    }

    fun bucketCounts(playlistId: String): IntArray = read {
        val counts = intArrayOf(0, 0, 0)
        readableDatabase.rawQuery(
            "SELECT bucket, COUNT(*) FROM channels WHERE playlist_id=? GROUP BY bucket",
            arrayOf(playlistId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = when (cursor.getString(0)) {
                    VOD -> 1
                    SERIES -> 2
                    else -> 0
                }
                counts[index] = cursor.getInt(1)
            }
        }
        counts
    }

    fun channelCount(playlistId: String): Int = read {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM channels WHERE playlist_id=?",
            arrayOf(playlistId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun countInGroup(playlistId: String, groupId: String, needle: String, groupName: String = ""): Int = read {
        if (groupId == PlaylistLoader.ALL_ID) {
            val (where, args) = bucketNeedleWhere(playlistId, LIVE, needle)
            return@read readableDatabase.rawQuery("SELECT COUNT(*) FROM channels WHERE $where", args).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
        if (groupId == TwentyFourSeven.GROUP_ID) {
            val (where, args) = bucketNeedleWhere(playlistId, LIVE, needle)
            return@read readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM channels WHERE $where AND ${TwentyFourSeven.SQL_MATCH}",
                args
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
        if (groupId == AdultGroup.GROUP_ID) {
            val (where, args) = bucketNeedleWhere(playlistId, LIVE, needle)
            return@read readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM channels WHERE $where AND ${AdultGroup.SQL_MATCH}",
                args
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
        val catCount = countByCategoryIndexed(playlistId, groupId, needle)
        if (catCount > 0) return@read catCount
        val titleCount = countByGroupTitleIndexed(playlistId, groupId, groupName, needle)
        if (titleCount > 0) return@read titleCount
        0
    }

    fun slimInGroup(
        playlistId: String,
        groupId: String,
        needle: String,
        limit: Int = 5000,
        byName: Boolean = false,
        groupName: String = ""
    ): List<SlimChannel> = read {
        val capped = limit.coerceAtLeast(1)
        if (groupId == PlaylistLoader.ALL_ID) {
            val (where, args) = bucketNeedleWhere(playlistId, LIVE, needle)
            return@read querySlim(where, args, byName, capped)
        }
        if (groupId == TwentyFourSeven.GROUP_ID) {
            val (where, args) = bucketNeedleWhere(playlistId, LIVE, needle)
            val all = querySlim("$where AND ${TwentyFourSeven.SQL_MATCH}", args, false, 10000)
                .map { it.copy(groupId = TwentyFourSeven.GROUP_ID) }
                .sortedWith(
                    compareBy<SlimChannel>(
                        { TwentyFourSeven.sortKey(it.name) },
                        { it.name.lowercase(java.util.Locale.US) },
                        { it.id }
                    )
                )
            return@read if (all.size <= capped) all else all.subList(0, capped)
        }

        if (groupId == AdultGroup.GROUP_ID) {
            val (where, args) = bucketNeedleWhere(playlistId, LIVE, needle)
            val all = querySlim("$where AND ${AdultGroup.SQL_MATCH}", args, true, capped)
                .map { it.copy(groupId = AdultGroup.GROUP_ID) }
            return@read all
        }

        val byCategory = queryByCategoryIndexed(playlistId, groupId, needle, byName, capped)
        if (byCategory.isNotEmpty()) return@read byCategory

        queryByGroupTitleIndexed(playlistId, groupId, groupName, needle, byName, capped)
    }

    fun sanitizeGroupNames(playlistId: String, tokens: List<String>): Int = write {
        val clean = tokens.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return@write 0
        val db = writableDatabase
        val combined = Regex(
            clean.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) },
            RegexOption.IGNORE_CASE
        )
        val multiSpace = Regex("""\s{2,}""")
        fun stripAll(raw: String): String =
            multiSpace.replace(combined.replace(raw, " "), " ").trim()
        var changed = 0
        db.beginTransaction()
        try {
            val catUpdates = ArrayList<Triple<String, String, String>>()
            db.query(
                "categories",
                arrayOf("bucket", "id", "name"),
                "playlist_id=?",
                arrayOf(playlistId),
                null,
                null,
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val bucket = cursor.getString(0) ?: continue
                    val id = cursor.getString(1) ?: continue
                    val name = cursor.getString(2) ?: continue
                    val next = stripAll(name)
                    if (next.isNotEmpty() && next != name) {
                        catUpdates.add(Triple(bucket, id, next))
                    }
                }
            }
            if (catUpdates.isNotEmpty()) {
                val stmt = db.compileStatement(
                    "UPDATE categories SET name=? WHERE playlist_id=? AND bucket=? AND id=?"
                )
                for ((bucket, id, next) in catUpdates) {
                    stmt.clearBindings()
                    stmt.bindString(1, next)
                    stmt.bindString(2, playlistId)
                    stmt.bindString(3, bucket)
                    stmt.bindString(4, id)
                    changed += stmt.executeUpdateDelete()
                }
            }
            val groupTitles = ArrayList<Pair<String, String>>()
            db.rawQuery(
                "SELECT DISTINCT group_title FROM channels WHERE playlist_id=? AND group_title IS NOT NULL AND group_title!=''",
                arrayOf(playlistId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(0) ?: continue
                    val next = stripAll(raw)
                    if (next.isNotEmpty() && next != raw) groupTitles.add(raw to next)
                }
            }
            if (groupTitles.isNotEmpty()) {
                val stmt = db.compileStatement(
                    "UPDATE channels SET group_title=? WHERE playlist_id=? AND group_title=?"
                )
                for ((raw, next) in groupTitles) {
                    stmt.clearBindings()
                    stmt.bindString(1, next)
                    stmt.bindString(2, playlistId)
                    stmt.bindString(3, raw)
                    stmt.executeUpdateDelete()
                }
            }
            if (changed > 0 || groupTitles.isNotEmpty()) {
                touchLibrary(db, playlistId)
                runCatching { dashFile().delete() }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed
    }

    private fun bucketNeedleWhere(playlistId: String, bucket: String, needle: String): Pair<String, Array<String>> {
        val where = StringBuilder("playlist_id=? AND bucket=?")
        val args = arrayListOf(playlistId, bucket)
        if (needle.isNotBlank()) {
            where.append(" AND name LIKE ? ESCAPE '\\'")
            args.add("%${likeEscape(needle)}%")
        }
        return where.toString() to args.toTypedArray()
    }

    private fun queryByCategoryIndexed(
        playlistId: String,
        categoryId: String,
        needle: String,
        byName: Boolean,
        limit: Int
    ): List<SlimChannel> {
        val out = ArrayList<SlimChannel>()
        val buckets = arrayOf(LIVE, VOD, SERIES)
        for (bucket in buckets) {
            val remaining = limit - out.size
            if (remaining <= 0) break
            val where = StringBuilder("playlist_id=? AND bucket=? AND category_id=?")
            val args = arrayListOf(playlistId, bucket, categoryId)
            if (needle.isNotBlank()) {
                where.append(" AND name LIKE ? ESCAPE '\\'")
                args.add("%${likeEscape(needle)}%")
            }
            out.addAll(querySlim(where.toString(), args.toTypedArray(), byName, remaining))
        }
        if (byName && out.size > 1) out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out
    }

    private fun countByCategoryIndexed(playlistId: String, categoryId: String, needle: String): Int {
        var total = 0
        for (bucket in arrayOf(LIVE, VOD, SERIES)) {
            val where = StringBuilder("playlist_id=? AND bucket=? AND category_id=?")
            val args = arrayListOf(playlistId, bucket, categoryId)
            if (needle.isNotBlank()) {
                where.append(" AND name LIKE ? ESCAPE '\\'")
                args.add("%${likeEscape(needle)}%")
            }
            total += readableDatabase.rawQuery("SELECT COUNT(*) FROM channels WHERE $where", args.toTypedArray()).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
        return total
    }

    private fun queryByGroupTitleIndexed(
        playlistId: String,
        groupId: String,
        groupName: String,
        needle: String,
        byName: Boolean,
        limit: Int
    ): List<SlimChannel> {
        val titles = linkedSetOf<String>()
        if (groupId.isNotBlank()) titles.add(groupId)
        if (groupName.isNotBlank()) titles.add(groupName)
        val out = ArrayList<SlimChannel>()
        val seen = HashSet<String>()
        for (bucket in arrayOf(LIVE, VOD, SERIES)) {
            for (title in titles) {
                val remaining = limit - out.size
                if (remaining <= 0) break
                val where = StringBuilder("playlist_id=? AND bucket=? AND group_title=?")
                val args = arrayListOf(playlistId, bucket, title)
                if (needle.isNotBlank()) {
                    where.append(" AND name LIKE ? ESCAPE '\\'")
                    args.add("%${likeEscape(needle)}%")
                }
                for (row in querySlim(where.toString(), args.toTypedArray(), byName, remaining)) {
                    if (seen.add(row.id)) out.add(row)
                }
            }
        }
        if (byName && out.size > 1) out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out
    }

    private fun countByGroupTitleIndexed(playlistId: String, groupId: String, groupName: String, needle: String): Int {
        val titles = linkedSetOf<String>()
        if (groupId.isNotBlank()) titles.add(groupId)
        if (groupName.isNotBlank()) titles.add(groupName)
        var total = 0
        for (bucket in arrayOf(LIVE, VOD, SERIES)) {
            for (title in titles) {
                val where = StringBuilder("playlist_id=? AND bucket=? AND group_title=?")
                val args = arrayListOf(playlistId, bucket, title)
                if (needle.isNotBlank()) {
                    where.append(" AND name LIKE ? ESCAPE '\\'")
                    args.add("%${likeEscape(needle)}%")
                }
                total += readableDatabase.rawQuery("SELECT COUNT(*) FROM channels WHERE $where", args.toTypedArray()).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        }
        return total
    }

    private fun querySlim(where: String, args: Array<String>, byName: Boolean, limit: Int): List<SlimChannel> {
        val rows = ArrayList<SlimChannel>()
        readableDatabase.query(
            "channels",
            arrayOf("id", "name", "logo_url", "category_id", "group_title", "kind"),
            where,
            args,
            null,
            null,
            if (byName) "name COLLATE NOCASE" else null,
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val category = cursor.text(3)?.takeIf { it.isNotBlank() } ?: cursor.text(4).orEmpty()
                rows.add(
                    SlimChannel(
                        cursor.getString(0),
                        cursor.getString(1),
                        category,
                        cursor.getString(5).uppercase(),
                        cursor.text(2).orEmpty()
                    )
                )
            }
        }
        return rows
    }

    fun hasLogo(playlistId: String, url: String): Boolean = read {
        if (playlistId.isBlank() || url.isBlank()) return false
        readableDatabase.query(
            "channels",
            arrayOf("id"),
            "playlist_id=? AND logo_url=?",
            arrayOf(playlistId, url),
            null,
            null,
            null,
            "1"
        ).use { it.moveToFirst() }
    }

    fun liveGuide(playlistId: String, limit: Int = LIVE_GUIDE_CAP): List<com.olympus.iptv.data.AutoFavorites.GuideChannel> = read {
        val rows = ArrayList<com.olympus.iptv.data.AutoFavorites.GuideChannel>()
        val cap = limit.coerceIn(1, LIVE_GUIDE_CAP)
        readableDatabase.rawQuery(
            "SELECT id, name, IFNULL(group_title, '') FROM channels WHERE playlist_id=? AND bucket=? LIMIT ?",
            arrayOf(playlistId, LIVE, cap.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    com.olympus.iptv.data.AutoFavorites.GuideChannel(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2)
                    )
                )
            }
        }
        rows
    }

    fun stripImageNames(playlistId: String): Pair<Int, List<StripSample>> = write {
        val db = writableDatabase
        val examples = ArrayList<StripSample>()
        var changed = 0
        db.beginTransaction()
        try {
            changed += stripTable(db, "categories", playlistId, examples)
            changed += stripTable(db, "channels", playlistId, examples)
            changed += stripGroupTitles(db, playlistId, examples)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed to examples
    }

    private fun stripTable(
        db: SQLiteDatabase,
        table: String,
        playlistId: String,
        examples: MutableList<StripSample>
    ): Int {
        var changed = 0
        val pending = ArrayList<Array<String>>()
        db.query(
            table,
            arrayOf("bucket", "id", "name"),
            "playlist_id=?",
            arrayOf(playlistId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(2)
                val next = NameImages.strip(name)
                if (next != name) pending.add(arrayOf(cursor.getString(0), cursor.getString(1), name, next))
            }
        }
        for (row in pending) {
            val values = ContentValues()
            values.put("name", row[3])
            db.update(table, values, "playlist_id=? AND bucket=? AND id=?", arrayOf(playlistId, row[0], row[1]))
            changed += 1
            if (examples.size < 4) examples.add(StripSample(row[2], row[3]))
        }
        return changed
    }

    fun replaceGroupLabels(
        playlistId: String,
        find: String,
        replacement: String
    ): Pair<Int, List<com.olympus.iptv.data.GroupRename.Sample>> =
        replaceGroupLabelsBatch(playlistId, listOf(find), replacement)

    fun replaceGroupLabelsBatch(
        playlistId: String,
        finds: List<String>,
        replacement: String
    ): Pair<Int, List<com.olympus.iptv.data.GroupRename.Sample>> {
        val needles = finds.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (playlistId.isBlank() || needles.isEmpty()) return 0 to emptyList()
        fun applyAll(input: String): String {
            var cur = input
            for (needle in needles) {
                cur = com.olympus.iptv.data.GroupRename.replaceAll(cur, needle, replacement).trim()
            }
            return cur
        }
        val samples = ArrayList<com.olympus.iptv.data.GroupRename.Sample>()
        val categoryEdits = ArrayList<Array<String>>()
        readableDatabase.query(
            "categories",
            arrayOf("bucket", "id", "name"),
            "playlist_id=?",
            arrayOf(playlistId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(2) ?: continue
                val next = applyAll(name)
                if (next.isEmpty() || next == name) continue
                categoryEdits.add(arrayOf(next, playlistId, cursor.getString(0), cursor.getString(1)))
                if (samples.size < 4) {
                    samples.add(com.olympus.iptv.data.GroupRename.Sample(name, next))
                }
            }
        }
        val titleEdits = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT group_title FROM channels WHERE playlist_id=? AND group_title IS NOT NULL AND group_title != ''",
            arrayOf(playlistId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val next = applyAll(name)
                if (next.isEmpty() || next == name) continue
                titleEdits.add(name to next)
                if (samples.size < 4 && samples.none { it.before == name }) {
                    samples.add(com.olympus.iptv.data.GroupRename.Sample(name, next))
                }
            }
        }
        if (categoryEdits.isEmpty() && titleEdits.isEmpty()) return 0 to emptyList()
        write {
            val db = writableDatabase

            runCatching { db.execSQL("DROP TRIGGER IF EXISTS channels_fts_update") }
            db.beginTransaction()
            try {
                val catStmt = db.compileStatement(
                    "UPDATE categories SET name=? WHERE playlist_id=? AND bucket=? AND id=?"
                )
                for (row in categoryEdits) {
                    catStmt.clearBindings()
                    catStmt.bindString(1, row[0])
                    catStmt.bindString(2, row[1])
                    catStmt.bindString(3, row[2])
                    catStmt.bindString(4, row[3])
                    catStmt.executeUpdateDelete()
                }
                val titleStmt = db.compileStatement(
                    "UPDATE channels SET group_title=? WHERE playlist_id=? AND group_title=?"
                )
                for ((before, after) in titleEdits) {
                    titleStmt.clearBindings()
                    titleStmt.bindString(1, after)
                    titleStmt.bindString(2, playlistId)
                    titleStmt.bindString(3, before)
                    titleStmt.executeUpdateDelete()
                }
                touchLibrary(db, playlistId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                runCatching { ensureSearchTriggers(db) }
            }
        }
        val changed = if (categoryEdits.isNotEmpty()) categoryEdits.size else titleEdits.size
        return changed to samples
    }

    fun categoryNames(playlistId: String): Map<String, String> = read {
        val names = HashMap<String, String>()
        readableDatabase.query(
            "categories",
            arrayOf("bucket", "id", "name"),
            "playlist_id=?",
            arrayOf(playlistId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                names[cursor.getString(0) + "\u0000" + cursor.getString(1)] = cursor.getString(2)
            }
        }
        names
    }

    fun cleanGroupImages(playlistId: String): Int = write {
        if (playlistId.isBlank()) return@write 0
        val stamp = libraryStamp(playlistId)
        if (imagesAlreadyClean(playlistId, stamp)) return@write 0
        val db = writableDatabase
        db.beginTransaction()
        val changed = try {
            val count = stripTable(db, "categories", playlistId, ArrayList()) +
                stripGroupTitles(db, playlistId, ArrayList())
            if (count > 0) touchLibrary(db, playlistId)
            db.setTransactionSuccessful()
            count
        } finally {
            db.endTransaction()
        }
        rememberClean(playlistId, libraryStamp(playlistId))
        changed
    }

    @Volatile
    private var cleanedImages: Pair<String, Long>? = null

    private fun imagesAlreadyClean(playlistId: String, stamp: Long): Boolean {
        if (cleanedImages == playlistId to stamp) return true
        val text = runCatching { File(filesDir, "group-images.stamp").readText() }.getOrNull() ?: return false
        val lines = text.lines()
        if (lines.size < 2 || lines[0] != playlistId || lines[1] != stamp.toString()) return false
        cleanedImages = playlistId to stamp
        return true
    }

    private fun rememberClean(playlistId: String, stamp: Long) {
        cleanedImages = playlistId to stamp
        runCatching { File(filesDir, "group-images.stamp").writeText("$playlistId\n$stamp") }
    }

    fun readEpgForChannels(
        playlistId: String,
        ids: Collection<String>
    ): Map<String, List<com.olympus.iptv.data.EpgProgram>> = read {
        if (playlistId.isBlank() || ids.isEmpty()) return@read emptyMap()
        val out = HashMap<String, List<com.olympus.iptv.data.EpgProgram>>()
        val db = readableDatabase
        val freshAfter = System.currentTimeMillis() - 6L * 3_600_000L
        for (chunk in ids.asSequence().filter { it.isNotBlank() }.distinct().chunked(200)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = ArrayList<String>(chunk.size + 1)
            args.add(playlistId)
            args.addAll(chunk)
            db.rawQuery(
                "SELECT channel_id, payload, fetched_at FROM epg_cache WHERE playlist_id=? AND channel_id IN ($marks)",
                args.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getLong(2) < freshAfter) continue
                    val id = cursor.getString(0) ?: continue
                    val payload = cursor.getString(1) ?: continue
                    out[id] = decodeEpg(payload)
                }
            }
        }
        out
    }

    fun readEpg(playlistId: String, channelId: String): Pair<Long, List<com.olympus.iptv.data.EpgProgram>>? = read {
        readableDatabase.query(
            "epg_cache",
            arrayOf("fetched_at", "payload"),
            "playlist_id=? AND channel_id=?",
            arrayOf(playlistId, channelId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getLong(0) to decodeEpg(cursor.getString(1))
        }
    }

    fun writeEpg(playlistId: String, channelId: String, programs: List<com.olympus.iptv.data.EpgProgram>) = write {
        val now = System.currentTimeMillis()
        val values = ContentValues()
        values.put("playlist_id", playlistId)
        values.put("channel_id", channelId)
        values.put("fetched_at", now)
        values.put("payload", encodeEpg(programs))
        val db = writableDatabase
        db.insertWithOnConflict("epg_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        val activeProg = programs.firstOrNull { now >= it.startMillis && now < it.endMillis }
            ?: programs.firstOrNull { it.startMillis > now }
        if (activeProg != null && NameStrip.isPlaceholderEvent(activeProg.title)) {
            db.execSQL(
                "UPDATE channels SET is_event_slot=1, event_start_ms=0, event_end_ms=0 WHERE playlist_id=? AND bucket=? AND (id=? OR epg_channel_id=?)",
                arrayOf(playlistId, LIVE, channelId, channelId)
            )
        } else if (activeProg != null && activeProg.startMillis > 0L && activeProg.endMillis > activeProg.startMillis) {
            db.execSQL(
                "UPDATE channels SET event_start_ms=?, event_end_ms=? WHERE playlist_id=? AND bucket=? AND is_event_slot=1 AND (id=? OR epg_channel_id=?)",
                arrayOf<Any>(activeProg.startMillis, activeProg.endMillis, playlistId, LIVE, channelId, channelId)
            )
        }
    }

    fun maintainDaily() = write {
        val stamp = File(filesDir, "catalog-maintain.day")
        val day = System.currentTimeMillis() / 86_400_000L
        val last = runCatching { stamp.readText().trim().toLong() }.getOrDefault(-1L)
        if (last == day) return@write
        val cutoff = System.currentTimeMillis() - 86_400_000L
        val db = writableDatabase
        db.delete("epg_cache", "fetched_at < ?", arrayOf(cutoff.toString()))

        runCatching { db.execSQL("VACUUM") }
        runCatching { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        runCatching { db.execSQL("PRAGMA optimize") }
        runCatching { stamp.writeText(day.toString()) }
    }

    fun markWatchedAll(playlistId: String, seriesId: String, season: Int, episodeIds: List<String>) = write {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (episodeId in episodeIds) {
                val values = ContentValues()
                values.put("playlist_id", playlistId)
                values.put("episode_id", episodeId)
                values.put("series_id", seriesId)
                values.put("season", season)
                db.insertWithOnConflict("watched", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearWatched(playlistId: String, episodeIds: List<String>) = write {
        val db = writableDatabase
        for (episodeId in episodeIds) {
            db.delete("watched", "playlist_id=? AND episode_id=?", arrayOf(playlistId, episodeId))
        }
    }

    fun markWatched(playlistId: String, episodeId: String, seriesId: String?, season: Int?) = write {
        val values = ContentValues()
        values.put("playlist_id", playlistId)
        values.put("episode_id", episodeId)
        if (seriesId.isNullOrBlank()) values.putNull("series_id") else values.put("series_id", seriesId)
        if (season == null) values.putNull("season") else values.put("season", season)
        writableDatabase.insertWithOnConflict("watched", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun watchedEpisodes(playlistId: String, seriesId: String): Set<String> = read {
        val ids = HashSet<String>()
        readableDatabase.query(
            "watched",
            arrayOf("episode_id"),
            "playlist_id=? AND series_id=?",
            arrayOf(playlistId, seriesId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        ids
    }

    fun freshTitles(playlistId: String): List<FreshTitle> = read {
        val rows = ArrayList<FreshTitle>()
        readableDatabase.rawQuery(
            """
            SELECT c.id, c.name, c.logo_url, c.bucket, IFNULL(c.group_title, ''), IFNULL(cat.name, '')
            FROM channels c
            LEFT JOIN categories cat
              ON cat.playlist_id = c.playlist_id AND cat.bucket = c.bucket AND cat.id = c.category_id
            WHERE c.playlist_id = ? AND c.bucket IN (?, ?)
              AND (
                c.group_title LIKE '%new%' COLLATE NOCASE
                OR cat.name LIKE '%new%' COLLATE NOCASE
                OR c.name LIKE '%New to %' COLLATE NOCASE
              )
            LIMIT 300
            """.trimIndent(),
            arrayOf(playlistId, VOD, SERIES)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val bucket = cursor.getString(3) ?: continue
                val group = cursor.getString(4)
                val category = cursor.getString(5)
                val service = serviceOf(group, category, cursor.getString(1) ?: "") ?: continue
                val name = NameImages.strip(cursor.getString(1) ?: continue)
                val episode = episodeLabel(name)
                val detail = if (episode != null) episode else NameImages.strip(category.ifBlank { group })
                rows.add(
                    FreshTitle(
                        id = id,
                        bucket = bucket,
                        name = name,
                        logoUrl = cursor.getString(2),
                        service = service,
                        detail = detail
                    )
                )
                if (rows.size == 40) break
            }
        }
        rows
    }

    private fun stripGroupTitles(
        db: SQLiteDatabase,
        playlistId: String,
        examples: MutableList<StripSample>
    ): Int {
        val pending = ArrayList<Pair<String, String>>()
        db.rawQuery(
            "SELECT DISTINCT group_title FROM channels WHERE playlist_id=? AND group_title IS NOT NULL",
            arrayOf(playlistId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val next = NameImages.strip(name)
                if (next != name) pending.add(name to next)
            }
        }
        var changed = 0
        for ((before, after) in pending) {
            val values = ContentValues()
            values.put("group_title", after)
            changed += db.update("channels", values, "playlist_id=? AND group_title=?", arrayOf(playlistId, before))
            if (examples.size < 4) examples.add(StripSample(before, after))
        }
        return changed
    }

    private fun encodeEpg(programs: List<com.olympus.iptv.data.EpgProgram>): String =
        programs.joinToString("\n") { program ->
            listOf(
                program.startMillis.toString(),
                program.endMillis.toString(),
                program.title.replace('\t', ' ').replace('\n', ' '),
                program.description.orEmpty().replace('\t', ' ').replace('\n', ' ')
            ).joinToString("\t")
        }

    private fun decodeEpg(payload: String?): List<com.olympus.iptv.data.EpgProgram> {
        if (payload.isNullOrBlank()) return emptyList()
        val programs = ArrayList<com.olympus.iptv.data.EpgProgram>()
        for (line in payload.lineSequence()) {
            val parts = line.split('\t', limit = 4)
            if (parts.size < 3) continue
            val start = parts[0].toLongOrNull() ?: continue
            val end = parts[1].toLongOrNull() ?: continue
            programs.add(
                com.olympus.iptv.data.EpgProgram(
                    title = parts[2],
                    startMillis = start,
                    endMillis = end,
                    description = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                )
            )
        }
        return programs
    }

    private fun likeEscape(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun kindLabel(bucket: String): String = when (bucket) {
        VOD -> "VOD"
        SERIES -> "SERIES"
        else -> "LIVE"
    }

    private fun fts5Available(db: SQLiteDatabase): Boolean = try {
        db.rawQuery("SELECT sqlite_compileoption_used('ENABLE_FTS5')", null).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    } catch (_: Exception) {
        false
    }

    private fun dashFile(): File = File(filesDir, "dashboard-snapshot.json")

    private fun searchExportFile(playlistId: String): File =
        File(filesDir, "search-export-${playlistId.hashCode()}.json")

    private fun readSearchExport(
        playlistId: String,
        stamp: Long
    ): Pair<List<Map<String, String>>, List<Map<String, String>>>? {
        val file = searchExportFile(playlistId)
        if (!file.exists()) return null
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root.get("playlistId")?.asString != playlistId || root.get("stamp")?.asLong != stamp) return null
            val groups = ArrayList<Map<String, String>>()
            root.getAsJsonArray("groups")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val row = element.asJsonObject
                groups.add(
                    mapOf(
                        "id" to (row.get("id")?.asString ?: return@forEach),
                        "name" to (row.get("name")?.asString ?: return@forEach),
                        "kind" to (row.get("kind")?.asString ?: return@forEach)
                    )
                )
            }
            val channels = ArrayList<Map<String, String>>()
            root.getAsJsonArray("channels")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val row = element.asJsonObject
                channels.add(
                    mapOf(
                        "id" to (row.get("id")?.asString ?: return@forEach),
                        "name" to (row.get("name")?.asString ?: ""),
                        "groupId" to (row.get("groupId")?.asString ?: ""),
                        "kind" to (row.get("kind")?.asString ?: "LIVE"),
                        "logo" to (row.get("logo")?.asString ?: "")
                    )
                )
            }
            groups to channels
        }.getOrNull()
    }

    private fun writeSearchExport(
        playlistId: String,
        stamp: Long,
        groups: List<Map<String, String>>,
        channels: List<Map<String, String>>
    ) {
        val root = JsonObject()
        root.addProperty("playlistId", playlistId)
        root.addProperty("stamp", stamp)
        val groupRows = com.google.gson.JsonArray()
        for (group in groups) {
            val row = JsonObject()
            row.addProperty("id", group["id"])
            row.addProperty("name", group["name"])
            row.addProperty("kind", group["kind"])
            groupRows.add(row)
        }
        val channelRows = com.google.gson.JsonArray()
        for (channel in channels) {
            val row = JsonObject()
            row.addProperty("id", channel["id"])
            row.addProperty("name", channel["name"])
            row.addProperty("groupId", channel["groupId"])
            row.addProperty("kind", channel["kind"])
            row.addProperty("logo", channel["logo"])
            channelRows.add(row)
        }
        root.add("groups", groupRows)
        root.add("channels", channelRows)
        runCatching { searchExportFile(playlistId).writeText(root.toString()) }
    }

    private fun readDash(playlistId: String, stamp: Long): DashboardSnapshot? {
        val file = dashFile()
        if (!file.exists()) return null
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root.get("playlistId")?.asString != playlistId || root.get("stamp")?.asLong != stamp) return null
            val groupRows = root.getAsJsonArray("groups")
            if (root.get("countryBasis")?.asString != "sql4") return null
            if (groupRows != null && groupRows.size() > 0) {
                val first = groupRows[0]
                if (!first.isJsonObject || !first.asJsonObject.has("countryId")) return null
            }
            val groups = ArrayList<DashboardGroup>()
            groupRows?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val row = element.asJsonObject
                groups.add(
                    DashboardGroup(
                        id = row.get("id")?.asString ?: return@forEach,
                        name = row.get("name")?.asString ?: return@forEach,
                        kind = row.get("kind")?.asString ?: return@forEach,
                        count = row.get("count")?.asInt ?: 0,
                        countryId = row.get("countryId")?.asString.orEmpty()
                    )
                )
            }
            val countries = ArrayList<CountryOption>()
            root.getAsJsonArray("countries")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val row = element.asJsonObject
                countries.add(
                    CountryOption(
                        id = row.get("id")?.asString ?: return@forEach,
                        label = row.get("label")?.asString ?: return@forEach,
                        count = row.get("count")?.asInt ?: 0
                    )
                )
            }
            DashboardSnapshot(groups, countries, root.get("channelCount")?.asInt ?: 0)
        }.getOrNull()
    }

    private fun writeDash(playlistId: String, stamp: Long, body: DashboardSnapshot) {
        val root = JsonObject()
        root.addProperty("playlistId", playlistId)
        root.addProperty("stamp", stamp)
        root.addProperty("channelCount", body.channelCount)
        root.addProperty("countryBasis", "sql4")
        val groups = com.google.gson.JsonArray()
        for (group in body.groups) {
            val row = JsonObject()
            row.addProperty("id", group.id)
            row.addProperty("name", group.name)
            row.addProperty("kind", group.kind)
            row.addProperty("count", group.count)
            row.addProperty("countryId", group.countryId)
            groups.add(row)
        }
        val countries = com.google.gson.JsonArray()
        for (country in body.countries) {
            val row = JsonObject()
            row.addProperty("id", country.id)
            row.addProperty("label", country.label)
            row.addProperty("count", country.count)
            countries.add(row)
        }
        root.add("groups", groups)
        root.add("countries", countries)
        runCatching { dashFile().writeText(root.toString()) }
    }

    companion object {
        private const val LIVE_GUIDE_CAP = 25_000
        const val META_MISS_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val LIVE = "live"
        const val VOD = "vod"
        const val SERIES = "series"
        private const val PARSE_EXAMPLES = 4
        private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val NEW_WORD_REGEX = Regex("\\bnew\\b")
        private val episodePattern = Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b|\\bSeason\\s+(\\d{1,2})\\s+Episode\\s+(\\d{1,3})\\b")
        private val services = listOf(
            "netflix" to "Netflix",
            "hulu" to "Hulu",
            "amazon" to "Amazon",
            "prime video" to "Amazon",
            "paramount" to "Paramount+",
            "apple tv" to "Apple TV",
            "appletv" to "Apple TV",
            "disney" to "Disney+",
            "hbo" to "Max",
            "peacock" to "Peacock"
        )

        private fun serviceOf(group: String, category: String, name: String): String? {
            val haystack = "$group $category $name".lowercase()
            if (!NEW_WORD_REGEX.containsMatchIn(haystack) && !haystack.contains("this week")) return null
            return services.firstOrNull { haystack.contains(it.first) }?.second
        }

        private fun episodeLabel(name: String): String? {
            val match = episodePattern.find(name) ?: return null
            val season = match.groupValues[1].ifBlank { match.groupValues[3] }
            val episode = match.groupValues[2].ifBlank { match.groupValues[4] }
            if (season.isBlank() || episode.isBlank()) return null
            return "Season $season, Episode $episode"
        }

        private fun clipExample(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.length <= 72) trimmed else trimmed.take(69).trimEnd() + "…"
        }

        private fun clipImageSample(value: String): String = clipExample(value)

        private val LIST_COLUMNS = arrayOf(
            "id",
            "name",
            "logo_url",
            "group_title",
            "category_id",
            "kind",
            "container_extension",
            "epg_channel_id",
            "country_id",
            "country_label",
            "number",
            "series_id",
            "catchup_type",
            "catchup_source",
            "catchup_days",
            "tv_archive",
            "tv_archive_duration"
        )

        private val CHANNEL_COLUMNS = arrayOf(
            "id",
            "name",
            "stream_url",
            "logo_url",
            "group_title",
            "category_id",
            "kind",
            "container_extension",
            "epg_channel_id",
            "country_id",
            "country_label",
            "number",
            "series_id",
            "catchup_type",
            "catchup_source",
            "catchup_days",
            "tv_archive",
            "tv_archive_duration"
        )
    }
}
