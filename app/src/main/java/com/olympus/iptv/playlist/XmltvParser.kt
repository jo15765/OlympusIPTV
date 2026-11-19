package com.olympus.iptv.playlist

import android.util.Xml
import com.olympus.iptv.data.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Lightweight XMLTV parser. Keeps programmes overlapping [windowStart, windowEnd].
 */
object XmltvParser {
    fun parse(
        input: InputStream,
        windowStart: Long = System.currentTimeMillis() - 60 * 60 * 1000L,
        windowEnd: Long = System.currentTimeMillis() + 12 * 60 * 60 * 1000L
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

                if (channelId.isNotBlank() && start != null && stop != null &&
                    stop > windowStart && start < windowEnd && title.isNotBlank()
                ) {
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
