package com.olympus.iptv.playlist

object FtsQuery {
    private val split = Regex("[^\\p{L}\\p{N}]+")

    fun match(text: String): String? {
        val tokens = text.split(split).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            val word = token.lowercase()
            if (word.all { it.isLetterOrDigit() }) "$word*" else "\"$word\"*"
        }
    }

    fun looseTokens(text: String): List<String> {
        return text.lowercase().split(split).filter { token ->
            token.length >= 2 || (token.isNotEmpty() && token.all { it.isDigit() })
        }
    }
}
