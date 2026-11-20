package com.olympus.iptv.playlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.olympus.iptv.BuildConfig
import com.olympus.iptv.data.NameImages
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

object Watchmode {
    private val http get() = HttpClients.shared
    private val dayLabel = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val stamp = DateTimeFormatter.BASIC_ISO_DATE
    private val services = mapOf(
        203 to "Netflix",
        26 to "Prime Video",
        372 to "Disney+",
        157 to "Hulu",
        387 to "Max",
        371 to "Apple TV+",
        388 to "Peacock",
        444 to "Paramount+",
        455 to "Paramount+"
    )
    private val serviceWords = mapOf(
        "Netflix" to listOf("netflix"),
        "Prime Video" to listOf("prime", "amazon"),
        "Disney+" to listOf("disney"),
        "Hulu" to listOf("hulu"),
        "Max" to listOf("max", "hbo"),
        "Apple TV+" to listOf("apple"),
        "Peacock" to listOf("peacock"),
        "Paramount+" to listOf("paramount")
    )

    fun weekRange(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
        val start = today.minusDays((today.dayOfWeek.value % 7).toLong())
        return start to start.plusDays(6)
    }

    fun weekKey(today: LocalDate = LocalDate.now()): String = weekRange(today).first.format(stamp)

    fun peek(cacheFile: File, today: LocalDate = LocalDate.now()): List<Premiere>? =
        readCache(cacheFile, weekKey(today))

    fun needsRefresh(cacheFile: File): Boolean {
        val held = weekHold(cacheFile, weekKey()) ?: return true
        return System.currentTimeMillis() - held.fetchedAt >= DAY_MS
    }

    fun lookupPrefixes(premieres: List<Premiere>): List<String> {
        val seen = HashSet<String>()
        val prefixes = ArrayList<String>(premieres.size * 2)
        for (premiere in premieres) {
            val folded = foldTitle(premiere.title)
            val words = folded.split(' ').filter { it.isNotBlank() }
            if (words.size >= 2) addPrefix(prefixes, seen, words.take(2).joinToString(" "))
            if (words.size >= 3) addPrefix(prefixes, seen, words.take(3).joinToString(" "))
            addPrefix(prefixes, seen, folded)
            addPrefix(prefixes, seen, premiere.title)
            if (prefixes.size >= MAX_PREFIXES) break
        }
        return prefixes
    }

    fun load(cacheFile: File, today: LocalDate = LocalDate.now()): List<Premiere> {
        peek(cacheFile, today)?.let { return it }
        return refresh(cacheFile, today)
    }

    fun refresh(cacheFile: File, today: LocalDate = LocalDate.now()): List<Premiere> {
        val (start, end) = weekRange(today)
        val week = start.format(stamp)
        val cached = readCache(cacheFile, week)
        val key = BuildConfig.WATCHMODE_API_KEY.trim()
        if (key.isEmpty()) {
            if (cached != null) return cached
            throw WatchmodeException(WatchmodeException.Kind.MISSING_KEY)
        }
        if (cached != null && System.currentTimeMillis() - cacheAge(cacheFile) < DAY_MS) return cached
        val fresh = runCatching { fetch(key, start, end) }.getOrElse { error ->
            if (cached != null) return cached
            throw error
        }
        writeCache(cacheFile, week, fresh)
        return fresh
    }

    fun synopsis(cacheFile: File, id: Int): String? {
        val key = BuildConfig.WATCHMODE_API_KEY.trim()
        if (key.isEmpty() || id <= 0) return null
        val details = runCatching { details(key, id) }.getOrNull() ?: return null
        val plot = details.first?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val body = if (plot.endsWith(".")) plot else "$plot."
        runCatching { updateCachedPlot(cacheFile, id, body, details.second) }
        return body
    }

