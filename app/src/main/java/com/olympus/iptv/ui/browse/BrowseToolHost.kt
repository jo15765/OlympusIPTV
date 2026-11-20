package com.olympus.iptv.ui.browse

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.olympus.iptv.R

class BrowseToolHost(
    private val activity: FragmentActivity,
    private val overlay: View,
    private val hostId: Int = R.id.toolHost,
) {
    private val fm get() = activity.supportFragmentManager

    fun showTool(fragment: Fragment) {
        overlay.visibility = View.VISIBLE
        val tag = fragment::class.java.simpleName
        fm.beginTransaction()
            .replace(hostId, fragment, tag)
            .addToBackStack(tag)
            .commit()
    }

    fun hideTool() {
        if (fm.backStackEntryCount > 0) {
            fm.popBackStack()
            overlay.post { syncOverlay() }
            return
        }
        val current = fm.findFragmentById(hostId)
        if (current != null) {
            fm.beginTransaction().remove(current).commitNowAllowingStateLoss()
        }
        overlay.visibility = View.GONE
    }

    fun isToolVisible(): Boolean = overlay.visibility == View.VISIBLE

    private fun syncOverlay() {
        if (fm.findFragmentById(hostId) == null) {
            overlay.visibility = View.GONE
        }
    }
}