package com.olympus.iptv.ui

import com.olympus.iptv.ui.browse.BrowseActivity

object FormFactorBehavior {
    fun isTvBuild(): Boolean = false

    fun attachFoldDisplayWatcher(activity: BrowseActivity, onWatch: () -> Unit) {
        onWatch()
    }
}
