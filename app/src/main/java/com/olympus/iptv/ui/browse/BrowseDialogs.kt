package com.olympus.iptv.ui.browse

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.olympus.iptv.R
import com.olympus.iptv.ui.Haptics

internal fun BrowseActivity.confirmRemovePlaylist() {
    AlertDialog.Builder(this)
        .setTitle(R.string.device_remove_playlist_title)
        .setMessage(R.string.device_remove_playlist_message)
        .setPositiveButton(R.string.device_remove_playlist_confirm) { _, _ -> removePlaylistAndRestart() }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun BrowseActivity.showPhoneRowSheet(
    title: String,
    labels: List<String>,
    actions: List<Int>,
    runAction: (Int) -> Unit
) {
    val sheet = BottomSheetDialog(this)
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            (20 * resources.displayMetrics.density).toInt(),
            (16 * resources.displayMetrics.density).toInt(),
            (20 * resources.displayMetrics.density).toInt(),
            (24 * resources.displayMetrics.density).toInt()
        )
        setBackgroundColor(getColor(R.color.bg_elevated))
    }
    column.addView(TextView(this).apply {
        text = title
        setTextColor(getColor(R.color.text_primary))
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
    })
    labels.forEachIndexed { index, label ->
        column.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            minHeight = (52 * resources.displayMetrics.density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_nav)
            setPadding(
                (14 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt()
            )
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = (6 * resources.displayMetrics.density).toInt()
            layoutParams = params
            setOnClickListener {
                Haptics.tick(this)
                sheet.dismiss()
                runAction(actions[index])
            }
        })
    }
    sheet.setContentView(column)
    sheet.show()
}
