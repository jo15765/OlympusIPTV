package com.olympus.iptv.data

/** Removes emoji and other picture characters from a channel or group name. */
object NameImages {
    fun strip(value: String): String {
        if (value.isEmpty()) return value
        val kept = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            if (!image(point)) kept.appendCodePoint(point)
            index += Character.charCount(point)
        }
        val cleaned = kept.toString().replace(SPACE, " ").trim()
        return cleaned.ifBlank { value.trim() }
    }

    private fun image(point: Int): Boolean {
        if (point == 0x200D || point == 0xFE0F || point == 0xFE0E || point == 0x20E3) return true
        if (point in 0x2600..0x27BF) return true
        if (point in 0x1F000..0x1FAFF) return true
        if (point in 0xE000..0xF8FF) return true
        if (point in 0xFE00..0xFE0F) return true
        val type = Character.getType(point)
        return type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt()
    }

    private val SPACE = Regex("\\s{2,}")
}
