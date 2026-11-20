package com.olympus.iptv.ui.countries

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R

class CountriesActivity : AppCompatActivity() {
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
                .replace(R.id.fragmentHost, CountriesFragment.newInstance(playlistId))
                .commit()
        }
        supportFragmentManager.setFragmentResultListener(CountriesFragment.REQUEST_SAVED, this) { _, _ ->
            setResult(RESULT_OK)
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, CountriesActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}