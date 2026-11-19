package com.olympus.iptv.remind

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.databinding.ActivityRemindersBinding
import com.olympus.iptv.playlist.EpgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemindersActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRemindersBinding
    private val leads = intArrayOf(15, 30, 45, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemindersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.remindBack.setOnClickListener { finish() }
        binding.quietHours.setOnClickListener { toggleQuietHours() }
        paintQuietHours()
        render()
        binding.remindBack.requestFocus()
    }

    private fun paintQuietHours() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val enabled = withContext(Dispatchers.IO) { store.quietHours() }
            val start = withContext(Dispatchers.IO) { store.quietStartMinute() }
            val end = withContext(Dispatchers.IO) { store.quietEndMinute() }
            binding.quietHours.text = getString(
                if (enabled) R.string.quiet_hours_on else R.string.quiet_hours_off
            )
            binding.quietHoursHint.text = getString(
                R.string.quiet_hours_hint,
                clockLabel(start),
                clockLabel(end)
            )
        }
    }

    private fun toggleQuietHours() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val enabled = withContext(Dispatchers.IO) { store.quietHours() }
            withContext(Dispatchers.IO) {
                store.saveQuietHours(
                    !enabled,
                    PlaylistStore.DEFAULT_QUIET_START,
                    PlaylistStore.DEFAULT_QUIET_END
                )
            }
            ReminderClock.arm(this@RemindersActivity)
            paintQuietHours()
        }
    }

    private fun clockLabel(minuteOfDay: Int): String {
        val hour = (minuteOfDay / 60) % 24
        val minute = minuteOfDay % 60
        return String.format("%d:%02d", hour, minute)
    }

    private fun render() {
        val cards = binding.remindCards
        cards.removeAllViews()
        val items = ReminderStore.listed(this)
        binding.remindEmpty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val density = resources.displayMetrics.density
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_panel)
                setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (16 * density).toInt())
            }
            card.addView(text(item.title.orEmpty().ifBlank { item.channelName.orEmpty() }, 20f, R.color.text_primary, true))
            card.addView(text(
                getString(
                    R.string.remind_line,
                    item.channelNumber ?: 1,
                    item.channelName.orEmpty(),
                    EpgUtils.formatClock(item.startMillis ?: 0L)
                ),
                15f,
                R.color.text_secondary,
                false
            ))
            val lead = item.leadMinutes
            if (lead != null) card.addView(text(getString(R.string.remind_lead, lead), 14f, R.color.accent, false))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(action(getString(R.string.remind_edit)) { edit(item) })
            row.addView(action(getString(R.string.remind_delete)) {
                val id = item.id ?: return@action
                ReminderStore.remove(this, id)
                ReminderClock.arm(this)
                render()
            })
            card.addView(row)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = (12 * density).toInt()
            cards.addView(card, params)
        }
    }

    private fun edit(item: ShowReminder) {
        val labels = leads.map { getString(R.string.remind_before, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(item.title.orEmpty())
            .setItems(labels) { _, which ->
                val id = item.id ?: return@setItems
                ReminderStore.retimed(this, id, leads[which])
                ReminderClock.arm(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(getColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
        }
    }

    private fun action(label: String, onPick: () -> Unit): Button {
        val gap = (8 * resources.displayMetrics.density).toInt()
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(getColor(R.color.bg))
            background = getDrawable(R.drawable.bg_button)
            setOnClickListener { onPick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (48 * resources.displayMetrics.density).toInt()
            ).apply { marginEnd = gap; topMargin = gap }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, RemindersActivity::class.java)
    }
}
