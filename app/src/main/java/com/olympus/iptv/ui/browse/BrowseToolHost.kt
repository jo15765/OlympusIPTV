package com.olympus.iptv.ui.browse

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.olympus.iptv.R
import java.lang.ref.WeakReference

class BrowseToolHost(
    activity: FragmentActivity,
    overlay: View,
    private val hostId: Int = R.id.toolHost,
) {
    private val activityRef = WeakReference(activity)
    private val overlayRef = WeakReference(overlay)
    private val fm get() = activityRef.get()?.supportFragmentManager

    fun release() {
        activityRef.clear()
        overlayRef.clear()
    }

    fun showTool(fragment: Fragment) {
        val overlay = overlayRef.get() ?: return
        val fm = fm ?: return
        overlay.visibility = View.VISIBLE
        val tag = fragment::class.java.simpleName
        fm.beginTransaction()
            .replace(hostId, fragment, tag)
            .addToBackStack(tag)
            .commit()
    }

    fun hideTool() {
        val fm = fm ?: return
        val overlay = overlayRef.get() ?: return
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

    fun isToolVisible(): Boolean = overlayRef.get()?.visibility == View.VISIBLE

    private fun syncOverlay() {
        val fm = fm ?: return
        val overlay = overlayRef.get() ?: return
        if (fm.findFragmentById(hostId) == null) {
            overlay.visibility = View.GONE
        }
    }
}