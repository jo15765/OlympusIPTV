package com.olympus.iptv.ui

import android.content.Context

/** Warms the next off-screen posters while a list is idle. */
object ImagePrefetcher {
    const val AHEAD = 12

    fun ahead(urls: List<String?>, firstOffScreen: Int, count: Int = AHEAD): List<String> {
        if (firstOffScreen < 0 || firstOffScreen >= urls.size || count <= 0) return emptyList()
        val end = (firstOffScreen + count).coerceAtMost(urls.size)
        val out = ArrayList<String>(count)
        for (index in firstOffScreen until end) {
            urls[index]?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }

    fun warm(context: Context, urls: List<String>, sizePx: Int) {
        prefetchLogos(context, urls, sizePx)
    }
}
