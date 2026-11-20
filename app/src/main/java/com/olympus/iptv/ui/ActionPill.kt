package com.olympus.iptv.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.olympus.iptv.R

/** Bottom confirmation that stays for a few seconds and can undo the last tap. */
object ActionPill {
    private const val TAG = "action_pill"
    private const val SHOW_MS = 3_000L
    private var generation = 0

    fun show(activity: Activity, message: String, onUndo: () -> Unit) {
        if (activity.isFinishing) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val density = activity.resources.displayMetrics.density
        var bar = root.findViewWithTag<LinearLayout>(TAG)
        if (bar == null) {
            bar = LinearLayout(activity).apply {
                tag = TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_panel)
                val pad = (14 * density).toInt()
                setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())
            }
            val label = TextView(activity).apply {
                tag = "action_pill_label"
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 15f
            }
            val undo = TextView(activity).apply {
                tag = "action_pill_undo"
                setTextColor(activity.getColor(R.color.accent))
                textSize = 15f
                setPadding((16 * density).toInt(), 0, 0, 0)
                text = activity.getString(R.string.action_undo)
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_nav)
            }
            bar.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(undo)
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            val margin = (18 * density).toInt()
            params.setMargins(margin, 0, margin, margin)
            root.addView(bar, params)
        }
        bar.findViewWithTag<TextView>("action_pill_label").text = message
        val undo = bar.findViewWithTag<TextView>("action_pill_undo")
        val previous = activity.currentFocus
        undo.setOnClickListener {
            dismiss(root)
            onUndo()
            previous?.requestFocus()
        }
        bar.visibility = View.VISIBLE
        bar.bringToFront()
        undo.requestFocus()
        val stamp = ++generation
        bar.postDelayed({
            if (stamp == generation && bar.visibility == View.VISIBLE) {
                dismiss(root)
                if (undo.hasFocus()) previous?.requestFocus()
            }
        }, SHOW_MS)
    }

    private fun dismiss(root: ViewGroup) {
        root.findViewWithTag<View>(TAG)?.visibility = View.GONE
    }
}
