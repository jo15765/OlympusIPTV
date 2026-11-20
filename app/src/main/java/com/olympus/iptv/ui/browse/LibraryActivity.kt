package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R
import com.olympus.iptv.databinding.ActivityLibraryBinding
import com.olympus.iptv.remind.RemindersActivity
import com.olympus.iptv.ui.FocusMotion

class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private val playlistId by lazy { intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty() }
    private var tab = Tab.QUEUE

    private enum class Tab { QUEUE, FRESH, REMINDERS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (playlistId.isBlank()) {
            finish()
            return
        }
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.root,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
        }
        binding.libraryBack.setOnClickListener { finish() }
        binding.pillQueue.setOnClickListener { select(Tab.QUEUE) }
        binding.pillFresh.setOnClickListener { select(Tab.FRESH) }
        binding.pillReminders.setOnClickListener { select(Tab.REMINDERS) }
        binding.libraryOpen.setOnClickListener { openSelected() }
        binding.libraryCard.setOnClickListener { openSelected() }
        listOf(binding.pillQueue, binding.pillFresh, binding.pillReminders, binding.libraryOpen, binding.libraryCard)
            .forEach { FocusMotion.bind(it, enabled = !com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) }
        select(Tab.QUEUE)
        binding.pillQueue.post { binding.pillQueue.requestFocus() }
    }

    private fun select(next: Tab) {
        tab = next
        binding.pillQueue.isSelected = next == Tab.QUEUE
        binding.pillFresh.isSelected = next == Tab.FRESH
        binding.pillReminders.isSelected = next == Tab.REMINDERS
        when (next) {
            Tab.QUEUE -> {
                binding.libraryHeading.setText(R.string.nav_queue)
                binding.libraryHint.setText(R.string.library_queue_hint)
            }
            Tab.FRESH -> {
                binding.libraryHeading.setText(R.string.nav_fresh)
                binding.libraryHint.setText(R.string.library_fresh_hint)
            }
            Tab.REMINDERS -> {
                binding.libraryHeading.setText(R.string.nav_reminders)
                binding.libraryHint.setText(R.string.library_reminders_hint)
            }
        }
    }

    private fun openSelected() {
        val intent = when (tab) {
            Tab.QUEUE -> QueueActivity.intent(this, playlistId)
            Tab.FRESH -> FreshActivity.intent(this, playlistId)
            Tab.REMINDERS -> RemindersActivity.intent(this)
        }
        startActivity(intent)
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, LibraryActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
