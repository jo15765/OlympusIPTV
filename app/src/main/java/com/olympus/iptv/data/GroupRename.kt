package com.olympus.iptv.data

object GroupRename {
    data class Sample(val before: String, val after: String)

    data class Result(
        val names: Map<String, String>,
        val changed: Int,
        val examples: List<Sample>
    )

    fun apply(
        groups: List<Pair<String, String>>,
        current: Map<String, String>,
        find: String,
        replacement: String
    ): Result {
        val needle = find.trim()
        if (needle.isEmpty()) return Result(current, 0, emptyList())
        val next = current.toMutableMap()
        val samples = ArrayList<Sample>()
        var changed = 0
        for ((id, original) in groups) {
            val override = current[id]?.takeIf { it.isNotBlank() }
            val displayed = override ?: original
            val before = when {
                contains(displayed, needle) -> displayed
                contains(original, needle) -> original
                else -> continue
            }
            val after = replaceAll(before, needle, replacement).trim()
            if (after.isEmpty() || after == before) continue
            changed++
            if (after == original) next.remove(id) else next[id] = after
            samples.add(Sample(clip(before), clip(after)))
        }
        return Result(next, changed, spread(samples))
    }

    fun shown(id: String, original: String, config: LineupConfig): String {
        val override = config.groupNames?.get(id)?.takeIf { it.isNotBlank() }
        if (override != null) return override
        val find = config.groupFind?.trim().orEmpty()
        if (find.isEmpty()) return original
        val replaced = replaceAll(original, find, config.groupReplace.orEmpty()).trim()
        return if (replaced.isEmpty() || replaced == original) original else replaced
    }

    private fun contains(name: String, needle: String): Boolean {
        if (name.contains(needle, ignoreCase = true)) return true
        val foldedNeedle = fold(needle).text
        if (foldedNeedle.isEmpty()) return false
        return fold(name).text.contains(foldedNeedle, ignoreCase = true)
    }

    fun replaceAll(name: String, needle: String, replacement: String): String {
        if (needle.isEmpty()) return name
        val foldedNeedle = fold(needle).text
        if (foldedNeedle.isEmpty()) return name
        val out = StringBuilder()
        var index = 0
        while (index < name.length) {
            val rest = name.substring(index)
            val folded = fold(rest)
            val at = folded.text.indexOf(foldedNeedle, ignoreCase = true)
            if (at < 0) {
                out.append(rest)
                break
            }
            val from = folded.start[at]
            val to = folded.end[at + foldedNeedle.length - 1]
            out.append(rest, 0, from)
            out.append(replacement)
            index += to
        }
        return out.toString()
    }

    private class Fold(val text: String, val start: IntArray, val end: IntArray)

    private fun fold(value: String): Fold {
        val text = StringBuilder(value.length)
        val start = ArrayList<Int>(value.length)
        val end = ArrayList<Int>(value.length)
        var index = 0
        var pendingSpace = false
        var spaceAt = 0
        while (index < value.length) {
            val ch = foldChar(value[index])
            if (ch == ' ') {
                if (!pendingSpace && text.isNotEmpty()) {
                    pendingSpace = true
                    spaceAt = index
                }
                index++
                continue
            }
            if (pendingSpace) {
                text.append(' ')
                start.add(spaceAt)
                end.add(index)
                pendingSpace = false
            }
            text.append(ch)
            start.add(index)
            end.add(index + 1)
            index++
        }
        return Fold(text.toString(), start.toIntArray(), end.toIntArray())
    }

    private fun foldChar(ch: Char): Char = when (ch) {
        '\u00A0', '\u2007', '\u202F', '\u2009', '\u200A' -> ' '
        '|', '│', '┃', '┆', '┇', '┊', '┋', 'ǀ', 'ǁ', '∣', '¦', '｜', '❘', '❙', '❚' -> '|'
        else -> ch
    }

    private fun spread(samples: List<Sample>): List<Sample> {
        if (samples.size <= EXAMPLES) return samples
        return List(EXAMPLES) { index -> samples[(index * samples.size) / EXAMPLES] }
    }

    private fun clip(value: String): String =
        if (value.length <= 72) value else value.take(69).trimEnd() + "…"

    private const val EXAMPLES = 4
}
