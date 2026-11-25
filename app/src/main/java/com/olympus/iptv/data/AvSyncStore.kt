package com.olympus.iptv.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AvSyncStore {
    private const val FILE = "av-sync.json"
    const val STEP_MS = 100
    const val LIMIT_MS = 500

    private val memory = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

    fun warm(context: Context) {
        val app = context.applicationContext
        Thread {
            val read = readFile(app)
            memory.clear()
            memory.putAll(read)
            loaded = true
        }.start()
    }

    fun get(channelId: String): Int {
        if (channelId.isBlank()) return 0
        return memory[channelId] ?: 0
    }

    fun nudge(context: Context, channelId: String, deltaMs: Int): Int {
        if (channelId.isBlank()) return 0
        val next = (get(channelId) + deltaMs).coerceIn(-LIMIT_MS, LIMIT_MS)
        if (next == 0) memory.remove(channelId) else memory[channelId] = next
        val snapshot = HashMap(memory)
        val app = context.applicationContext
        Thread { write(app, snapshot) }.start()
        return next
    }

    fun all(context: Context): Map<String, Int> {
        if (!loaded) {
            val read = readFile(context.applicationContext)
            memory.clear()
            memory.putAll(read)
            loaded = true
        }
        return HashMap(memory)
    }

    fun replace(context: Context, offsets: Map<String, Int>) {
        memory.clear()
        offsets.forEach { (id, ms) ->
            if (id.isNotBlank() && ms != 0) memory[id] = ms.coerceIn(-LIMIT_MS, LIMIT_MS)
        }
        loaded = true
        val snapshot = HashMap(memory)
        val app = context.applicationContext
        Thread { write(app, snapshot) }.start()
    }

    private fun readFile(context: Context): Map<String, Int> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            val out = LinkedHashMap<String, Int>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = root.optInt(key).coerceIn(-LIMIT_MS, LIMIT_MS)
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun write(context: Context, offsets: Map<String, Int>) {
        val root = JSONObject()
        offsets.forEach { (id, ms) -> root.put(id, ms) }
        File(context.filesDir, FILE).writeText(root.toString())
    }
}
