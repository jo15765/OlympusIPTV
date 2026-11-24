package com.olympus.iptv.remind

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

fun applyCloudReminders(
    context: Context,
    playlistId: String,
    remote: List<com.olympus.iptv.playlist.LineupClient.RemoteReminder>
) {
    val items = remote.map { item ->
        val start = item.startMillis
        val lead = item.leadMinutes
        ShowReminder().also {
            it.id = item.id
            it.playlistId = playlistId
            it.channelId = item.channelId
            it.channelName = item.channelName
            it.channelNumber = item.channelNumber
            it.title = item.title
            it.startMillis = start
            it.leadMinutes = lead
            it.fireAt = (start - lead * 60_000L).coerceAtLeast(0L)
        }
    }
    ReminderStore.replaceAll(context, items)
    ReminderClock.arm(context)
}

internal object ReminderStore {
    private val gson get() = com.olympus.iptv.data.AppJson.gson
    private val type = object : TypeToken<List<ShowReminder>>() {}.type
    private val lock = Any()
    @Volatile
    private var memory: List<ShowReminder>? = null

    fun listed(context: Context): List<ShowReminder> {
        return synchronized(lock) { read(context).sortedBy { it.startMillis ?: 0L } }
    }

    fun retimed(context: Context, id: String, minutes: Int): ShowReminder? {
        synchronized(lock) {
            val items = read(context).toMutableList()
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            val item = items[index]
            val start = item.startMillis ?: return null
            item.leadMinutes = minutes
            item.fireAt = (start - minutes * 60_000L).coerceAtLeast(System.currentTimeMillis() + 500L)
            write(context, items)
            return item
        }
    }

    fun due(context: Context, now: Long = System.currentTimeMillis()): List<ShowReminder> {
        return all(context).filter { (it.fireAt ?: Long.MAX_VALUE) <= now }
            .sortedBy { it.fireAt ?: 0L }
    }

    fun nextFire(context: Context, now: Long = System.currentTimeMillis()): Long? {
        return all(context).mapNotNull { it.fireAt }.filter { it > now }.minOrNull()
    }

    fun save(context: Context, item: ShowReminder) {
        synchronized(lock) {
            val kept = read(context).filter { existing ->
                existing.id != item.id &&
                    !(existing.channelId == item.channelId && existing.startMillis == item.startMillis)
            }
            write(context, kept + item)
        }
    }

    fun remove(context: Context, id: String) {
        synchronized(lock) {
            val current = read(context)
            val next = current.filter { it.id != id }
            if (next.size == current.size) return
            write(context, next)
        }
    }

    fun replaceAll(context: Context, items: List<ShowReminder>) {
        synchronized(lock) { write(context, items) }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            memory = null
            runCatching { file(context).delete() }
        }
    }

    fun snooze(context: Context, id: String, until: Long) {
        synchronized(lock) {
            val items = read(context)
            var changed = false
            val next = items.map { item ->
                if (item.id == id) {
                    changed = true
                    item.also { it.fireAt = until }
                } else item
            }
            if (changed) write(context, next)
        }
    }

    private fun all(context: Context): List<ShowReminder> = synchronized(lock) { read(context) }

    private fun read(context: Context): List<ShowReminder> {
        memory?.let { return it }
        val raw = runCatching { file(context).readText() }.getOrNull()
        if (raw.isNullOrBlank()) {
            memory = emptyList()
            return emptyList()
        }
        val parsed = runCatching { gson.fromJson<List<ShowReminder>>(raw, type) }.getOrNull().orEmpty()
            .filter { !it.id.isNullOrBlank() && (it.fireAt ?: 0L) > 0L }
        memory = parsed
        return parsed
    }

    private fun write(context: Context, items: List<ShowReminder>) {
        memory = items
        file(context).writeText(gson.toJson(items))
    }

    private fun file(context: Context) = File(context.filesDir, "reminders.json")
}
