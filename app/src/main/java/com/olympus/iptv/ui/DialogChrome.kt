package com.olympus.iptv.ui

import android.app.Dialog
import android.view.ViewGroup
import com.olympus.iptv.R

/** Dark slate card and the same focus scale used on the guide. */
object DialogChrome {
    fun dress(dialog: Dialog) {
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_login_card)
        dialog.setOnShowListener {
            val root = dialog.window?.decorView as? ViewGroup ?: return@setOnShowListener
            dressTree(root)
        }
    }

    private fun dressTree(group: ViewGroup) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.isFocusable && child.isClickable) FocusMotion.bind(child)
            if (child is ViewGroup) dressTree(child)
        }
    }
}
