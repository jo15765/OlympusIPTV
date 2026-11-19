package com.olympus.iptv.playlist

import com.olympus.iptv.data.Channel
import java.text.Normalizer
import java.util.Locale

data class CountryOption(
    val id: String,
    val label: String,
    val count: Int
)

object CountryIndex {
    const val OTHER_ID = "__other__"

    private data class Origin(val id: String, val label: String)

    private val OTHER = Origin(OTHER_ID, "Other")
    private val ENGLISH = Origin("en", "English")
    private val MARKS = Regex("\\p{Mn}+")
    private val SPLITS = Regex("""\s*[|:;/\\\[\]()]+\s*""")

    private val aliases: Map<String, Origin>
    private val phrases: List<Pair<String, Origin>>

    init {
        val entries = listOf(
            origin("us", "United States", "usa", "us", "united states", "united states of america"),
            origin("gb", "United Kingdom", "uk", "gb", "united kingdom", "great britain", "britain"),
            origin("ca", "Canada", "ca", "canada"),
            origin("au", "Australia", "au", "australia"),
            origin("ar", "Argentina", "ar", "argentina"),
            origin("de", "Germany", "de", "germany", "deutschland"),
            origin("it", "Italy", "it", "italy", "italia"),
            origin("fr", "France", "fr", "france"),
            origin("es", "Spain", "es", "spain", "espana", "españa"),
            origin("mx", "Mexico", "mx", "mexico", "méxico"),
            origin("br", "Brazil", "br", "brazil", "brasil"),
            origin("in", "India", "in", "india"),
            origin("cn", "China", "cn", "china"),
            origin("ru", "Russia", "ru", "russia"),
            origin("pl", "Poland", "pl", "poland", "polska"),
            origin("al", "Albania", "al", "albania", "shqiperia"),
            origin("tr", "Turkey", "tr", "turkey", "turkiye", "türkiye"),
            origin("pt", "Portugal", "pt", "portugal"),
            origin("ro", "Romania", "ro", "romania", "românia"),
            origin("vn", "Vietnam", "vn", "vietnam"),
            origin("nl", "Netherlands", "nl", "netherlands", "holland"),
            origin("se", "Sweden", "se", "sweden", "sverige"),
            origin("no", "Norway", "no", "norway", "norge"),
            origin("pe", "Peru", "pe", "peru"),
            origin("ir", "Iran", "ir", "iran"),
            origin("cz", "Czechia", "cz", "czechia", "czech republic"),
            origin("cl", "Chile", "cl", "chile"),
            origin("pk", "Pakistan", "pk", "pakistan"),
            origin("il", "Israel", "il", "israel"),
            origin("hu", "Hungary", "hu", "hungary"),
            origin("dk", "Denmark", "dk", "denmark"),
            origin("ph", "Philippines", "ph", "philippines", "philippine"),
            origin("gr", "Greece", "gr", "greece"),
            origin("mt", "Malta", "mt", "malta"),
            origin("bg", "Bulgaria", "bg", "bulgaria"),
            origin("jp", "Japan", "jp", "japan"),
            origin("ba", "Bosnia and Herzegovina", "ba", "bosnia and herzegovina", "bosnia"),
            origin("co", "Colombia", "co", "colombia"),
            origin("do", "Dominican Republic", "do", "dominican republic", "republica dominicana", "república dominicana"),
            origin("af", "Afghanistan", "af", "afghanistan"),
            origin("am", "Armenia", "am", "armenia"),
            origin("at", "Austria", "at", "austria"),
            origin("az", "Azerbaijan", "az", "azerbaijan"),
            origin("bd", "Bangladesh", "bd", "bangladesh"),
            origin("be", "Belgium", "be", "belgium", "belgique"),
            origin("bo", "Bolivia", "bo", "bolivia"),
            origin("cr", "Costa Rica", "cr", "costa rica"),
            origin("cu", "Cuba", "cu", "cuba"),
            origin("cy", "Cyprus", "cy", "cyprus"),
            origin("ec", "Ecuador", "ec", "ecuador"),
            origin("sv", "El Salvador", "sv", "el salvador"),
            origin("fi", "Finland", "fi", "finland", "suomi"),
            origin("gt", "Guatemala", "gt", "guatemala"),
            origin("hn", "Honduras", "hn", "honduras"),
            origin("id", "Indonesia", "id", "indonesia"),
            origin("ie", "Ireland", "ie", "ireland"),
            origin("kr", "Korea", "kr", "korea", "south korea"),
            origin("mk", "North Macedonia", "mk", "macedonia", "north macedonia"),
            origin("ni", "Nicaragua", "ni", "nicaragua"),
            origin("pa", "Panama", "pa", "panama", "panamá"),
            origin("py", "Paraguay", "py", "paraguay"),
            origin("pr", "Puerto Rico", "pr", "puerto rico"),
            origin("sg", "Singapore", "sg", "singapore"),
            origin("ch", "Switzerland", "ch", "switzerland"),
            origin("tj", "Tajikistan", "tj", "tajikistan"),
            origin("th", "Thailand", "th", "thailand"),
            origin("ua", "Ukraine", "ua", "ukraine"),
            origin("uy", "Uruguay", "uy", "uruguay"),
            origin("nz", "New Zealand", "nz", "new zealand"),
            origin("caribbean", "Caribbean", "caribbean"),
            origin("africa", "Africa", "africa"),
            origin("ex-yu", "Ex-Yu", "ex-yu", "ex yu"),
            origin("intl", "International", "intl", "international"),
            origin("ckb", "Kurdish", "kurdish"),
            origin("in-pk", "India / Pakistan", "india/pakistan", "india / pakistan")
        )
        aliases = entries.flatMap { (origin, keys) -> keys.map { normalize(it) to origin } }.toMap()
        phrases = aliases.entries
            .asSequence()
            .filter { it.key.length > 3 && it.key.contains(' ') }
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
            .toList()
    }

