package com.olympus.iptv.data

object ShowNames {
    private val seasonEpisode = Regex("(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b")
    private val cross = Regex("(?i)\\b\\d{1,2}x\\d{1,3}\\b")
    private val worded = Regex("(?i)\\b(season|episode|ep\\.?)\\s*\\d+\\b")
    private val punctuation = Regex("[|:\\-]")
    private val spaces = Regex("\\s+")

    fun key(title: String): String {
        val stripped = worded.replace(
            cross.replace(seasonEpisode.replace(title, " "), " "),
            " "
        )
        return punctuation.replace(stripped, " ").replace(spaces, " ").trim().lowercase()
    }

    fun same(left: String, right: String): Boolean {
        val a = key(left)
        val b = key(right)
        return a.length >= 3 && a == b
    }

    fun episodeHeading(showName: String, season: Int, number: Int, raw: String): String {
        var title = raw.trim()
        title = codeToken.replace(title, " ")
        val show = showName.trim()
        if (show.length >= 3) {
            title = Regex("(?i)(^|[\\s|:–—-])${Regex.escape(show)}(?=\\s|$|[|:–—-])").replace(title) { it.groupValues[1] }
        }
        title = leadingEpisode.replace(title, " ")
        title = spaces.replace(title, " ").trim(' ', '-', ':', '|', '–', '—', '.')
        val code = "S${season.toString().padStart(2, '0')}-E${number.toString().padStart(2, '0')}"
        return if (title.isBlank() || title.equals(show, ignoreCase = true)) code else "$code - $title"
    }

    private val codeToken = Regex("(?i)\\bS\\d{1,2}\\s*[- ]?E\\d{1,3}\\b|\\b\\d{1,2}x\\d{1,3}\\b")
    private val leadingEpisode = Regex("(?i)(?:^|\\s)(?:episode|ep\\.?)\\s*\\d+\\b")
}
