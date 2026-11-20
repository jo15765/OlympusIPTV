package com.olympus.iptv.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.olympus.iptv.R

/** Translucent D-pad quick-tip footer that fades after a few seconds. */
object RemoteTip {
    private const val SHOW_MS = 4_000L

    fun show(activity: Activity, message: CharSequence, root: ViewGroup? = null) {
        if (DeviceForm.isTouchPrimary(activity)) return
        val host = root ?: (activity.findViewById(android.R.id.content) as? ViewGroup) ?: return
        val existing = host.findViewWithTag<View>("remote_tip")
        existing?.let { host.removeView(it) }
        val density = activity.resources.displayMetrics.density
        val tip = TextView(activity).apply {
            tag = "remote_tip"
            text = message
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(
                (18 * density).toInt(),
                (10 * density).toInt(),
                (18 * density).toInt(),
                (10 * density).toInt()
            )
            alpha = 0.92f
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = (24 * density).toInt()
            rightMargin = (24 * density).toInt()
            bottomMargin = (18 * density).toInt()
        }
        host.addView(tip, params)
        tip.animate().alpha(0.92f).setDuration(180).start()
        Handler(Looper.getMainLooper()).postDelayed({
            if (tip.parent != null) {
                tip.animate().alpha(0f).setDuration(320).withEndAction {
                    (tip.parent as? ViewGroup)?.removeView(tip)
                }.start()
            }
        }, SHOW_MS)
    }
}