    fun describe(cacheFile: File, premieres: List<Premiere>, wanted: Set<Int>): List<Premiere> {
        val key = BuildConfig.WATCHMODE_API_KEY.trim()
        if (key.isEmpty()) return premieres
        val pending = premieres.filter { it.id in wanted && it.id > 0 && it.plot.isNullOrBlank() }.take(24)
        if (pending.isEmpty()) return premieres
        val filled = premieres.toMutableList()
        var changed = false
        for (item in pending) {
            val details = runCatching { details(key, item.id) }.getOrNull() ?: continue
            val index = filled.indexOfFirst { it.id == item.id && it.sourceId == item.sourceId && it.date == item.date }
            if (index < 0) continue
            val plot = details.first?.takeIf { it.isNotBlank() }
            val poster = filled[index].poster ?: details.second?.takeIf { it.isNotBlank() }
            if (plot == null && poster == filled[index].poster) continue
            filled[index] = filled[index].copy(plot = plot ?: filled[index].plot, poster = poster)
            changed = true
        }
        if (changed) {
            val week = weekRange().first.format(stamp)
            writeCache(cacheFile, week, filled)
        }
        return filled
    }

    fun match(premieres: List<Premiere>, catalog: List<CatalogDb.CatalogTitle>): List<CatalogDb.FreshTitle> {
        if (premieres.isEmpty() || catalog.isEmpty()) return emptyList()
        val exact = HashMap<String, MutableList<CatalogDb.CatalogTitle>>(catalog.size)
        val buckets = HashMap<String, MutableList<String>>()
        val heads = HashMap<String, MutableList<String>>()
        for (row in catalog) {
            val key = foldTitle(row.name)
            if (key.length < 3) continue
            exact.getOrPut(key) { ArrayList(1) }.add(row)
            buckets.getOrPut(key.take(4)) { ArrayList(1) }.add(key)
            val words = key.split(' ')
            if (words.size >= 2) {
                val head = words[0] + ' ' + words[1]
                heads.getOrPut(head) { ArrayList(1) }.add(key)
            }
        }
        val cards = ArrayList<CatalogDb.FreshTitle>()
        val seen = HashSet<String>()
        for (premiere in premieres.sortedWith(compareBy({ it.date }, { it.title }))) {
            val key = foldTitle(premiere.title)
            if (key.length < 3) continue
            val hit = best(premiere, key, exact, buckets[key.take(4)].orEmpty(), heads) ?: continue
            val marker = "${hit.bucket}:${hit.id}:${premiere.sourceId}"
            if (!seen.add(marker)) continue
            cards.add(
                CatalogDb.FreshTitle(
                    id = hit.id,
                    bucket = hit.bucket,
                    name = premiere.title,
                    logoUrl = imageUrl(hit.logoUrl) ?: imageUrl(premiere.poster),
                    service = premiere.service,
                    detail = detail(premiere),
                    day = premiere.day,
                    watchmodeId = premiere.id,
                    season = premiere.season?.takeIf { it > 0 }
                )
            )
        }
        return cards
    }

