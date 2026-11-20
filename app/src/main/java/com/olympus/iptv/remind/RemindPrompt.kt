package com.olympus.iptv.remind

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.EpgProgram
import java.util.UUID

object RemindPrompt {
    private val leads = intArrayOf(15, 30, 45, 60)

    fun show(
        activity: AppCompatActivity,
        playlistId: String,
        channel: Channel,
        number: Int,
        program: EpgProgram
    ) {
        val labels = leads.map { activity.getString(R.string.remind_before, it) }.toTypedArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(program.title)
            .setItems(labels) { _, which ->
                val fire = program.startMillis - leads[which] * 60_000L
                val item = ShowReminder().apply {
                    id = UUID.randomUUID().toString()
                    this.playlistId = playlistId
                    channelId = channel.id
                    channelName = channel.name
                    channelNumber = number.coerceAtLeast(1)
                    title = program.title
                    startMillis = program.startMillis
                    fireAt = fire.coerceAtLeast(System.currentTimeMillis() + 500L)
                    leadMinutes = leads[which]
                }
                ReminderStore.save(activity, item)
                ReminderClock.arm(activity)
                Toast.makeText(activity, R.string.remind_set, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        com.olympus.iptv.ui.DialogChrome.dress(dialog)
        dialog.show()
    }
}
