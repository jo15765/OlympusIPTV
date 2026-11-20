package com.olympus.iptv.ui.browse

import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.ui.DialogChrome

/** Gold A–Z / # grid for lists too long to scroll one row at a time. */
object LetterJump {
    fun show(activity: BrowseActivity, channels: List<Channel>, onJump: (Int) -> Unit) {
        val letters = ('A'..'Z').map { it.toString() } + "#"
        val density = activity.resources.displayMetrics.density
        val grid = GridLayout(activity).apply {
            columnCount = 7
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }
        lateinit var dialog: AlertDialog
        letters.forEachIndexed { index, letter ->
            val cell = TextView(activity).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(0xFFE0B15A.toInt())
                background = activity.getDrawable(R.drawable.bg_search_pill)
                isFocusable = true
                layoutParams = GridLayout.LayoutParams().apply {
                    width = (44 * density).toInt()
                    height = (40 * density).toInt()
                    setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                }
                setOnClickListener {
                    val at = indexOf(channels, letter)
                    dialog.dismiss()
                    if (at >= 0) onJump(at)
                }
            }
            grid.addView(cell)
            if (index == 0) cell.post { cell.requestFocus() }
        }
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6141210"))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.letter_jump_title)
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), 0)
            })
            addView(grid)
        }
        dialog = AlertDialog.Builder(activity).setView(wrap).create()
        DialogChrome.dress(dialog)
        dialog.show()
        dialog.window?.setLayout((380 * density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun indexOf(channels: List<Channel>, letter: String): Int = channels.indexOfFirst { channel ->
        val mark = channel.name.trim().firstOrNull()?.uppercaseChar()
        if (letter == "#") mark == null || !mark.isLetter() else mark.toString() == letter
    }
}
