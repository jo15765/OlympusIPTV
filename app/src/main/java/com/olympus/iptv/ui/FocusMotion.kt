package com.olympus.iptv.ui

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object FocusMotion {
    private val ease = DecelerateInterpolator()
    private const val FOCUSED = 1.04f
    private const val NORMAL = 1f
    private const val MS = 120L

    fun bind(view: View, enabled: Boolean = true) {
        if (!enabled) {
            view.animate().cancel()
            view.scaleX = NORMAL
            view.scaleY = NORMAL
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
        }
    }

    fun animate(view: View, focused: Boolean) {
        val scale = if (focused) FOCUSED else NORMAL
        val lively = !DeviceForm.isLowRam(view.context)
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(if (lively) 180L else MS)
            .setInterpolator(if (lively) OvershootInterpolator(1.35f) else ease)
            .start()
        if (focused) {
            view.elevation = 6f * view.resources.displayMetrics.density
            val stroke = android.graphics.drawable.GradientDrawable()
            stroke.setColor(android.graphics.Color.TRANSPARENT)
            stroke.cornerRadius = 12f * view.resources.displayMetrics.density
            stroke.setStroke((2 * view.resources.displayMetrics.density).toInt(), 0xFFE0B15A.toInt())
            view.foreground = stroke
            com.olympus.iptv.ui.browse.GuideFeel.onFocus(view)
        } else {
            view.elevation = 0f
            view.foreground = null
        }
    }
}
