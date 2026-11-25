package com.olympus.iptv.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout

object LiveBars {
    fun show(group: LinearLayout?, playing: Boolean) {
        if (group == null) return
        val running = group.getTag(group.id) as? List<*>
        if (!playing) {
            running?.forEach { (it as? ObjectAnimator)?.cancel() }
            group.setTag(group.id, null)
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE
        if (!running.isNullOrEmpty()) return
        val animators = ArrayList<ObjectAnimator>(group.childCount)
        for (index in 0 until group.childCount) {
            val bar = group.getChildAt(index)
            val dip = 0.35f + (index * 0.12f)
            val anim = ObjectAnimator.ofPropertyValuesHolder(
                bar,
                PropertyValuesHolder.ofFloat(View.SCALE_Y, dip, 1f)
            ).apply {
                duration = 420L + index * 90L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = LinearInterpolator()
                start()
            }
            animators.add(anim)
        }
        group.setTag(group.id, animators)
    }
}