    fun idFor(groupTitle: String?): String? = parse(groupTitle)?.id

    fun directory(): List<CountryOption> = knownCountries

    private val knownCountries: List<CountryOption> by lazy {
        val labels = LinkedHashMap<String, String>()
        for (origin in aliases.values) labels.putIfAbsent(origin.id, origin.label)
        labels.map { (id, label) -> CountryOption(id, label, 0) }
            .sortedBy { it.label.lowercase(Locale.US) }
    }

    fun isForeign(groupTitle: String?, homeCountry: String): Boolean {
        val id = idFor(groupTitle) ?: return false
        return id != homeCountry
    }

    fun tagChannels(channels: List<Channel>): List<Channel> = channels.map(::tag)

    fun describe(groupTitle: String?): Pair<String, String>? {
        val parsed = parse(groupTitle) ?: return null
        return parsed.id to parsed.token
    }

    fun annotate(loaded: LoadedPlaylist): LoadedPlaylist {
        return loaded.copy(
            liveChannels = loaded.liveChannels.map(::tag),
            vodChannels = loaded.vodChannels.map(::tag)
        )
    }

    fun options(channels: List<Channel>): List<CountryOption> {
        val grouped = channels.groupBy { it.countryId ?: OTHER_ID }
        return grouped.map { (id, list) ->
            val label = if (id == OTHER_ID) {
                OTHER.label
            } else {
                list.mapNotNull { it.countryLabel }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: id
            }
            CountryOption(id, label, list.size)
        }.sortedWith(compareBy({ it.id == OTHER_ID }, { it.label.lowercase(Locale.US) }))
    }

    private fun tag(channel: Channel): Channel {
        val parsed = parse(channel.groupTitle) ?: return channel
        return channel.copy(countryId = parsed.id, countryLabel = parsed.token)
    }

    private data class Parsed(val id: String, val token: String)

    private fun parse(groupTitle: String?): Parsed? {
        if (groupTitle.isNullOrBlank()) return null
        val cleaned = stripDecorations(groupTitle)
        if (cleaned.isEmpty()) return null
        val pieces = cleaned.split(SPLITS).map { it.trim() }.filter { it.isNotEmpty() }
        for (piece in pieces) {
            val token = piece.substringBefore(" - ").trim()
            matchToken(token)?.let { return it }
        }
        matchToken(cleaned.substringBefore(" - ").trim())?.let { return it }
        return null
    }

    private fun matchToken(token: String): Parsed? {
        if (token.isBlank()) return null
        val key = normalize(token)
        val origin = when {
            key == "en" || key.startsWith("en✦") -> ENGLISH
            else -> aliases[key] ?: leadingOrigin(token, key)
        } ?: return null
        val shown = if (origin.id == ENGLISH.id) ENGLISH.label else token
        return Parsed(origin.id, shown)
    }

    private fun leadingOrigin(token: String, key: String): Origin? {
        for ((candidate, origin) in phrases) {
            if (key.startsWith("$candidate ")) return origin
        }
        val rawFirst = token.trim().substringBefore(' ').substringBefore('-')
        val keyFirst = normalize(rawFirst)
        if (keyFirst.isBlank() || keyFirst == key) return null
        val code = rawFirst.length in 2..3 &&
            rawFirst.all { it.isLetter() } &&
            rawFirst == rawFirst.uppercase(Locale.US) &&
            rawFirst.lowercase(Locale.US) !in SHORT_WORDS
        val word = rawFirst.length > 3
        if (!code && !word) return null
        return aliases[keyFirst]
    }

    private fun stripDecorations(value: String): String {
        return value.trim().dropWhile { ch ->
            val type = Character.getType(ch)
            type == Character.SURROGATE.toInt() ||
                type == Character.OTHER_SYMBOL.toInt() ||
                ch == '\uFE0F' ||
                ch.isWhitespace()
        }.trim()
    }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        if (isAscii(trimmed)) return trimmed.lowercase(Locale.US)
        return Normalizer.normalize(trimmed, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.US)
            .trim()
    }

    private fun isAscii(value: String): Boolean {
        for (index in value.indices) {
            if (value[index].code > 127) return false
        }
        return true
    }

    private val SHORT_WORDS = setOf("in", "it", "no", "be", "at", "id", "am", "do", "or", "an", "to", "my", "is", "as", "if", "so", "up", "on", "by")

    private fun origin(id: String, label: String, vararg keys: String): Pair<Origin, List<String>> {
        return Origin(id, label) to keys.toList()
    }
}
