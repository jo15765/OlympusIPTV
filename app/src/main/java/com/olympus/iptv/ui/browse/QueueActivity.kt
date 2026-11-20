package com.olympus.iptv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R

class QueueActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_host)
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                findViewById(R.id.fragmentHost),
                top = true,
                bottom = true,
                left = true,
                right = true
            )
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentHost, QueueFragment.newInstance(playlistId))
                .commit()
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = QueueFragment.EXTRA_PLAYLIST_ID

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, QueueActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
