package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R

class GroupRenameActivity : AppCompatActivity() {
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
                .replace(R.id.fragmentHost, GroupRenameFragment.newInstance(playlistId))
                .commit()
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, GroupRenameActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}