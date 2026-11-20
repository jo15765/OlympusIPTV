package com.olympus.iptv.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.SystemClock
import android.view.View

/**
 * Samples artwork for a muted accent and paints a soft gradient behind a hero.
 * A handful of pixels keeps this out of the APK as a library.
 */
object BackdropGlow {
    const val THUMB_W = 64
    const val THUMB_H = 36
    @Volatile var motion: Boolean = true
    @Volatile private var quietUntil = 0L

    /** Called while a low-RAM stick is repeating Up/Down so the glow paints in place. */
    fun noteScroll() {
        quietUntil = SystemClock.uptimeMillis() + 450L
    }

    fun apply(bitmap: Bitmap, target: View) {
        if (bitmap.width < 2 || bitmap.height < 2) return
        val tiny = if (bitmap.width == THUMB_W && bitmap.height == THUMB_H) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, THUMB_W, THUMB_H, true)
        }
        val accent = try {
            sample(tiny)
        } finally {
            if (tiny !== bitmap && !tiny.isRecycled) tiny.recycle()
        } ?: return
        val dark = Color.rgb(
            (Color.red(accent) * 0.28f).toInt(),
            (Color.green(accent) * 0.28f).toInt(),
            (Color.blue(accent) * 0.28f).toInt()
        )
        val next = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(accent, dark)
        )
        val previous = target.background
        val animate = motion && SystemClock.uptimeMillis() >= quietUntil && previous != null
        if (!animate) {
            target.background = next
            return
        }
        val cross = TransitionDrawable(arrayOf(previous, next))
        cross.isCrossFadeEnabled = true
        target.background = cross
        cross.startTransition(280)
    }

    private fun sample(bitmap: Bitmap): Int? {
        if (bitmap.width < 2 || bitmap.height < 2) return null
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        val stepX = (bitmap.width / 6).coerceAtLeast(1)
        val stepY = (bitmap.height / 6).coerceAtLeast(1)
        var y = stepY
        while (y < bitmap.height) {
            var x = stepX
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max in 40..220 && max - min > 18) {
                    red += r
                    green += g
                    blue += b
                    count += 1
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return null
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }
}
