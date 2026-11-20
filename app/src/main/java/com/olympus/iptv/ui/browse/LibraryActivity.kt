package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R
import com.olympus.iptv.databinding.ActivityLibraryBinding
import com.olympus.iptv.remind.RemindersFragment
import com.olympus.iptv.ui.FocusMotion

/**
 * Queue, Fresh, and Reminders stay attached and are shown or hidden in place.
 * D-pad Left/Right on the pills switches tabs without starting a new Activity.
 */
class LibraryActivity : AppCompatActivity(), LibraryHost {
    private lateinit var binding: ActivityLibraryBinding
    private val playlistId by lazy { intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty() }
    private var tab = 0

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
        if (savedInstanceState == null) {
            val queue = QueueFragment.newInstance(playlistId)
            val fresh = FreshFragment.newInstance(playlistId)
            val reminders = RemindersFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .add(R.id.libraryHost, queue, TAGS[0])
                .add(R.id.libraryHost, fresh, TAGS[1])
                .hide(fresh)
                .add(R.id.libraryHost, reminders, TAGS[2])
                .hide(reminders)
                .commitNow()
        }
        binding.libraryBack.setOnClickListener { finish() }
        binding.pillQueue.setOnClickListener { showTab(0) }
        binding.pillFresh.setOnClickListener { showTab(1) }
        binding.pillReminders.setOnClickListener { showTab(2) }
        listOf(binding.pillQueue, binding.pillFresh, binding.pillReminders, binding.libraryBack)
            .forEach { FocusMotion.bind(it, enabled = !com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) }
        val start = savedInstanceState?.getInt(STATE_TAB) ?: intent.getIntExtra(EXTRA_TAB, 0)
        showTab(start)
        binding.libraryHost.post {
            if (tab == 1) {
                val focused = (supportFragmentManager.findFragmentByTag(TAGS[1]) as? FreshFragment)
                    ?.focusFirstCard() == true
                if (!focused) focusPill()
            } else {
                focusPill()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    override fun onFreshExit() {
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tab == 1) {
            val fresh = supportFragmentManager.findFragmentByTag(TAGS[1]) as? FreshFragment
            if (fresh?.dispatchKeyEvent(event) == true) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val onChrome = focused === binding.pillQueue || focused === binding.pillFresh ||
                focused === binding.pillReminders || focused === binding.libraryBack
            if (onChrome) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        showTab((tab - 1).floorMod(3))
                        focusPill()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        showTab((tab + 1).floorMod(3))
                        focusPill()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m

    private fun showTab(index: Int) {
        tab = index
        val transaction = supportFragmentManager.beginTransaction()
        TAGS.forEachIndexed { i, tag ->
            val fragment = supportFragmentManager.findFragmentByTag(tag) ?: return@forEachIndexed
            if (i == index) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commitNowAllowingStateLoss()
        paintPills(index)
    }

    private fun focusPill() {
        when (tab) {
            0 -> binding.pillQueue.requestFocus()
            1 -> binding.pillFresh.requestFocus()
            else -> binding.pillReminders.requestFocus()
        }
    }

    private fun paintPills(index: Int) {
        binding.pillQueue.isSelected = index == 0
        binding.pillFresh.isSelected = index == 1
        binding.pillReminders.isSelected = index == 2
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_TAB = "library_tab_start"
        private const val STATE_TAB = "library_tab"
        private val TAGS = arrayOf("library_queue", "library_fresh", "library_reminders")

        fun intent(context: Context, playlistId: String, tab: Int = 0): Intent =
            Intent(context, LibraryActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                .putExtra(EXTRA_TAB, tab)
    }
}
