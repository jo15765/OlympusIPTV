package com.olympus.iptv.playlist

import com.olympus.iptv.data.LineupConfig

object GroupLock {
    private val unlockedUntil = HashMap<String, Long>()

    fun code(digits: String): String {
        var hash = 2166136261L
        for (ch in digits) {
            hash = hash xor ch.code.toLong()
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return hash.toString(16)
    }

    fun locked(config: LineupConfig, groupId: String): Boolean =
        !config.groupPins.orEmpty()[groupId].isNullOrBlank() && !isUnlocked(groupId)

    fun accepts(config: LineupConfig, groupId: String, digits: String): Boolean {
        val saved = config.groupPins.orEmpty()[groupId]
        if (saved.isNullOrBlank()) return true
        return digits.length == 4 && digits.all { it.isDigit() } && code(digits) == saved
    }

    fun unlock(groupId: String, timeoutMinutes: Int) {
        if (groupId.isBlank()) return
        val ttl = timeoutMinutes.coerceAtLeast(1) * 60_000L
        unlockedUntil[groupId] = System.currentTimeMillis() + ttl
    }

    fun lock(groupId: String) {
        unlockedUntil.remove(groupId)
    }

    fun purgeExpired() {
        val now = System.currentTimeMillis()
        unlockedUntil.entries.removeAll { it.value <= now }
    }

    private fun isUnlocked(groupId: String): Boolean {
        val until = unlockedUntil[groupId] ?: return false
        if (until <= System.currentTimeMillis()) {
            unlockedUntil.remove(groupId)
            return false
        }
        return true
    }
}