    private fun imageUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return null
        return when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            value.startsWith("//") -> "https:$value"
            else -> null
        }
    }

    private fun best(
        premiere: Premiere,
        key: String,
        exact: Map<String, List<CatalogDb.CatalogTitle>>,
        nearby: List<String>,
        heads: Map<String, List<String>>
    ): CatalogDb.CatalogTitle? {
        val wantSeries = premiere.type.startsWith("tv")
        val pool = ArrayList<CatalogDb.CatalogTitle>(4)
        exact[key]?.let { pool.addAll(it) }
        for (catalogKey in nearby) {
            if (catalogKey == key || !catalogKey.startsWith("$key ")) continue
            val rest = catalogKey.removePrefix("$key ").trim()
            val usable = rest.matches(YEAR) || rest.matches(SEASON_TOKEN) || rest.startsWith("season ")
            if (!usable) continue
            exact[catalogKey]?.let { pool.addAll(it) }
        }
        if (pool.isEmpty()) {
            val parts = key.split(' ')
            if (parts.size >= 2) {
                val head = parts[0] + ' ' + parts[1]
                for (catalogKey in heads[head].orEmpty()) {
                    if (catalogKey == key || !relatedTitle(key, catalogKey, wantSeries)) continue
                    exact[catalogKey]?.let { pool.addAll(it) }
                }
            }
        }
        if (pool.isEmpty()) return null
        val words = serviceWords[premiere.service].orEmpty()
        return pool.maxByOrNull { row ->
            val folded = foldTitle(row.name)
            var score = when {
                folded == key -> 100
                relatedTitle(key, folded, wantSeries) -> 70
                else -> 60
            }
            val group = row.group
            if (words.any { group.contains(it, ignoreCase = true) }) score += 25
            if (wantSeries && row.bucket == CatalogDb.SERIES) score += 10
            if (!wantSeries && row.bucket == CatalogDb.VOD) score += 10
            score
        }
    }

    private fun relatedTitle(premiereKey: String, catalogKey: String, wantSeries: Boolean): Boolean {
        if (catalogKey.startsWith("$premiereKey ")) return true
        if (premiereKey.startsWith("$catalogKey ") && catalogKey.length >= 8) return true
        if (!wantSeries) return false
        if (premiereKey.length >= 10 && catalogKey.contains(premiereKey)) return true
        if (catalogKey.length >= 10 && premiereKey.contains(catalogKey)) return true
        val premiereWords = premiereKey.split(' ')
        val catalogWords = catalogKey.split(' ')
        if (premiereWords.size < 2 || catalogWords.size < 2) return false
        return premiereWords[0] == catalogWords[0] && premiereWords[1] == catalogWords[1]
    }

    private fun detail(premiere: Premiere): String {
        val plot = premiere.plot?.trim().orEmpty()
        val season = premiere.season?.takeIf { it > 0 }?.let { "Season $it" }
        if (season == null && plot.isBlank()) return "Premieres ${premiere.day}."
        if (plot.isBlank()) return "$season."
        val body = if (plot.endsWith(".")) plot else "$plot."
        return if (season == null) body else "$season. $body"
    }

    private fun fetch(key: String, start: LocalDate, end: LocalDate): List<Premiere> {
        val url = "https://api.watchmode.com/v1/releases/?start_date=${start.format(stamp)}&end_date=${end.format(stamp)}&limit=250"
        val body = get(key, url)
        val root = JsonParser.parseString(body)
        val rows = when {
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("releases") ?: JsonArray()
            root.isJsonArray -> root.asJsonArray
            else -> JsonArray()
        }
        val premieres = ArrayList<Premiere>()
        val seen = HashSet<String>()
        for (element in rows) {
            if (!element.isJsonObject) continue
            val row = element.asJsonObject
            val sourceId = row.int("source_id") ?: continue
            val service = services[sourceId] ?: continue
            val title = row.string("title") ?: continue
            val date = row.string("source_release_date") ?: continue
            val day = runCatching { LocalDate.parse(date).format(dayLabel) }.getOrNull() ?: continue
            if (!seen.add("$sourceId|$title|$date")) continue
            premieres.add(
                Premiere(
                    id = row.int("id") ?: 0,
                    title = title,
                    type = row.string("type").orEmpty(),
                    sourceId = sourceId,
                    service = service,
                    date = date,
                    day = day,
                    poster = row.string("poster_url"),
                    plot = null,
                    season = row.int("season_number")
                )
            )
        }
        return premieres
    }

    private fun details(key: String, id: Int): Pair<String?, String?> {
        val body = get(key, "https://api.watchmode.com/v1/title/$id/details/")
        val row = JsonParser.parseString(body).asJsonObject
        return row.string("plot_overview") to (row.string("poster") ?: row.string("posterMedium"))
    }

    private fun get(key: String, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", key)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                throw WatchmodeException(WatchmodeException.Kind.REJECTED)
            }
            if (!response.isSuccessful) throw WatchmodeException(WatchmodeException.Kind.FAILED)
            return text
        }
    }

    private data class WeekHold(
        val path: String,
        val modified: Long,
        val week: String,
        val fetchedAt: Long,
        val rows: List<Premiere>
    )

    @Volatile
    private var heldWeek: WeekHold? = null

    private fun weekHold(file: File, week: String): WeekHold? {
        if (!file.exists()) return null
        val modified = file.lastModified()
        val current = heldWeek
        if (current != null && current.path == file.absolutePath && current.modified == modified && current.week == week) {
            return current
        }
        val parsed = runCatching { parseWeek(file, week) }.getOrNull() ?: return null
        val hold = WeekHold(file.absolutePath, file.lastModified(), week, parsed.first, parsed.second)
        heldWeek = hold
        return hold
    }

    private fun parseWeek(file: File, week: String): Pair<Long, List<Premiere>>? {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        if (root.string("week") != week) return null
        val fetchedAt = root.get("fetchedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        val rows = root.getAsJsonArray("premieres") ?: return fetchedAt to emptyList()
        val premieres = rows.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val row = element.asJsonObject
            Premiere(
                id = row.int("id") ?: 0,
                title = row.string("title") ?: return@mapNotNull null,
                type = row.string("type").orEmpty(),
                sourceId = row.int("sourceId") ?: return@mapNotNull null,
                service = row.string("service") ?: return@mapNotNull null,
                date = row.string("date") ?: return@mapNotNull null,
                day = row.string("day") ?: return@mapNotNull null,
                poster = row.string("poster"),
                plot = row.string("plot"),
                season = row.int("season")
            )
        }
        return fetchedAt to premieres
    }

    private fun readCache(file: File, week: String): List<Premiere>? = weekHold(file, week)?.rows

    private fun updateCachedPlot(file: File, id: Int, plot: String, poster: String?) {
        if (!file.exists()) return
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val rows = root.getAsJsonArray("premieres") ?: return
        var changed = false
        for (element in rows) {
            if (!element.isJsonObject) continue
            val row = element.asJsonObject
            if (row.int("id") != id) continue
            if (row.string("plot").isNullOrBlank()) {
                row.addProperty("plot", plot)
                changed = true
            }
            if (row.string("poster").isNullOrBlank() && !poster.isNullOrBlank()) {
                row.addProperty("poster", poster)
                changed = true
            }
        }
        if (!changed) return
        file.writeText(root.toString())
        heldWeek = null
    }

    private fun writeCache(file: File, week: String, premieres: List<Premiere>) {
        val rows = JsonArray()
        for (item in premieres) {
            val row = JsonObject()
            row.addProperty("id", item.id)
            row.addProperty("title", item.title)
            row.addProperty("type", item.type)
            row.addProperty("sourceId", item.sourceId)
            row.addProperty("service", item.service)
            row.addProperty("date", item.date)
            row.addProperty("day", item.day)
            if (item.poster != null) row.addProperty("poster", item.poster)
            if (item.plot != null) row.addProperty("plot", item.plot)
            if (item.season != null) row.addProperty("season", item.season)
            rows.add(row)
        }
        val root = JsonObject()
        val fetchedAt = System.currentTimeMillis()
        root.addProperty("week", week)
        root.addProperty("fetchedAt", fetchedAt)
        root.add("premieres", rows)
        file.writeText(root.toString())
        heldWeek = WeekHold(file.absolutePath, file.lastModified(), week, fetchedAt, premieres)
    }

    private fun cacheAge(file: File): Long = weekHold(file, weekKey())?.fetchedAt ?: 0L

    private fun addPrefix(prefixes: MutableList<String>, seen: MutableSet<String>, value: String) {
        val trimmed = value.trim()
        if (trimmed.length < 3) return
        if (!seen.add(trimmed.lowercase())) return
        prefixes.add(trimmed)
    }

    private fun foldTitle(raw: String): String =
        NameImages.strip(raw).lowercase().replace(FOLD, " ").trim()

    private fun JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        val primitive = value.asJsonPrimitive
        return when {
            primitive.isNumber -> primitive.asInt
            primitive.isString -> primitive.asString.toIntOrNull()
            else -> null
        }
    }

    data class Premiere(
        val id: Int,
        val title: String,
        val type: String,
        val sourceId: Int,
        val service: String,
        val date: String,
        val day: String,
        val poster: String?,
        val plot: String?,
        val season: Int?
    )

    private val FOLD = Regex("[^a-z0-9]+")
    private val YEAR = Regex("\\d{4}.*")
    private val SEASON_TOKEN = Regex("s\\d{1,2}.*")
    private const val DAY_MS = 20L * 60L * 60L * 1000L
    private const val MAX_PREFIXES = 96
}

class WatchmodeException(val kind: Kind, cause: Throwable? = null) : Exception(cause) {
    enum class Kind { MISSING_KEY, REJECTED, FAILED }
}
