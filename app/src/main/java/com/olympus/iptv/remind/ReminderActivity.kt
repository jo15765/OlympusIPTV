package com.olympus.iptv.remind

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.databinding.ActivityReminderBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.EpgUtils
import com.olympus.iptv.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReminderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReminderBinding
    private var item: ShowReminder? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        show(intent.getStringExtra(EXTRA_ID))
        binding.remindIgnore.setOnClickListener { ignore() }
        binding.remindSnooze.setOnClickListener { snooze() }
        binding.remindTune.setOnClickListener { tune() }
        binding.remindIgnore.requestFocus()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent.getStringExtra(EXTRA_ID))
    }

    override fun onDestroy() {
        if (active) active = false
        if (!closed) ReminderClock.onClosed(applicationContext)
        closed = true
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            ignore()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun show(id: String?) {
        val found = ReminderStore.due(this).firstOrNull { it.id == id }
            ?: ReminderStore.due(this).firstOrNull()
        if (found == null) {
            finish()
            return
        }
        item = found
        binding.remindLine.text = getString(
            R.string.remind_line,
            found.channelNumber ?: 1,
            found.title.orEmpty().ifBlank { found.channelName.orEmpty() },
            EpgUtils.formatClock(found.startMillis ?: 0L)
        )
    }

    private fun ignore() {
        val id = item?.id
        if (!id.isNullOrBlank()) ReminderStore.remove(this, id)
        leave()
    }

    private fun snooze() {
        val id = item?.id
        if (!id.isNullOrBlank()) {
            ReminderStore.snooze(this, id, System.currentTimeMillis() + 5L * 60L * 1000L)
        }
        leave()
    }

    private fun tune() {
        val reminder = item ?: return
        val playlistId = reminder.playlistId
        val channelId = reminder.channelId
        if (playlistId.isNullOrBlank() || channelId.isNullOrBlank()) {
            ignore()
            return
        }
        lifecycleScope.launch {
            val channel = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).catalogDb.readChannel(playlistId, CatalogDb.LIVE, channelId)
            }
            if (isDestroyed) return@launch
            if (channel == null || channel.streamUrl.isBlank()) {
                Toast.makeText(this@ReminderActivity, R.string.remind_missing, Toast.LENGTH_SHORT).show()
                ignore()
                return@launch
            }
            ReminderStore.remove(this@ReminderActivity, reminder.id.orEmpty())
            val mode = if (channel.categoryId.isNullOrBlank()) PlayerActivity.ZAP_ALL else PlayerActivity.ZAP_GROUP
            startActivity(
                PlayerActivity.intent(
                    this@ReminderActivity,
                    channel = channel,
                    channelMeta = reminder.title.orEmpty(),
                    channelNext = "",
                    startIndex = 0,
                    playlistId = playlistId,
                    zapMode = mode,
                    categoryId = channel.categoryId
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            leave()
        }
    }

    private fun leave() {
        closed = false
        finish()
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
        @Volatile var active = false
    }
}
