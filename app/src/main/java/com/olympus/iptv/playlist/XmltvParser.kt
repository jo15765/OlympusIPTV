package com.olympus.iptv.playlist

import android.util.Xml
import com.olympus.iptv.data.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object XmltvParser {
    const val PAST_MS = 2L * 60L * 60L * 1000L
    const val FUTURE_MS = 48L * 60L * 60L * 1000L

    fun window(now: Long = System.currentTimeMillis()): Pair<Long, Long> =
        (now - PAST_MS) to (now + FUTURE_MS)

    fun keep(start: Long?, stop: Long?, windowStart: Long, windowEnd: Long): Boolean {
        if (start == null || stop == null) return false
        if (stop <= windowStart) return false
        if (start >= windowEnd) return false
        return true
    }

    fun parse(
        input: InputStream,
        windowStart: Long = window().first,
        windowEnd: Long = window().second
    ): Map<String, List<EpgProgram>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val result = linkedMapOf<String, MutableList<EpgProgram>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("programme", ignoreCase = true)) {
                val channelId = parser.getAttributeValue(null, "channel").orEmpty()
                val start = EpgUtils.parseTimeToMillis(parser.getAttributeValue(null, "start"))
                val stop = EpgUtils.parseTimeToMillis(parser.getAttributeValue(null, "stop"))
                if (!keep(start, stop, windowStart, windowEnd)) {
                    skip(parser)
                    event = parser.next()
                    continue
                }
                var title = ""
                var description: String? = null

                var inner = parser.next()
                while (!(inner == XmlPullParser.END_TAG && parser.name.equals("programme", ignoreCase = true))) {
                    if (inner == XmlPullParser.START_TAG) {
                        when (parser.name.lowercase()) {
                            "title" -> title = parser.nextText().orEmpty()
                            "desc" -> description = parser.nextText()
                            else -> skip(parser)
                        }
                    }
                    inner = parser.next()
                }

                if (channelId.isNotBlank() && title.isNotBlank() && start != null && stop != null) {
                    result.getOrPut(channelId) { mutableListOf() }.add(
                        EpgProgram(
                            title = title.trim(),
                            startMillis = start,
                            endMillis = stop,
                            description = description?.trim()
                        )
                    )
                }
            }
            event = parser.next()
        }
        return result.mapValues { (_, list) -> list.sortedBy { it.startMillis } }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }
}
