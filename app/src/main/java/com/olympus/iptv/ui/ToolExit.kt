package com.olympus.iptv.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.olympus.iptv.R
import com.olympus.iptv.ui.browse.ToolHost

fun Fragment.exitToolHost() {
    val act = requireActivity()
    val fm = act.supportFragmentManager
    when {
        fm.backStackEntryCount > 0 -> fm.popBackStack()
        act is ToolHost && act.isToolVisible() -> act.hideTool()
        hostedInFragmentHostActivity() -> act.finish()
        else -> act.onBackPressedDispatcher.onBackPressed()
    }
}

private fun Fragment.hostedInFragmentHostActivity(): Boolean {
    val act = activity as? AppCompatActivity ?: return false
    return act.supportFragmentManager.findFragmentById(R.id.fragmentHost) === this
}

fun Fragment.viewActive(): Boolean = view != null && isAdded