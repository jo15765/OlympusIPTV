package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind

class EpgEditsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_host)
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        val kind = if (intent.getStringExtra(EXTRA_KIND) == ContentKind.VOD.name) {
            ContentKind.VOD
        } else {
            ContentKind.LIVE
        }
        val includeVod = intent.getBooleanExtra(EXTRA_INCLUDE_VOD, false)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentHost,
                    EpgEditsFragment.newInstance(playlistId, kind, includeVod)
                )
                .commit()
        }
        supportFragmentManager.setFragmentResultListener(EpgEditsFragment.RESULT_KEY, this) { _, _ ->
            setResult(RESULT_OK)
            if (!isFinishing) finish()
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_INCLUDE_VOD = "include_vod"

        fun intent(context: Context, playlistId: String, kind: ContentKind, includeVod: Boolean): Intent =
            Intent(context, EpgEditsActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_INCLUDE_VOD, includeVod)
    }
}