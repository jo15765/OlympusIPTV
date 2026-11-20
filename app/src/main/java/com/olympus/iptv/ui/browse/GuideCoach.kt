package com.olympus.iptv.ui.browse

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.olympus.iptv.R
import com.olympus.iptv.ui.DeviceForm

/** Three first-run tips. Dismissing the last one stores a flag and stays quiet. */
object GuideCoach {
    private const val PREFS = "olympus_tips"
    private const val KEY = "coach_done"

    fun maybe(activity: BrowseActivity) {
        if (DeviceForm.isTouchPrimary(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        if (activity.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag<View>("guide_coach") != null) return
        val steps = listOf(
            R.string.coach_1,
            R.string.coach_2,
            R.string.coach_3
        )
        show(activity, steps, 0) {
            prefs.edit().putBoolean(KEY, true).apply()
        }
    }

    private fun show(activity: BrowseActivity, steps: List<Int>, index: Int, onDone: () -> Unit) {
        val host = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        host.findViewWithTag<View>("guide_coach")?.let { host.removeView(it) }
        val density = activity.resources.displayMetrics.density
        val card = LinearLayout(activity).apply {
            tag = "guide_coach"
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_login_card)
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt())
        }
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.coach_count, index + 1, steps.size)
            setTextColor(0xFFE0B15A.toInt())
            textSize = 13f
        })
        card.addView(TextView(activity).apply {
            text = activity.getString(steps[index])
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 16f
            setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
        })
        card.addView(TextView(activity).apply {
            text = activity.getString(if (index == steps.lastIndex) R.string.coach_done else R.string.coach_next)
            setTextColor(activity.getColor(R.color.bg))
            gravity = Gravity.CENTER
            textSize = 15f
            setBackgroundResource(R.drawable.bg_button)
            isFocusable = true
            isClickable = true
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            setOnClickListener {
                host.removeView(card)
                if (index == steps.lastIndex) onDone() else show(activity, steps, index + 1, onDone)
            }
            post { requestFocus() }
        })
        val params = FrameLayout.LayoutParams(
            (420 * density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (36 * density).toInt()
        }
        host.addView(card, params)
    }
}
