package com.olympus.iptv.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

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
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(MS)
            .setInterpolator(ease)
            .start()
        if (focused) {
            view.elevation = 6f * view.resources.displayMetrics.density
        } else {
            view.elevation = 0f
        }
    }
}
