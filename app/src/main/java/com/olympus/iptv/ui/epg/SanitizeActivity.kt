package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R

class SanitizeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_host)
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentHost, SanitizeFragment.newInstance(playlistId))
                .commit()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentHost) as? SanitizeFragment
        if (fragment?.dispatchKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, SanitizeActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}