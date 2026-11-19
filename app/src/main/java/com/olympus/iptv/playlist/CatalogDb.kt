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

class CatalogDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "olympus-iptv.db", null, 10) {
    private val filesDir = context.applicationContext.filesDir
    private val writer = ReentrantLock()
    private val dashLock = Any()
    @Volatile private var dashHold: DashHold? = null
    @Volatile private var liveCountryHold: LiveCountryHold? = null
    @Volatile private var searchExportHold: SearchExportHold? = null

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
        applyPragma(db, "PRAGMA synchronous=NORMAL")
        applyPragma(db, "PRAGMA temp_store=MEMORY")
        applyPragma(db, "PRAGMA busy_timeout=3000")
        applyPragma(db, "PRAGMA cache_size=-16384")
        applyPragma(db, "PRAGMA mmap_size=33554432")
        db.setMaxSqlCacheSize(50)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS library_meta (playlist_id TEXT PRIMARY KEY, stamp INTEGER NOT NULL)"
        )
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
            if (found.size >= limit) return found
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
            found
        }
    }

    data class NamedChannel(val id: String, val name: String, val group: String)

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
        db.beginTransaction()
        try {
            val categoryStmt = db.compileStatement(
                "INSERT OR IGNORE INTO categories (playlist_id, bucket, id, name) VALUES (?,?,?,?)"
            )
            for (category in categories) {
                categoryStmt.clearBindings()
                categoryStmt.bindString(1, playlistId)
                categoryStmt.bindString(2, bucket)
                categoryStmt.bindString(3, category.id)
                categoryStmt.bindString(4, category.name)
                if (categoryStmt.executeInsert() != -1L) newCategories.add(category)
            }
            val channelStmt = db.compileStatement(
                """
                INSERT OR IGNORE INTO channels (
                    playlist_id, bucket, id, name, stream_url, logo_url, group_title, category_id,
                    kind, container_extension, epg_channel_id, country_id, country_label, number, series_id,
                    catchup_type, catchup_source, catchup_days, tv_archive, tv_archive_duration
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            val catchupStmt = db.compileStatement(
                """
                UPDATE channels SET catchup_type=?, catchup_source=?, catchup_days=?, tv_archive=?, tv_archive_duration=?
                WHERE playlist_id=? AND bucket=? AND id=?
                """.trimIndent()
            )
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
                    if (replaceTeam.executeUpdateDelete() > 0) newChannels.add(channel)
                } else {
                    catchupStmt.clearBindings()
                    bindCatchup(catchupStmt, 1, channel)
                    catchupStmt.bindString(6, playlistId)
                    catchupStmt.bindString(7, bucket)
                    catchupStmt.bindString(8, channel.id)
                    catchupStmt.executeUpdateDelete()
                }
            }
            if (newCategories.isNotEmpty() || newChannels.isNotEmpty()) touchLibrary(db, playlistId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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
        db.execSQL(
            "INSERT INTO library_meta(playlist_id, stamp) VALUES(?, 1) ON CONFLICT(playlist_id) DO UPDATE SET stamp = stamp + 1",
            arrayOf(playlistId)
        )
    }

    private fun readCategories(db: SQLiteDatabase, playlistId: String, bucket: String): List<Category> {
        val kind = if (bucket == LIVE) ContentKind.LIVE else ContentKind.VOD
        val categories = ArrayList<Category>()
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
                val shown = NameImages.strip(cursor.getString(1) ?: "")
            categories.add(Category(id = cursor.getString(0), name = shown, kind = kind))
            }
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

    private fun ftsMatch(text: String): String? {
        val tokens = text.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"${it.lowercase()}\"*" }
    }

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
    }

    private fun ensureSearch(db: SQLiteDatabase) {
        if (!fts5Available(db)) return
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
        val watchmodeId: Int = 0
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

    fun categoryCounts(playlistId: String, bucket: String): Map<String, Int> = read {
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
        db.rawQuery(
            "SELECT bucket, category_id, COUNT(*) FROM channels WHERE playlist_id=? AND category_id IS NOT NULL AND category_id!='' GROUP BY bucket, category_id",
            arrayOf(playlistId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                counts[cursor.getString(0) + "\u0000" + cursor.getString(1)] = cursor.getInt(2)
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
        for ((key, name) in names) {
            val split = key.indexOf('\u0000')
            val bucket = key.substring(0, split)
            val id = key.substring(split + 1)
            val parsed = CountryIndex.idFor(name).orEmpty()
            val countryId = when {
                parsed.isNotEmpty() -> parsed
                bucket != LIVE -> ""
                else -> liveCountry[id] ?: titled[id] ?: named[id].orEmpty()
            }
            rows.add(DashboardGroup(id, name, kindLabel(bucket), counts[key] ?: 0, countryId))
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

    fun countInGroup(playlistId: String, groupId: String, needle: String): Int = read {
        val (where, args) = groupMatch(playlistId, groupId, needle)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM channels WHERE $where", args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun slimInGroup(
        playlistId: String,
        groupId: String,
        needle: String,
        limit: Int = 5000,
        byName: Boolean = false
    ): List<SlimChannel> = read {
        val (where, args) = groupMatch(playlistId, groupId, needle)
        val rows = ArrayList<SlimChannel>()
        readableDatabase.query(
            "channels",
            arrayOf("id", "name", "logo_url", "category_id", "group_title", "kind"),
            where,
            args,
            null,
            null,
            if (byName) "name COLLATE NOCASE" else null,
            limit.coerceAtLeast(1).toString()
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
        rows
    }

    private fun groupMatch(playlistId: String, groupId: String, needle: String): Pair<String, Array<String>> {
        val where = StringBuilder("playlist_id=? AND (category_id=? OR group_title=?)")
        val args = arrayListOf(playlistId, groupId, groupId)
        if (needle.isNotBlank()) {
            where.append(" AND name LIKE ? ESCAPE '\\'")
            args.add("%${likeEscape(needle)}%")
        }
        return where.toString() to args.toTypedArray()
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
    ): Pair<Int, List<com.olympus.iptv.data.GroupRename.Sample>> {
        val needle = find.trim()
        if (playlistId.isBlank() || needle.isEmpty()) return 0 to emptyList()
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
                val next = com.olympus.iptv.data.GroupRename.replaceAll(name, needle, replacement).trim()
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
                val next = com.olympus.iptv.data.GroupRename.replaceAll(name, needle, replacement).trim()
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
            db.beginTransaction()
            try {
                for (row in categoryEdits) {
                    val values = ContentValues()
                    values.put("name", row[0])
                    db.update("categories", values, "playlist_id=? AND bucket=? AND id=?", arrayOf(row[1], row[2], row[3]))
                }
                for ((before, after) in titleEdits) {
                    val values = ContentValues()
                    values.put("group_title", after)
                    db.update("channels", values, "playlist_id=? AND group_title=?", arrayOf(playlistId, before))
                }
                touchLibrary(db, playlistId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
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
        val values = ContentValues()
        values.put("playlist_id", playlistId)
        values.put("channel_id", channelId)
        values.put("fetched_at", System.currentTimeMillis())
        values.put("payload", encodeEpg(programs))
        writableDatabase.insertWithOnConflict("epg_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
                val group = cursor.getString(4)
                val category = cursor.getString(5)
                val service = serviceOf(group, category, cursor.getString(1) ?: "") ?: continue
                val name = NameImages.strip(cursor.getString(1) ?: continue)
                val episode = episodeLabel(name)
                val detail = if (episode != null) episode else NameImages.strip(category.ifBlank { group })
                rows.add(
                    FreshTitle(
                        id = cursor.getString(0),
                        bucket = cursor.getString(3),
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
            if (root.get("countryBasis")?.asString != "sql") return null
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
        root.addProperty("countryBasis", "sql")
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
        const val LIVE = "live"
        const val VOD = "vod"
        const val SERIES = "series"
        private const val PARSE_EXAMPLES = 4
        private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
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
            if (!Regex("\\bnew\\b").containsMatchIn(haystack) && !haystack.contains("this week")) return null
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
