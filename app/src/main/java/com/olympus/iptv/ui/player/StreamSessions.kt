package com.olympus.iptv.ui.player

import java.util.concurrent.ConcurrentHashMap

/** Streams this TV currently has open. Cleared when playback stops. */
object StreamSessions {
    data class Row(val id: String, val name: String, val role: String)

    private val open = ConcurrentHashMap<String, Row>()

    fun set(id: String, name: String, role: String) {
        if (id.isBlank()) return
        open[id] = Row(id, name.ifBlank { id }, role)
    }

    fun remove(id: String) {
        open.remove(id)
    }

    fun clear() {
        open.clear()
    }

    fun list(): List<Row> = open.values.sortedBy { it.name }
}
