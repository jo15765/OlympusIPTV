package com.olympus.iptv.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object InsetsPad {
    fun apply(
        view: View,
        top: Boolean = false,
        bottom: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        baseLeft: Int = view.paddingLeft,
        baseTop: Int = view.paddingTop,
        baseRight: Int = view.paddingRight,
        baseBottom: Int = view.paddingBottom
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.updatePadding(
                left = baseLeft + if (left) bars.left else 0,
                top = baseTop + if (top) bars.top else 0,
                right = baseRight + if (right) bars.right else 0,
                bottom = baseBottom + if (bottom) bars.bottom else 0
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun applyMargins(
        view: View,
        top: Boolean = false,
        bottom: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        baseLeft: Int = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0,
        baseTop: Int = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
        baseRight: Int = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin ?: 0,
        baseBottom: Int = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = target.layoutParams as? ViewGroup.MarginLayoutParams ?: return@setOnApplyWindowInsetsListener insets
            params.leftMargin = baseLeft + if (left) bars.left else 0
            params.topMargin = baseTop + if (top) bars.top else 0
            params.rightMargin = baseRight + if (right) bars.right else 0
            params.bottomMargin = baseBottom + if (bottom) bars.bottom else 0
            target.layoutParams = params
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun systemBars(view: View): Insets =
        ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?: Insets.NONE
}
