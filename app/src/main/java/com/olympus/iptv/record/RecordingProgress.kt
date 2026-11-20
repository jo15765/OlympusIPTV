package com.olympus.iptv.record

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Resume time and a still frame for a finished capture. Stored next to the app files, not in the catalog. */
object RecordingProgress {
    private const val POSITIONS = "recording-positions.json"

    fun position(context: Context, id: String): Long {
        if (id.isBlank()) return 0L
        return runCatching {
            val file = File(context.applicationContext.filesDir, POSITIONS)
            if (!file.exists()) return 0L
            JSONObject(file.readText()).optLong(id, 0L)
        }.getOrDefault(0L)
    }

    fun remember(context: Context, id: String, positionMs: Long) {
        if (id.isBlank() || positionMs < 0L) return
        runCatching {
            val file = File(context.applicationContext.filesDir, POSITIONS)
            val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            root.put(id, positionMs)
            file.writeText(root.toString())
        }
    }

    fun forget(context: Context, id: String) {
        if (id.isBlank()) return
        runCatching {
            val file = File(context.applicationContext.filesDir, POSITIONS)
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            root.remove(id)
            file.writeText(root.toString())
        }
        thumbFile(context, id).delete()
    }

    fun thumbFile(context: Context, id: String): File =
        File(File(context.applicationContext.cacheDir, "recording-thumbs"), "$id.jpg")

    /** One frame a second in, written once. MPEG-TS often has no frame the retriever can read. */
    fun ensureThumb(context: Context, id: String, media: File): File? {
        if (id.isBlank() || !media.exists() || media.length() <= 0L) return null
        val dest = thumbFile(context, id)
        if (dest.exists() && dest.length() > 0L) return dest
        val frame = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(media.absolutePath)
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull() ?: return null
        return runCatching {
            dest.parentFile?.mkdirs()
            val small = Bitmap.createScaledBitmap(
                frame,
                160,
                (160f * frame.height / frame.width.coerceAtLeast(1)).toInt().coerceAtLeast(1),
                true
            )
            FileOutputStream(dest).use { out ->
                small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            if (small !== frame) small.recycle()
            frame.recycle()
            dest
        }.getOrNull()
    }
}
