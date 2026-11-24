package com.olympus.iptv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator

object FocusMotion {
    private val smooth = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val fastEase = DecelerateInterpolator()
    private const val FOCUSED = 1.035f
    private const val NORMAL = 1f
    private const val MS_TV = 140L
    private const val MS_LOW_RAM = 90L

    @Volatile private var cachedDensity = 0f
    @Volatile private var cachedStroke: GradientDrawable? = null

    private fun focusStroke(density: Float): GradientDrawable {
        val existing = cachedStroke
        if (existing != null && cachedDensity == density) return existing
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 12f * density
            setStroke((2f * density).toInt().coerceAtLeast(2), 0xFFF5CA8E.toInt())
            cachedDensity = density
            cachedStroke = this
        }
    }

    fun bind(view: View, enabled: Boolean = true) {
        if (!enabled) {
            view.animate().cancel()
            view.scaleX = NORMAL
            view.scaleY = NORMAL
            view.elevation = 0f
            view.foreground = null
            view.onFocusChangeListener = null
            return
        }
        val previous = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus)
            animate(v, hasFocus)
        }
        if (view.isFocused) animate(view, true) else {
            view.scaleX = NORMAL
            view.scaleY = NORMAL
            view.elevation = 0f
        }
    }

    fun animate(view: View, focused: Boolean) {
        val scale = if (focused) FOCUSED else NORMAL
        val lively = !DeviceForm.isLowRam(view.context)
        val density = view.resources.displayMetrics.density
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(if (lively) MS_TV else MS_LOW_RAM)
            .setInterpolator(if (lively) smooth else fastEase)
            .start()
        if (focused) {
            view.elevation = (if (lively) 8f else 4f) * density
            // Views backed by a StateListDrawable already draw their own radius-matched focus border;
            // only attach the cached foreground ring when a view has no selector border.
            if (view.background !is StateListDrawable) {
                view.foreground = focusStroke(density)
            } else if (view.foreground != null) {
                view.foreground = null
            }
            com.olympus.iptv.ui.browse.GuideFeel.onFocus(view)
        } else {
            view.elevation = 0f
            if (view.foreground != null) view.foreground = null
        }
    }
}
