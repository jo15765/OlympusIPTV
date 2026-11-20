package com.olympus.iptv.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** One translucent action list for a channel, program, or poster. */
object GuideActionSheet {
    fun show(context: Context, title: String, labels: List<String>, onPick: (Int) -> Unit) {
        if (labels.isEmpty()) return
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        DialogChrome.dress(dialog)
        dialog.show()
    }
}
