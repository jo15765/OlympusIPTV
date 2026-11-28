package com.olympus.iptv.ui

import com.olympus.iptv.ui.browse.BrowseActivity

object FormFactorBehavior {
    fun isTvBuild(): Boolean = true

    fun attachFoldDisplayWatcher(activity: BrowseActivity, onWatch: () -> Unit) {
        // Set-top boxes never fold or unfold; skip DisplayListener & layout listener overhead.
    }
}
