package com.olympus.iptv.ui

import android.content.Context
import android.graphics.Bitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision

fun ImageRequest.Builder.channelLogo(sizePx: Int): ImageRequest.Builder =
    size(sizePx)
        .precision(Precision.INEXACT)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .crossfade(false)
        .allowHardware(true)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)

fun prefetchLogos(context: Context, urls: List<String>, sizePx: Int) {
    if (urls.isEmpty() || sizePx <= 0) return
    val loader = context.imageLoader
    val memory = loader.memoryCache
    val seen = HashSet<String>(urls.size)
    for (url in urls) {
        if (url.isBlank() || !seen.add(url)) continue
        val request = ImageRequest.Builder(context)
            .data(url)
            .channelLogo(sizePx)
            .build()
        val memoryKey = request.memoryCacheKey
        if (memoryKey != null && memory?.get(memoryKey) != null) continue
        // Coil checks disk cache asynchronously on its IO dispatcher; avoid blocking the main thread.
        loader.enqueue(request)
    }
}
